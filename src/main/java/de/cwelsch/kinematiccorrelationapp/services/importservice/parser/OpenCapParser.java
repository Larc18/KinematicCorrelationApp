package de.cwelsch.kinematiccorrelationapp.services.importservice.parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Concrete parser implementation responsible for processing tabular OpenCap motion files (.mot).
 * Extracts metadata header configurations and time-series kinematic joint streams.
 * Includes inline coordinate system harmonization for known orientation offsets.
 */
public class OpenCapParser implements Parser {

    private static final Pattern INT_PATTERN = Pattern.compile("^\\d+$");
    private static final Pattern DOUBLE_PATTERN = Pattern.compile("^\\d+\\.\\d+$");

    // CRITICAL FIX: Erfasst ein oder mehrere Whitespace-Zeichen (Tabs ODER Leerzeichen) als kombinierten Trenner
    private static final Pattern WHITESPACE_SPLIT_PATTERN = Pattern.compile("\\s+");

    // INGESTION-HARMONISIERUNG: Kanäle mit bekanntem 90°-Orientierungs-Offset zwischen OpenSim und Theia
    private static final Set<String> CHANNELS_WITH_90_DEGREE_OFFSET = Set.of(
            "pelvis_list",
            "lumbar_bending",
            "Beckenneigung (List)",
            "LWS Seitneigung"
    );

    private static class OpenCapMotionData implements MotionData {
        private final Map<String, Object> metadata = new LinkedHashMap<>();
        private final Map<String, List<Double>> data = new LinkedHashMap<>();

        @Override
        public Map<String, Object> getMetaData() {
            return metadata;
        }

        @Override
        public Map<String, List<Double>> getData() {
            return data;
        }
    }

    /**
     * Parses an OpenCap {@code .mot} file, extracting the metadata header and the time-series
     * joint angle columns, and applies the known 90°-offset harmonization to affected channels.
     *
     * @param filePath the path to the OpenCap {@code .mot} file to parse
     * @return the parsed metadata and column data
     * @throws IOException if the file does not exist or cannot be read
     */
    @Override
    public MotionData parse(String filePath) throws IOException {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("The target file path string cannot be null or empty.");
        }

        Path targetPath = Path.of(filePath);
        if (!Files.exists(targetPath)) {
            throw new IOException("Specified OpenCap file destination does not exist: " + filePath);
        }

        OpenCapMotionData motionData = new OpenCapMotionData();

        try (BufferedReader br = Files.newBufferedReader(targetPath)) {
            String line;
            boolean inHeader = true;
            List<String> headers = new ArrayList<>();

            // Stage 1: Parse metadata header segment
            while ((line = br.readLine()) != null) {
                String cleaned = line.trim();
                if (cleaned.isEmpty()) {
                    continue;
                }

                if (cleaned.equalsIgnoreCase("endheader")) {
                    inHeader = false;

                    // The line immediately following "endheader" defines the column indices
                    String headerLine = br.readLine();
                    if (headerLine != null) {
                        // CRITICAL FIX: Nutzt den robusten Whitespace-Trenner auf der getrimmten Zeile
                        String[] splitHeaders = WHITESPACE_SPLIT_PATTERN.split(headerLine.trim());
                        for (String h : splitHeaders) {
                            String trimmedH = h.trim();
                            if (!trimmedH.isEmpty()) {
                                // Standardisiere den Zeit-Key auf Kleinschreibung, um Case-Fehler im ImportService zu blockieren
                                if (trimmedH.equalsIgnoreCase("time")) {
                                    trimmedH = "time";
                                }
                                headers.add(trimmedH);
                                motionData.getData().put(trimmedH, new ArrayList<>());
                            }
                        }
                    }
                    break;
                }

                if (inHeader && cleaned.contains("=")) {
                    String[] parts = cleaned.split("=", 2);
                    String key = parts[0].trim();
                    String val = parts[1].trim();

                    if (INT_PATTERN.matcher(val).matches()) {
                        motionData.getMetaData().put(key, Integer.parseInt(val));
                    } else if (DOUBLE_PATTERN.matcher(val).matches()) {
                        motionData.getMetaData().put(key, Double.parseDouble(val));
                    } else {
                        motionData.getMetaData().put(key, val);
                    }
                }
            }

            // Stage 2: Stream coordinate data rows
            while ((line = br.readLine()) != null) {
                String cleaned = line.trim();
                if (cleaned.isEmpty()) {
                    continue;
                }

                // CRITICAL FIX: Trimmt die Zeile vorab, damit führende Tabs kein leeres Token an Index 0 erzeugen
                String[] values = WHITESPACE_SPLIT_PATTERN.split(cleaned);

                // Takt-Validierung: Eine Zeile muss exakt so viele Werte haben wie der Header Spalten besitzt
                if (values.length < headers.size()) {
                    continue; // Überspringe korrupte oder unvollständige Zeilenfragmente
                }

                for (int colIndex = 0; colIndex < headers.size(); colIndex++) {
                    String val = values[colIndex].trim();
                    String headerName = headers.get(colIndex);

                    try {
                        double num;
                        if (val.isEmpty() || val.equalsIgnoreCase("nan") || val.equalsIgnoreCase("inf")) {
                            num = Double.NaN;
                        } else {
                            num = Double.parseDouble(val);
                            // Direct Ingestion Harmonization for Coordinate System Offsets
                            num = harmonizeCoordinateOffset(headerName, num);
                        }

                        List<Double> columnData = motionData.getData().get(headerName);
                        if (columnData != null) {
                            columnData.add(num);
                        }
                    } catch (NumberFormatException e) {
                        // Trage kontrolliert NaN ein, um die Zeilensynchronisation unter keinen Umständen zu verlieren
                        List<Double> columnData = motionData.getData().get(headerName);
                        if (columnData != null) {
                            columnData.add(Double.NaN);
                        }
                    }
                }
            }
        }

        return motionData;
    }

    /**
     * Korrigiert systematische 90°-Koordinaten-Offsets direkt beim Einlesen im Parser.
     * Schützt den RMSE davor, durch reine Nullachsen-Konventionen künstlich verfälscht zu werden.
     */
    private double harmonizeCoordinateOffset(String channelName, double rawAngle) {
        if (Double.isNaN(rawAngle)) {
            return Double.NaN;
        }

        if (CHANNELS_WITH_90_DEGREE_OFFSET.contains(channelName)) {
            // Zieht die 90° Offset-Verschiebung ab und normalisiert in den Bereich [-180°, 180°]
            double corrected = rawAngle - 90.0;
            while (corrected < -180.0) corrected += 360.0;
            while (corrected > 180.0) corrected -= 360.0;
            return corrected;
        }
        return rawAngle;
    }
}