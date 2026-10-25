package de.cwelsch.kinematiccorrelationapp.services.statisticalanalysisservice;

import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Movement;
import de.cwelsch.kinematiccorrelationapp.model.Trial;
import de.cwelsch.kinematiccorrelationapp.model.TrialSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // Sammelt pro Proband (Schlüssel: Participant.getId()) einen RMSE-Wert je Movement/Joint-
    // Kombination, in der er Daten hat. Grundlage für einen Einstichproben-t-Test der
    // Probanden-RMSE gegen einen fixen Referenzwert (z.B. 8°) — dafür wird EIN RMSE-Wert PRO
    // PROBAND benötigt, nicht die punktweise über die Kohorte gemittelte 101-Punkte-Kurve.
    private final Map<String, List<Double>> perParticipantRmseSamples = new HashMap<>();

    // Analog zu perParticipantRmseSamples, aber für den systematischen Versatz: Bias
    // (Mittelwert von OpenCap − Theia über die 101 Punkte) und der um diesen Versatz bereinigte
    // zentrierte RMSE, je Proband und Movement/Joint-Kombination. Der Bias trennt einen konstanten
    // Offset (z.B. unterschiedliche Modell-Nulllage der Systeme) vom verbleibenden
    // Kurvenverlaufsfehler: rmseCentered² = rmse² − bias².
    private final Map<String, List<Double>> perParticipantBiasSamples = new HashMap<>();
    private final Map<String, List<Double>> perParticipantRmseCenteredSamples = new HashMap<>();

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

                // Je Proband genau EIN Bias- und EIN zentrierter RMSE-Wert für DIESE Movement/
                // Joint-Kombination. Diese Kennwerte sind bewusst keine 101-Punkte-Kurven: Der Bias
                // ist der über die Zeitachse gemittelte Versatz eines Probandenkurvenpaares und
                // wird erst anschließend über die Kohorte gemittelt.
                List<Double> jointBiasValues = new ArrayList<>();
                List<Double> jointRmseCenteredValues = new ArrayList<>();

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
                        double[] participantOcCurve = averageCurves(participantOcCurves);
                        double[] participantThCurve = averageCurves(participantThCurves);
                        allOpenCapCurves.add(participantOcCurve);
                        allTheiaCurves.add(participantThCurve);

                        // Pro-Proband-Kennwerte: über die 101 Punkte DIESES EINEN Probanden gemittelt
                        // (statt über die Kohorte an einem festen Zeitpunkt). Liefert in einem Durchgang
                        // RMSE, Bias (systematischer Versatz OpenCap − Theia) und den um diesen Versatz
                        // bereinigten zentrierten RMSE.
                        CurveDeviation deviation = computeCurveDeviation(participantOcCurve, participantThCurve);

                        if (!Double.isNaN(deviation.bias())) {
                            jointBiasValues.add(deviation.bias());
                            jointRmseCenteredValues.add(deviation.rmseCentered());
                        }

                        // Geschlüsselt über die echte Subject-ID statt Listenposition, da Sessions ohne
                        // Daten für dieses Movement/Joint oben per continue übersprungen werden und die
                        // Listenindizes dadurch zwischen Movement/Joint-Durchläufen nicht stabil
                        // dieselbe Person referenzieren.
                        String participantId = session.getParticipant() != null ? session.getParticipant().getId() : null;
                        if (participantId != null && !Double.isNaN(deviation.rmse())) {
                            perParticipantRmseSamples
                                    .computeIfAbsent(participantId, id -> new ArrayList<>())
                                    .add(deviation.rmse());
                            perParticipantBiasSamples
                                    .computeIfAbsent(participantId, id -> new ArrayList<>())
                                    .add(deviation.bias());
                            perParticipantRmseCenteredSamples
                                    .computeIfAbsent(participantId, id -> new ArrayList<>())
                                    .add(deviation.rmseCentered());
                        }
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

                // Optimierung: Berechne die durchschnittliche Range of Motion (ROM) pro Proband,
                // um Verzerrungen durch globale Extremwerte auszuschließen. Die ROM wird dabei
                // NICHT nur aus OpenCap bestimmt, sondern aus der punktweise gemittelten Kurve
                // von OpenCap und Theia3D — damit die NRMSE-Normierung systemübergreifend und
                // nicht einseitig auf ein System bezogen ist.
                double totalRomSum = 0;
                int validRomCount = 0;
                for (int i = 0; i < allOpenCapCurves.size(); i++) {
                    double[] ocCurve = allOpenCapCurves.get(i);
                    double[] thCurve = allTheiaCurves.get(i);

                    double localMin = Double.MAX_VALUE;
                    double localMax = -Double.MAX_VALUE;
                    for (int p = 0; p < NORMALIZED_TIMELINE_POINTS; p++) {
                        double ocVal = ocCurve[p];
                        double thVal = thCurve[p];
                        if (Double.isNaN(ocVal) || Double.isNaN(thVal)) continue;

                        double meanVal = (ocVal + thVal) / 2.0;
                        localMin = Math.min(localMin, meanVal);
                        localMax = Math.max(localMax, meanVal);
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

                // Bias und zentrierter RMSE über die Kohorte: erst pro Proband über die Zeitachse,
                // dann über die Probanden gemittelt (nicht umgekehrt — sonst würden sich positive und
                // negative Probandenversätze beim zentrierten RMSE gegenseitig wegkürzen).
                double meanBias = Double.NaN;
                double meanRmseCentered = Double.NaN;
                if (!jointBiasValues.isEmpty()) {
                    meanBias = jointBiasValues.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
                    meanRmseCentered = jointRmseCenteredValues.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
                    results.setBiasResultsForJoint(movement, joint, meanBias, meanRmseCentered);
                }

                // Output mit integriertem NRMSE sowie Bias/zentriertem RMSE im Konsolen-Log
                System.out.printf("%-15s | %-35s | 0%%: ICC=%.4f RMSE=%.2f° (NRMSE=%.1f%%) | 50%%: ICC=%.4f RMSE=%.2f° (NRMSE=%.1f%%) | Bias=%.2f° RMSE_zentriert=%.2f°%n",
                        movement.name(), joint.getDisplayName(),
                        icc101Points[0], rmse101Points[0], nrmse101Points[0],
                        icc101Points[50], rmse101Points[50], nrmse101Points[50],
                        meanBias, meanRmseCentered);
            }
        }

        // Pro-Proband-Mittelwert über alle erfassten Movement/Joint-Kombinationen (z.B. 4 Movements
        // x 3 Gelenke = bis zu 12 Werte pro Proband) zu genau EINEM RMSE-, Bias- und zentrierten
        // RMSE-Wert pro Proband verdichten.
        results.setPerParticipantMeanRmse(averagePerParticipant(perParticipantRmseSamples));
        results.setPerParticipantMeanBias(
                averagePerParticipant(perParticipantBiasSamples),
                averagePerParticipant(perParticipantRmseCenteredSamples));

        System.out.println("Statistical analysis complete.");
        return results;
    }

    /**
     * Abweichungskennwerte eines einzelnen Kurvenpaares (ein Proband, eine Movement/Joint-Kombination).
     *
     * @param bias         Mittlere signierte Differenz OpenCap − Theia über die Zeitachse. Positiv
     *                     bedeutet: OpenCap liegt systematisch höher als Theia. Entspricht einem
     *                     konstanten Versatz, z.B. durch unterschiedliche Modell-Nulllagen.
     * @param rmse         Klassischer RMSE (enthält den Versatz).
     * @param rmseCentered RMSE nach Abzug des konstanten Versatzes — beschreibt nur noch die
     *                     Abweichung im Kurvenverlauf. Es gilt rmseCentered² = rmse² − bias².
     */
    private record CurveDeviation(double bias, double rmse, double rmseCentered) {
    }

    /**
     * Berechnet Bias, RMSE und zentrierten RMSE zwischen zwei 101-Punkte-Kurven DESSELBEN Probanden
     * (gleiche Bewegung & gleiches Gelenk, NaN-tolerant). Im Gegensatz zur punktweisen Kohorten-
     * Aggregation in {@link #analyze()} liefert dies Skalarwerte für genau einen Probanden.
     * <p>
     * Der Bias trennt einen konstanten Versatz zwischen den Systemen vom verbleibenden
     * Verlaufsfehler: Ein großer RMSE bei kleinem zentrierten RMSE bedeutet, dass die Kurvenform gut
     * übereinstimmt und die Abweichung praktisch vollständig aus einem festen Offset stammt.
     * <p>
     * Punkte, an denen eine der beiden Kurven NaN ist, werden in allen drei Kennwerten konsistent
     * übersprungen (statt sie als 0-Differenz zu werten), damit Bias und zentrierter RMSE auf
     * derselben Punktmenge basieren wie der RMSE.
     */
    private CurveDeviation computeCurveDeviation(double[] curveA, double[] curveB) {
        double[] deltas = new double[NORMALIZED_TIMELINE_POINTS];
        int count = 0;
        double biasSum = 0;

        for (int p = 0; p < NORMALIZED_TIMELINE_POINTS; p++) {
            double a = curveA[p];
            double b = curveB[p];
            if (Double.isNaN(a) || Double.isNaN(b)) continue;
            deltas[count++] = a - b;
            biasSum += a - b;
        }

        if (count == 0) {
            return new CurveDeviation(Double.NaN, Double.NaN, Double.NaN);
        }

        double bias = biasSum / count;

        double squaredSum = 0;
        double squaredCenteredSum = 0;
        for (int i = 0; i < count; i++) {
            double delta = deltas[i];
            squaredSum += delta * delta;
            squaredCenteredSum += (delta - bias) * (delta - bias);
        }

        double rmse = Math.sqrt(squaredSum / count);                  // wie bisher
        double rmseCentered = Math.sqrt(squaredCenteredSum / count);  // ohne konstanten Versatz
        return new CurveDeviation(bias, rmse, rmseCentered);
    }

    /**
     * Verdichtet die je Movement/Joint gesammelten Einzelwerte eines Probanden zu genau EINEM
     * Mittelwert pro Proband. Die Einfügereihenfolge der Probanden bleibt erhalten, damit die
     * Wertelisten von RMSE, Bias und zentriertem RMSE im Export zeilenweise zueinander passen.
     */
    private Map<String, Double> averagePerParticipant(Map<String, List<Double>> samplesByParticipant) {
        Map<String, Double> meanByParticipant = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> entry : samplesByParticipant.entrySet()) {
            double mean = entry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            meanByParticipant.put(entry.getKey(), mean);
        }
        return meanByParticipant;
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