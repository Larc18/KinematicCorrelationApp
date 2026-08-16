package de.cwelsch.kinematiccorrelationapp.services.statisticalanalysisservice;

import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Movement;
import de.cwelsch.kinematiccorrelationapp.model.Trial;
import de.cwelsch.kinematiccorrelationapp.model.TrialSession;

import java.util.ArrayList;
import java.util.List;

/**
 * Service responsible for executing aggregate cross-sectional statistical analyses.
 * Extracts matching curves across all processed subjects, computing concurrent timeline vectors
 * for Intraclass Correlation Coefficients (ICC), Root Mean Square Errors (RMSE), and Normalized RMSE (NRMSE).
 * Fixed filename matching engine to support diverse system data extensions (.mot vs .json).
 */
public class StatisticalAnalysisService {

    private static final int NORMALIZED_TIMELINE_POINTS = 101;
    // Fenstergröße für die punktweise ICC-Glättung entlang der 101-Punkte-Zeitachse (zentrierter
    // gleitender Mittelwert, ±2 Punkte). Reduziert Punkt-zu-Punkt-Rauschen bei kleiner Stichprobe,
    // ohne den groben Kurvenverlauf zu verfälschen.
    private static final int ICC_SMOOTHING_RADIUS = 2;
    private final List<TrialSession> trialSessions;

    /**
     * Instantiates the aggregate analytical framework engine.
     *
     * @param trialSessions The collection cohort containing populated participant sessions.
     */
    public StatisticalAnalysisService(List<TrialSession> trialSessions) {
        this.trialSessions = trialSessions;
    }

    /**
     * Processes cross-subject tracking matrices across all active movement and joint configurations.
     *
     * @return An aggregated AnalysisResults instance populated with calculated discrete 101-point vectors.
     */
    public AnalysisResults analyze() {
        System.out.println("========================================");
        System.out.println("Starting StatisticalAnalysisService");
        System.out.println("========================================");

        AnalysisResults results = new AnalysisResults();

        // Outer loop execution layer: Process per discrete motor task category
        for (Movement movement : Movement.values()) {

            // Intermediary execution layer: Process per in-scope anatomical tracking joint profile
            // (Hüfte/Knie/Sprunggelenk — siehe Joints.ANALYSIS_SCOPE)
            for (Joints joint : Joints.ANALYSIS_SCOPE) {

                List<double[]> allOpenCapCurves = new ArrayList<>();
                List<double[]> allTheiaCurves = new ArrayList<>();

                // Cohort iteration block: Aggregate normalized coordinates over all loaded participant sessions
                for (TrialSession session : this.trialSessions) {
                    List<Trial> trials = session.getTrials();
                    if (trials == null) continue;

                    // Trenne OpenCap und Theia explizit nach Flag – nie nach Index
                    List<Trial> openCapTrials = trials.stream()
                            .filter(t -> t != null && t.isOpenCap() && t.getMovement() == movement)
                            .toList();
                    List<Trial> theiaTrials = trials.stream()
                            .filter(t -> t != null && !t.isOpenCap() && t.getMovement() == movement)
                            .toList();

                    // CLUSTERING FIX: Ein Proband liefert bis zu 3 Wiederholungstrials pro Movement.
                    // Diese Trials sind NICHT unabhängig (dieselbe Person) — würde man sie einzeln als
                    // "Subjects" in die ICC(3,1)-ANOVA geben, würde die Unabhängigkeitsannahme verletzt
                    // und die Stichprobengröße künstlich aufgebläht (z.B. 14 Probanden x 3 Trials = 42
                    // "Subjects" statt der tatsächlichen 14 unabhängigen Probanden). Fix: Erst alle
                    // Trial-Paare EINES Probanden punktweise zu einer einzigen Mittelwertkurve pro
                    // System zusammenfassen, danach genau EIN (OpenCap, Theia)-Kurvenpaar pro Proband
                    // in die Statistik geben.
                    List<double[]> participantOcCurves = new ArrayList<>();
                    List<double[]> participantThCurves = new ArrayList<>();

                    // Matche Paare über den reinen Dateinamen OHNE Endung (z.B. "SQT_0001")
                    for (Trial ocTrial : openCapTrials) {
                        String ocFileName = ocTrial.getSourceFileName().toLowerCase().trim();
                        if (!ocFileName.contains(".")) continue;
                        String ocBaseName = ocFileName.substring(0, ocFileName.lastIndexOf('.'));

                        theiaTrials.stream()
                                .filter(th -> {
                                    String thFileName = th.getSourceFileName().toLowerCase().trim();
                                    if (!thFileName.contains(".")) return false;
                                    String thBaseName = thFileName.substring(0, thFileName.lastIndexOf('.'));
                                    return thBaseName.equals(ocBaseName); // Vergleiche reine Basisnamen!
                                })
                                .findFirst()
                                .ifPresent(thTrial -> {
                                    double[] ocAngles = ocTrial.getNormalizedAngles(joint);
                                    double[] thAngles = thTrial.getNormalizedAngles(joint);
                                    if (ocAngles != null && thAngles != null) {
                                        participantOcCurves.add(ocAngles);
                                        participantThCurves.add(thAngles);
                                    }
                                });
                    }

                    if (!participantOcCurves.isEmpty()) {
                        allOpenCapCurves.add(averageCurves(participantOcCurves));
                        allTheiaCurves.add(averageCurves(participantThCurves));
                    }
                }

                // Guard boundary checking: Skip if no matching data matrices populate the targeted parameters
                if (allOpenCapCurves.isEmpty()) {
                    continue;
                }

                int numberOfParticipants = allOpenCapCurves.size();
                double[] icc101Points = new double[NORMALIZED_TIMELINE_POINTS];
                double[] rmse101Points = new double[NORMALIZED_TIMELINE_POINTS];
                double[] nrmse101Points = new double[NORMALIZED_TIMELINE_POINTS];

                // Optimierung: Berechne die durchschnittliche Range of Motion (ROM) pro Kurve/Trial,
                // um Verzerrungen durch globale Extremwerte auszuschließen.
                double totalRomSum = 0;
                int validRomCount = 0;
                for (double[] curve : allOpenCapCurves) {
                    double localMin = Double.MAX_VALUE;
                    double localMax = -Double.MAX_VALUE;
                    for (double val : curve) {
                        if (!Double.isNaN(val)) {
                            localMin = Math.min(localMin, val);
                            localMax = Math.max(localMax, val);
                        }
                    }
                    if (localMax > localMin) {
                        totalRomSum += (localMax - localMin);
                        validRomCount++;
                    }
                }
                double meanRom = (validRomCount > 0) ? (totalRomSum / validRomCount) : 1.0;
                if (meanRom < 1e-6) meanRom = 1.0; // Schutz vor Division durch 0

                // Timeline frame loop execution layer: Calculate metrics concurrently across all 101 points
                for (int p = 0; p < NORMALIZED_TIMELINE_POINTS; p++) {
                    double[] sampleA = new double[numberOfParticipants];
                    double[] sampleB = new double[numberOfParticipants];

                    for (int t = 0; t < numberOfParticipants; t++) {
                        sampleA[t] = allOpenCapCurves.get(t)[p];
                        sampleB[t] = allTheiaCurves.get(t)[p];
                    }

                    // Execute model-3 single-measure ICC formula calculation checks
                    icc101Points[p] = IntraClassCorrelationCoefficient.calculateICC31(sampleA, sampleB);

                    // Compute baseline root mean square deviation values across cross-sectional tracking lines
                    double squaredErrorsSum = 0;
                    for (int t = 0; t < numberOfParticipants; t++) {
                        double delta = sampleA[t] - sampleB[t];
                        squaredErrorsSum += delta * delta;
                    }
                    double rmse = Math.sqrt(squaredErrorsSum / numberOfParticipants);
                    rmse101Points[p] = rmse;

                    // NRMSE in Prozent (%) bezogen auf die mittlere Trial-ROM
                    nrmse101Points[p] = (rmse / meanRom) * 100.0;
                }

                // SMOOTHING FIX: Punktweise ICC-Werte werden unabhängig pro Zeitpunkt aus einer kleinen
                // Stichprobe (Probandenanzahl) geschätzt und schwanken dadurch stark von Punkt zu Punkt.
                // Ein leichter gleitender Mittelwert entlang der Zeitachse glättet dieses Schätzrauschen,
                // ohne den groben Kurvenverlauf zu verfälschen. RMSE/NRMSE bleiben unangetastet (deren
                // Rauschen ist bereits durch die quadratische Mittelung über alle Probanden gedämpft).
                icc101Points = smoothIcc(icc101Points);

                results.setResultsForJoint(movement, joint, icc101Points, rmse101Points, nrmse101Points);

                // Output mit integriertem NRMSE im Konsolen-Log
                System.out.printf("%-15s | %-35s | 0%%: ICC=%.4f RMSE=%.2f° (NRMSE=%.1f%%) | 50%%: ICC=%.4f RMSE=%.2f° (NRMSE=%.1f%%)%n",
                        movement.name(), joint.getDisplayName(),
                        icc101Points[0], rmse101Points[0], nrmse101Points[0],
                        icc101Points[50], rmse101Points[50], nrmse101Points[50]);
            }
        }

        System.out.println("Statistical analysis complete.");
        return results;
    }

    /**
     * Mittelt mehrere normierte 101-Punkte-Kurven desselben Probanden punktweise zu einer
     * repräsentativen Kurve (NaN-tolerant). Dient dazu, Mehrfach-Trials EINES Probanden vor der
     * ICC/RMSE-Berechnung zu clustern, statt sie als unabhängige Beobachtungen zu behandeln.
     */
    private double[] averageCurves(List<double[]> curves) {
        double[] mean = new double[NORMALIZED_TIMELINE_POINTS];
        for (int p = 0; p < NORMALIZED_TIMELINE_POINTS; p++) {
            double sum = 0;
            int count = 0;
            for (double[] curve : curves) {
                if (p < curve.length && !Double.isNaN(curve[p])) {
                    sum += curve[p];
                    count++;
                }
            }
            mean[p] = count > 0 ? sum / count : Double.NaN;
        }
        return mean;
    }

    /**
     * Zentrierter gleitender Mittelwert über die punktweise ICC-Kurve (Radius siehe
     * ICC_SMOOTHING_RADIUS). Randpunkte nutzen ein asymmetrisch verkleinertes Fenster statt
     * außerhalb der Kurve zu spiegeln oder zu wiederholen. NaN-Werte (z.B. degenerierte
     * Nullvarianz-Punkte, siehe IntraClassCorrelationCoefficient) werden von der Mittelung
     * ausgeschlossen statt sie fälschlich als 0 zu werten.
     */
    private double[] smoothIcc(double[] raw) {
        double[] smoothed = new double[raw.length];
        for (int p = 0; p < raw.length; p++) {
            int from = Math.max(0, p - ICC_SMOOTHING_RADIUS);
            int to = Math.min(raw.length - 1, p + ICC_SMOOTHING_RADIUS);

            double sum = 0;
            int count = 0;
            for (int i = from; i <= to; i++) {
                if (!Double.isNaN(raw[i])) {
                    sum += raw[i];
                    count++;
                }
            }
            smoothed[p] = count > 0 ? sum / count : Double.NaN;
        }
        return smoothed;
    }
}