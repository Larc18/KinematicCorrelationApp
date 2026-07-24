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

            // Intermediary execution layer: Process per registered anatomical tracking joint profile
            for (Joints joint : Joints.values()) {

                List<double[]> allOpenCapCurves = new ArrayList<>();
                List<double[]> allTheiaCurves   = new ArrayList<>();

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
                                        allOpenCapCurves.add(ocAngles);
                                        allTheiaCurves.add(thAngles);
                                    }
                                });
                    }
                }

                // Guard boundary checking: Skip if no matching data matrices populate the targeted parameters
                if (allOpenCapCurves.isEmpty()) {
                    continue;
                }

                int numberOfPairs = allOpenCapCurves.size();
                double[] icc101Points   = new double[NORMALIZED_TIMELINE_POINTS];
                double[] rmse101Points  = new double[NORMALIZED_TIMELINE_POINTS];
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
                    double[] sampleA = new double[numberOfPairs];
                    double[] sampleB = new double[numberOfPairs];

                    for (int t = 0; t < numberOfPairs; t++) {
                        sampleA[t] = allOpenCapCurves.get(t)[p];
                        sampleB[t] = allTheiaCurves.get(t)[p];
                    }

                    // Execute model-3 single-measure ICC formula calculation checks
                    icc101Points[p] = IntraClassCorrelationCoefficient.calculateICC31(sampleA, sampleB);

                    // Compute baseline root mean square deviation values across cross-sectional tracking lines
                    double squaredErrorsSum = 0;
                    for (int t = 0; t < numberOfPairs; t++) {
                        double delta = sampleA[t] - sampleB[t];
                        squaredErrorsSum += delta * delta;
                    }
                    double rmse = Math.sqrt(squaredErrorsSum / numberOfPairs);
                    rmse101Points[p] = rmse;

                    // NRMSE in Prozent (%) bezogen auf die mittlere Trial-ROM
                    nrmse101Points[p] = (rmse / meanRom) * 100.0;
                }

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
}