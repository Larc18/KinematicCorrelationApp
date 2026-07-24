package de.cwelsch.kinematiccorrelationapp.services.importservice.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Concrete parser implementation responsible for processing hierarchical JSON Theia Markerless tracking assets.
 * Extracts 3D joint center trajectory tracking vectors from structural node arrays utilizing Jackson Databind.
 * Additionally derives Euler rotation angles from the 4x4 homogeneous transformation matrix for the
 * pelvis and abdomen segments, enabling direct cross-system comparison with OpenCap rotation channels.
 */
public class TheiaMarkerlessParser implements Parser {

    private static class TheiaMotionData implements MotionData {
        private final Map<String, Object> metadata = new LinkedHashMap<>();
        private final Map<String, List<Double>> data = new LinkedHashMap<>();

        @Override
        public Map<String, Object> getMetaData() { return metadata; }

        @Override
        public Map<String, List<Double>> getData() { return data; }
    }

    @Override
    public MotionData parse(String filePath) throws IOException {
        System.out.println("[DEBUG] TheiaMarkerlessParser (JSON-Engine) gestartet für: " + filePath);

        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("The target file path string cannot be null or empty.");
        }

        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("Specified Theia JSON destination does not exist: " + filePath);
        }

        TheiaMotionData motionData = new TheiaMotionData();
        ObjectMapper mapper = new ObjectMapper();

        JsonNode rootNode = mapper.readTree(file);

        // Metadaten & Frame-Rate auslesen
        JsonNode trialNode = rootNode.path("trial");
        double frameRate = trialNode.path("frame_rate").asDouble(85.0);
        motionData.getMetaData().put("frame_rate", frameRate);

        JsonNode subjectsArray = rootNode.path("data").path("subjects");
        if (!subjectsArray.isArray() || subjectsArray.isEmpty()) {
            System.err.println("[Warning] No active subjects found in Theia JSON data block.");
            return motionData;
        }

        JsonNode segmentsArray = subjectsArray.get(0).path("segments");
        if (!segmentsArray.isArray()) {
            return motionData;
        }

        // Zeitachse initialisieren
        List<Double> timeColumn = new ArrayList<>();
        motionData.getData().put("time", timeColumn);

        for (JsonNode segment : segmentsArray) {
            String segmentName = segment.path("name").asText().toLowerCase().trim();
            JsonNode poseArray = segment.path("pose");

            if (!poseArray.isArray() || poseArray.isEmpty()) continue;

            int totalFrames = poseArray.size();

            // Zeitachse nur einmalig beim ersten gültigen Segment befüllen
            if (timeColumn.isEmpty()) {
                for (int f = 0; f < totalFrames; f++) {
                    timeColumn.add(f * (1.0 / frameRate));
                }
            }

            // --- Translation: Spalte 4 der homogenen Matrix (Indizes 3, 7, 11) ---
            List<Double> xCoords = new ArrayList<>(totalFrames);
            List<Double> yCoords = new ArrayList<>(totalFrames);
            List<Double> zCoords = new ArrayList<>(totalFrames);

            // --- Rotation (nur für Becken und Rumpf relevant) ---
            List<Double> rotTilt     = new ArrayList<>(totalFrames); // X-Rotation: Tilt
            List<Double> rotList     = new ArrayList<>(totalFrames); // Z-Rotation: List
            List<Double> rotRotation = new ArrayList<>(totalFrames); // Y-Rotation: Rotation

            boolean extractRotation = segmentName.equals("pelvis") || segmentName.equals("abdomen");

            for (JsonNode framePose : poseArray) {
                if (framePose.isArray() && framePose.size() >= 12) {

                    // Translation
                    xCoords.add(framePose.get(3).asDouble(Double.NaN));
                    yCoords.add(framePose.get(7).asDouble(Double.NaN));
                    zCoords.add(framePose.get(11).asDouble(Double.NaN));

                    // Euler-Winkel aus der 3x3-Rotationsuntermatrix (ZYX-Konvention)
                    // Flache Matrix-Layout: [R00, R01, R02, Tx, R10, R11, R12, Ty, R20, R21, R22, Tz, 0, 0, 0, 1]
                    if (extractRotation) {
                        double r00 = framePose.get(0).asDouble();
                        double r10 = framePose.get(4).asDouble();
                        double r20 = framePose.get(8).asDouble();
                        double r21 = framePose.get(9).asDouble();
                        double r22 = framePose.get(10).asDouble();

                        // Gimbal-Lock-Schutz: wenn cos(pitch) ≈ 0
                        double cosY = Math.sqrt(r00 * r00 + r10 * r10);
                        boolean singular = cosY < 1e-6;

                        double eulerX, eulerY, eulerZ;
                        if (!singular) {
                            eulerX = Math.toDegrees(Math.atan2(r21, r22));   // Tilt  (Sagittal)
                            eulerY = Math.toDegrees(Math.atan2(-r20, cosY));  // Rotation (Transversal)
                            eulerZ = Math.toDegrees(Math.atan2(r10, r00));    // List (Frontal)
                        } else {
                            // Degenerate case: Gimbal Lock
                            double r01 = framePose.get(1).asDouble();
                            double r02 = framePose.get(2).asDouble();
                            eulerX = Math.toDegrees(Math.atan2(-r02, r01));
                            eulerY = Math.toDegrees(Math.atan2(-r20, cosY));
                            eulerZ = 0.0;
                        }

                        rotTilt.add(eulerX);
                        rotRotation.add(eulerY);
                        rotList.add(eulerZ);
                    }

                } else {
                    xCoords.add(Double.NaN);
                    yCoords.add(Double.NaN);
                    zCoords.add(Double.NaN);
                    if (extractRotation) {
                        rotTilt.add(Double.NaN);
                        rotRotation.add(Double.NaN);
                        rotList.add(Double.NaN);
                    }
                }
            }

            // Translations-Kanäle registrieren
            motionData.getData().put(segmentName + "_x", xCoords);
            motionData.getData().put(segmentName + "_y", yCoords);
            motionData.getData().put(segmentName + "_z", zCoords);

            // Rotations-Kanäle unter OpenCap-kompatiblen Keys registrieren
            if (extractRotation && segmentName.equals("pelvis")) {
                motionData.getData().put("pelvis_tilt",     rotTilt);
                motionData.getData().put("pelvis_list",     rotList);
                motionData.getData().put("pelvis_rotation", rotRotation);
            }
            if (extractRotation && segmentName.equals("abdomen")) {
                motionData.getData().put("lumbar_extension", rotTilt);
                motionData.getData().put("lumbar_bending",   rotList);
                motionData.getData().put("lumbar_rotation",  rotRotation);
            }
        }

        System.out.println("[DEBUG] JSON Parsing abgeschlossen. Kanäle extrahiert: " + motionData.getData().size());
        return motionData;
    }
}