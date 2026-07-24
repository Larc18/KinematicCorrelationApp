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

    private static final List<Joints> JOINT_ORDER = List.of(
            Joints.PELVIS_TILT, Joints.PELVIS_LIST, Joints.PELVIS_ROTATION,
            Joints.LUMBAR_ROTATION,
            Joints.HIP_FLEXION_R, Joints.HIP_FLEXION_L,
            Joints.KNEE_ANGLE_R, Joints.KNEE_ANGLE_L,
            Joints.ANKLE_ANGLE_R, Joints.ANKLE_ANGLE_L
    );

    public CsvExportService(String targetFolderURL) {
        this.targetFolderURL = targetFolderURL;
    }

    /**
     * Exportiert die statistischen Gütekriterien direkt als CSV-Datei.
     */
    public void exportAggregatedMetricsToCsv(List<TrialSession> trialSessions, AnalysisResults statisticalResults) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path csvPath = Path.of(targetFolderURL, "biomechanical_metrics_" + timestamp + ".csv");

        System.out.println("========================================");
        System.out.println("Starting Aggregated CsvExportService");
        System.out.println("Export destination: " + csvPath.toAbsolutePath());

        // Präsenz-Map aufbauen wie im HTML-Export, um nur vorhandene Kombinationen zu exportieren
        Map<Movement, Map<Joints, Boolean>> activeCombinations = new HashMap<>();

        for (TrialSession session : trialSessions) {
            for (Trial trial : session.getTrials()) {
                if (trial == null || trial.getMovement() == null) continue;
                Movement mv = trial.getMovement();

                for (Joints joint : Joints.values()) {
                    double[] normalized101 = trial.getNormalizedAngles(joint);
                    if (normalized101 != null && normalized101.length >= 101) {
                        activeCombinations
                                .computeIfAbsent(mv, k -> new HashMap<>())
                                .put(joint, true);
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
                    "Validitaetsstufe"
            ));
            writer.newLine();

            // Äußere Schleife über Bewegungen
            for (Movement mv : MOVEMENT_ORDER) {
                if (!activeCombinations.containsKey(mv)) continue;

                // Innere Schleife über Gelenke (Proximal -> Distal)
                for (Joints joint : JOINT_ORDER) {
                    if (!activeCombinations.get(mv).containsKey(joint)) continue;

                    double[] iccArray  = statisticalResults.getIccForJoint(mv, joint);
                    double[] rmseArray = statisticalResults.getRmseForJoint(mv, joint);
                    if (iccArray == null || rmseArray == null) continue;

                    double aggregateIcc  = Arrays.stream(iccArray).filter(v -> !Double.isNaN(v)).summaryStatistics().getAverage();
                    double aggregateRmse = Arrays.stream(rmseArray).filter(v -> !Double.isNaN(v)).summaryStatistics().getAverage();

                    String validityText;
                    if      (aggregateIcc >= 0.75) validityText = "Exzellent";
                    else if (aggregateIcc >= 0.40) validityText = "Moderat";
                    else                           validityText = "Mangelhaft";

                    // Zeile mit deutscher Lokalisierung schreiben (Komma statt Punkt für deutsches Excel)
                    String line = String.format(Locale.GERMAN, "%s%s%s%s%.4f%s%.2f%s%s",
                            mv.name(), CSV_SEPARATOR,
                            joint.getDisplayName(), CSV_SEPARATOR,
                            aggregateIcc, CSV_SEPARATOR,
                            aggregateRmse, CSV_SEPARATOR,
                            validityText
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