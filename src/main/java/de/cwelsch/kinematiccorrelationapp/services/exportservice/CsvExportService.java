package de.cwelsch.kinematiccorrelationapp.services.exportservice;

import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Movement;
import de.cwelsch.kinematiccorrelationapp.model.Trial;
import de.cwelsch.kinematiccorrelationapp.model.TrialSession;
import de.cwelsch.kinematiccorrelationapp.services.statisticalanalysisservice.AnalysisResults;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Service für den strukturierten CSV-Export aller berechneten biomechanischen Metriken.
 * Nutzt exakt dieselbe Aggregations- und Berechnungslogik wie der HtmlChartExportService.
 * Garantiert direkte Kompatibilität mit Excel (deutsche Formatierung), SPSS und R.
 */
public class CsvExportService {

    private final String targetFolderURL;
    private static final String CSV_SEPARATOR = ";";

    // Fixierte Reihenfolge analog zum HtmlChartExportService
    private static final List<Movement> MOVEMENT_ORDER = List.of(
            Movement.SQUAT,
            Movement.COUNTERMOVEMENT_JUMP,
            Movement.LATERAL_LUNGE_LEFT,
            Movement.LATERAL_LUNGE_RIGHT,
            Movement.BECKEN_RUMPF_ROTATION
    );

    // Fachlicher Analyseumfang der Studie: nur Hüfte/Knie/Sprunggelenk, siehe Joints.ANALYSIS_SCOPE.
    private static final List<Joints> JOINT_ORDER = Joints.ANALYSIS_SCOPE;

    public CsvExportService(String targetFolderURL) {
        this.targetFolderURL = targetFolderURL;
    }

    /**
     * Exports the aggregated quality metrics (ICC, RMSE, validity level, window-fallback ratio) as a CSV file.
     *
     * @param trialSessions      the participant sessions whose trials are evaluated for the fallback ratio
     * @param statisticalResults the previously computed ICC/RMSE results per movement and joint
     * @throws IOException if the CSV file cannot be written
     */
    public void exportAggregatedMetricsToCsv(List<TrialSession> trialSessions, AnalysisResults statisticalResults) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path csvPath = Path.of(targetFolderURL, "biomechanical_metrics_" + timestamp + ".csv");

        System.out.println("========================================");
        System.out.println("Starting Aggregated CsvExportService");
        System.out.println("Export destination: " + csvPath.toAbsolutePath());

        // Präsenz-Map aufbauen wie im HTML-Export, um nur vorhandene Kombinationen zu exportieren.
        // Zählt zusätzlich mit, wie viele der beitragenden Trials auf die Fensterfallback-
        // Erkennung zurückgefallen sind (siehe TimeNormalizationService/MovementWindow).
        Map<Movement, Map<Joints, int[]>> activeCombinations = new HashMap<>(); // int[]{totalTrials, fallbackTrials}

        for (TrialSession session : trialSessions) {
            for (Trial trial : session.getTrials()) {
                if (trial == null || trial.getMovement() == null) continue;
                Movement mv = trial.getMovement();

                for (Joints joint : Joints.ANALYSIS_SCOPE) {
                    double[] normalized101 = trial.getNormalizedAngles(joint);
                    if (normalized101 != null && normalized101.length >= 101) {
                        int[] counts = activeCombinations
                                .computeIfAbsent(mv, k -> new HashMap<>())
                                .computeIfAbsent(joint, k -> new int[2]);
                        counts[0]++;
                        if (trial.isWindowFallbackUsed()) counts[1]++;
                    }
                }
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8)) {
            // Header-Zeile schreiben
            writer.write(String.join(CSV_SEPARATOR,
                    "Bewegungsform",
                    "Anatomischer_Gelenkwinkel",
                    "ICC_Mittelwert",
                    "RMSE_Grad",
                    "Validitaetsstufe",
                    "Fenster_Fallback_Trials"
            ));
            writer.newLine();

            // Äußere Schleife über Bewegungen
            for (Movement mv : MOVEMENT_ORDER) {
                if (!activeCombinations.containsKey(mv)) continue;

                // Innere Schleife über Gelenke (Proximal -> Distal)
                for (Joints joint : JOINT_ORDER) {
                    if (!activeCombinations.get(mv).containsKey(joint)) continue;

                    double[] iccArray = statisticalResults.getIccForJoint(mv, joint);
                    double[] rmseArray = statisticalResults.getRmseForJoint(mv, joint);
                    if (iccArray == null || rmseArray == null) continue;

                    DoubleSummaryStatistics iccStats = Arrays.stream(iccArray).filter(v -> !Double.isNaN(v)).summaryStatistics();
                    DoubleSummaryStatistics rmseStats = Arrays.stream(rmseArray).filter(v -> !Double.isNaN(v)).summaryStatistics();

                    // CRITICAL FIX: Ein leerer Stream (alle 101 Punkte NaN, z.B. weil das Gelenk
                    // überall zu viele fehlende Frames hatte) lieferte hier vorher getAverage()==0.0
                    // -> täuschte fälschlich ein perfektes RMSE (0°) und ein katastrophales ICC (0.0)
                    // vor, obwohl schlicht keine Daten vorlagen. Solche Zeilen werden jetzt übersprungen.
                    if (iccStats.getCount() == 0 || rmseStats.getCount() == 0) continue;

                    double aggregateIcc = iccStats.getAverage();
                    double aggregateRmse = rmseStats.getAverage();

                    String validityText;
                    if (aggregateIcc >= 0.75) validityText = "Exzellent";
                    else if (aggregateIcc >= 0.40) validityText = "Moderat";
                    else validityText = "Mangelhaft";

                    // (b) Sichtbarkeit der Fenster-Fallback-Quote: "2/8" bedeutet, dass 2 von 8
                    // beitragenden Trials keine echte Bewegungsfenster-Erkennung erreicht haben.
                    int[] counts = activeCombinations.get(mv).get(joint);
                    String fallbackText = counts[1] + "/" + counts[0];

                    // Zeile mit deutscher Lokalisierung schreiben (Komma statt Punkt für deutsches Excel)
                    String line = String.format(Locale.GERMAN, "%s%s%s%s%.4f%s%.2f%s%s%s%s",
                            mv.name(), CSV_SEPARATOR,
                            joint.getDisplayName(), CSV_SEPARATOR,
                            aggregateIcc, CSV_SEPARATOR,
                            aggregateRmse, CSV_SEPARATOR,
                            validityText, CSV_SEPARATOR,
                            fallbackText
                    );

                    writer.write(line);
                    writer.newLine();
                }
            }

            System.out.println("CSV Export completed successfully.");
            System.out.println("========================================");
        }
    }
}