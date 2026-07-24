package de.cwelsch.kinematiccorrelationapp.services.exportservice;

import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Movement;
import de.cwelsch.kinematiccorrelationapp.services.statisticalanalysisservice.AnalysisResults;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Service responsible for writing aggregated statistical results to the filesystem.
 * Exports ICC and RMSE values for all 101 normalized time points (0%-100%) per
 * movement and joint combination in wide-format CSV layout.
 */
public class ExportService {

    private final AnalysisResults finalResults;
    private final String rootFolderURL;

    /**
     * Instantiates the filesystem generation exporter service.
     *
     * @param finalResults  The populated cross-sectional statistical matrix output dataset.
     * @param rootFolderURL The target root destination folder location path string.
     */
    public ExportService(AnalysisResults finalResults, String rootFolderURL) {
        this.finalResults = finalResults;
        this.rootFolderURL = rootFolderURL;
    }

    /**
     * Compiles all 101 ICC and RMSE timeline points per movement-joint combination
     * and writes a wide-format CSV spreadsheet asset with one row per combination.
     * Column layout: Movement | Joint | ICC_0..ICC_100 | RMSE_0..RMSE_100
     */
    public void export() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String fileName = "biomechanical_results_" + timestamp + ".csv";
        Path outputPath = Path.of(rootFolderURL).resolve(fileName);

        System.out.println("========================================");
        System.out.println("Starting ExportService");
        System.out.println("Export destination: " + outputPath.toAbsolutePath());

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {

            // Header row: Movement, Joint, ICC_0...ICC_100, RMSE_0...RMSE_100
            StringBuilder header = new StringBuilder("Movement,Joint");
            for (int p = 0; p <= 100; p++) header.append(",ICC_").append(p);
            for (int p = 0; p <= 100; p++) header.append(",RMSE_").append(p);
            writer.write(header.toString());
            writer.newLine();

            // Data rows: one row per movement-joint combination
            for (Movement movement : Movement.values()) {
                for (Joints joint : Joints.values()) {

                    double[] iccArray  = finalResults.getIccForJoint(movement, joint);
                    double[] rmseArray = finalResults.getRmseForJoint(movement, joint);

                    // Skip combinations without recorded tracking metrics
                    if (iccArray == null || rmseArray == null) continue;

                    StringBuilder row = new StringBuilder();
                    row.append(movement.name())
                            .append(",\"")
                            .append(joint.getDisplayName())
                            .append("\"");

                    for (int p = 0; p <= 100; p++) {
                        row.append(",").append(String.format(java.util.Locale.US, "%.4f", iccArray[p]));
                    }
                    for (int p = 0; p <= 100; p++) {
                        row.append(",").append(String.format(java.util.Locale.US, "%.4f", rmseArray[p]));
                    }

                    writer.write(row.toString());
                    writer.newLine();
                }
            }

            System.out.println("Export completed successfully: " + fileName);

        } catch (IOException e) {
            System.err.println("[Error] Data export matrix generation failed: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("========================================");
    }
}