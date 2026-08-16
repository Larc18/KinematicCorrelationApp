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
        public Map<String, Object> getMetaData() {
            return metadata;
        }

        @Override
        public Map<String, List<Double>> getData() {
            return data;
        }
    }

    /**
     * Parses a Theia Markerless JSON export, extracting 3D joint center trajectories and, for the
     * pelvis and abdomen segments, deriving Euler rotation angles (and relative hip flexion) from
     * the 4x4 homogeneous pose matrices so they line up with OpenCap-compatible channel names.
     *
     * @param filePath the path to the Theia JSON file to parse
     * @return the parsed metadata and column data
     * @throws IOException if the file does not exist or cannot be read
     */
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

        // Pose-Arrays von Becken und Oberschenkeln merken, um nach der Segment-Schleife die
        // Hüftflexion aus der RELATIVEN Rotation (Becken -> Oberschenkel) zu berechnen.
        JsonNode pelvisPoseArray = null, rThighPoseArray = null, lThighPoseArray = null;

        for (JsonNode segment : segmentsArray) {
            String segmentName = segment.path("name").asText().toLowerCase().trim();
            JsonNode poseArray = segment.path("pose");

            if (poseArray.isArray() && !poseArray.isEmpty()) {
                if (segmentName.equals("pelvis")) pelvisPoseArray = poseArray;
                if (segmentName.equals("r_thigh")) rThighPoseArray = poseArray;
                if (segmentName.equals("l_thigh")) lThighPoseArray = poseArray;
            }

            if (!poseArray.isArray() || poseArray.isEmpty()) continue;

            int totalFrames = poseArray.size();

            // Zeitachse nur einmalig beim ersten gültigen Segment befüllen
            if (timeColumn.isEmpty()) {
                for (int f = 0; f < totalFrames; f++) {
                    timeColumn.add(f * (1.0 / frameRate));
                }
            } else if (totalFrames != timeColumn.size()) {
                // CRITICAL FIX: Alle Segmente wurden bisher stillschweigend gegen den Zeitindex
                // des ERSTEN Segments ausgerichtet. Weicht ein späteres Segment in der Frame-Anzahl
                // ab, entstehen unbemerkt verschobene Spalten. Das wird jetzt zumindest sichtbar gemacht.
                System.err.printf("  [Warning] Segment '%s' has %d frames, but the time axis has %d frames — columns may be misaligned.%n",
                        segmentName, totalFrames, timeColumn.size());
            }

            // --- Translation: Spalte 4 der homogenen Matrix (Indizes 3, 7, 11) ---
            List<Double> xCoords = new ArrayList<>(totalFrames);
            List<Double> yCoords = new ArrayList<>(totalFrames);
            List<Double> zCoords = new ArrayList<>(totalFrames);

            // --- Rotation (nur für Becken und Rumpf relevant) ---
            List<Double> rotTilt = new ArrayList<>(totalFrames); // Sagittal: Tilt
            List<Double> rotList = new ArrayList<>(totalFrames); // Frontal: List/Bending
            List<Double> rotRotation = new ArrayList<>(totalFrames); // Transversal: Rotation (Z-up-Achse!)

            boolean extractRotation = segmentName.equals("pelvis") || segmentName.equals("abdomen");

            for (JsonNode framePose : poseArray) {
                if (framePose.isArray() && framePose.size() >= 12) {

                    // Translation
                    xCoords.add(framePose.get(3).asDouble(Double.NaN));
                    yCoords.add(framePose.get(7).asDouble(Double.NaN));
                    zCoords.add(framePose.get(11).asDouble(Double.NaN));

                    // Euler-Winkel aus der 3x3-Rotationsuntermatrix (ZYX-Konvention)
                    // Flache Matrix-Layout: [R00, R01, R02, Tx, R10, R11, R12, Ty, R20, R21, R22, Tz, 0, 0, 0, 1]
                    //
                    // CRITICAL FIX: Theia3D liefert seine Segment-Posen in einem Z-UP-Weltkoordinaten-
                    // system, nicht Y-up wie ursprünglich (analog zu OpenSim/OpenCap) angenommen. An
                    // echten BRR-Trials (reine Rumpfrotation) empirisch nachgewiesen: R22 blieb über den
                    // kompletten Bewegungszyklus nahezu konstant (~0.97), während R00 und R11 nahezu
                    // synchron zwischen -1 und +1 pendelten — exakt das Signaturmuster einer Rotation um
                    // die zu R22 gehörende Achse. Die vormals "Rotation (Transversal)" genannte Größe
                    // atan2(-r20, cosMid) blieb dagegen im gesamten Zyklus im einstelligen Gradbereich
                    // (reines Rauschen), während atan2(r10, r00) — vormals fälschlich "List (Frontal)"
                    // genannt — die tatsächliche ~90-170°-Rumpfrotation trug. Die Zuordnung wird hier
                    // entsprechend korrigiert (nur pelvis_rotation/lumbar_rotation und pelvis_list/
                    // lumbar_bending tauschen die Quelle; Tilt/Sagittal war bereits korrekt).
                    if (extractRotation) {
                        double r00 = framePose.get(0).asDouble();
                        double r10 = framePose.get(4).asDouble();
                        double r20 = framePose.get(8).asDouble();
                        double r21 = framePose.get(9).asDouble();
                        double r22 = framePose.get(10).asDouble();

                        // Gimbal-Lock-Schutz: wenn cos(Zwischenwinkel) ≈ 0
                        double cosMid = Math.sqrt(r00 * r00 + r10 * r10);
                        boolean singular = cosMid < 1e-6;

                        double eulerTilt, eulerBending, eulerRotation;
                        if (!singular) {
                            eulerTilt = Math.toDegrees(Math.atan2(r21, r22));      // Sagittal (Tilt)
                            eulerBending = Math.toDegrees(Math.atan2(-r20, cosMid));  // Frontal (List/Bending)
                            eulerRotation = Math.toDegrees(Math.atan2(r10, r00));      // Transversal (Rotation)
                        } else {
                            // Degenerate case: Gimbal Lock
                            double r01 = framePose.get(1).asDouble();
                            double r02 = framePose.get(2).asDouble();
                            eulerTilt = Math.toDegrees(Math.atan2(-r02, r01));
                            eulerBending = Math.toDegrees(Math.atan2(-r20, cosMid));
                            eulerRotation = 0.0;
                        }

                        rotTilt.add(eulerTilt);
                        rotList.add(eulerBending);
                        rotRotation.add(eulerRotation);
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
                motionData.getData().put("pelvis_tilt", rotTilt);
                motionData.getData().put("pelvis_list", rotList);
                motionData.getData().put("pelvis_rotation", rotRotation);
            }
            if (extractRotation && segmentName.equals("abdomen")) {
                motionData.getData().put("lumbar_extension", rotTilt);
                motionData.getData().put("lumbar_bending", rotList);
                motionData.getData().put("lumbar_rotation", rotRotation);
            }
        }

        // CRITICAL FIX: Hüftflexion wurde bisher in ImportService aus einem einzelnen 3-Punkte-
        // Vektorwinkel (Becken-Zentrum -> Hüfte -> Knie) berechnet. Das vermischt alle 3 Freiheitsgrade
        // der Hüfte (Flexion, Adduktion, Rotation) in einer Zahl und führte zu massiven Formabweichungen
        // gegenüber OpenCaps modellbasierter (reiner) Flexion — empirisch bestätigt an echten Squat-/
        // Lunge-Daten. Fix: Aus der VOLLEN Segment-Pose (Rotation, nicht nur Position) von Becken und
        // Oberschenkel die relative Rotation berechnen und daraus nur die Flexionskomponente extrahieren
        // — dieselbe Idee wie bei der Becken-/Rumpfrotation, nur diesmal für ein echtes Kugelgelenk.
        // Empirisch verifiziert (Squat & LLR, echte Daten): atan2(Rrel[2][1], Rrel[2][2]) aus
        // Rrel = R_pelvis^T * R_thigh trifft Form UND Timing der OpenCap-Flexionskurve sehr genau
        // (Achsen-Diagonale R00 bleibt während reiner Flexion nahezu konstant, R11≈R22 pendeln
        // synchron — klassische Signatur einer Rotation um genau diese Achse).
        if (pelvisPoseArray != null) {
            if (rThighPoseArray != null) {
                motionData.getData().put("hip_flexion_r", computeRelativeFlexion(pelvisPoseArray, rThighPoseArray));
            }
            if (lThighPoseArray != null) {
                motionData.getData().put("hip_flexion_l", computeRelativeFlexion(pelvisPoseArray, lThighPoseArray));
            }
        }

        System.out.println("[DEBUG] JSON Parsing abgeschlossen. Kanäle extrahiert: " + motionData.getData().size());
        return motionData;
    }

    /**
     * Berechnet pro Frame den Flexionswinkel eines Extremitätensegments relativ zum Becken:
     * Rrel = R_becken^T * R_segment, Flexion = atan2(Rrel[2][1], Rrel[2][2]).
     * Frames ohne vollständige 3x3-Rotationsuntermatrix (weniger als 12 Werte) liefern NaN.
     */
    private List<Double> computeRelativeFlexion(JsonNode proximalPoseArray, JsonNode distalPoseArray) {
        int n = Math.min(proximalPoseArray.size(), distalPoseArray.size());
        List<Double> flexion = new ArrayList<>(n);

        for (int f = 0; f < n; f++) {
            JsonNode proximalPose = proximalPoseArray.get(f);
            JsonNode distalPose = distalPoseArray.get(f);

            if (proximalPose.isArray() && proximalPose.size() >= 12
                    && distalPose.isArray() && distalPose.size() >= 12) {
                double[][] rProximal = extractRotationMatrix(proximalPose);
                double[][] rDistal = extractRotationMatrix(distalPose);
                double[][] rRelative = multiplyTransposeA(rProximal, rDistal);

                flexion.add(Math.toDegrees(Math.atan2(rRelative[2][1], rRelative[2][2])));
            } else {
                flexion.add(Double.NaN);
            }
        }
        return flexion;
    }

    /**
     * Liest die 3x3-Rotationsuntermatrix aus einer flachen 4x4-Pose (Zeilen-Major:
     * [R00,R01,R02,Tx, R10,R11,R12,Ty, R20,R21,R22,Tz, 0,0,0,1]).
     */
    private double[][] extractRotationMatrix(JsonNode pose) {
        return new double[][]{
                {pose.get(0).asDouble(), pose.get(1).asDouble(), pose.get(2).asDouble()},
                {pose.get(4).asDouble(), pose.get(5).asDouble(), pose.get(6).asDouble()},
                {pose.get(8).asDouble(), pose.get(9).asDouble(), pose.get(10).asDouble()}
        };
    }

    /**
     * Berechnet A^T * B für zwei 3x3-Matrizen.
     */
    private double[][] multiplyTransposeA(double[][] a, double[][] b) {
        double[][] result = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double sum = 0;
                for (int k = 0; k < 3; k++) {
                    sum += a[k][i] * b[k][j]; // a[k][i] == a^T[i][k]
                }
                result[i][j] = sum;
            }
        }
        return result;
    }
}