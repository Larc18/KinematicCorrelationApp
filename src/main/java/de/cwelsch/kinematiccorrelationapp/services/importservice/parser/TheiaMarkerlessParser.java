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

        // Pose-Arrays von Becken, Ober-/Unterschenkeln und Füßen merken, um nach der Segment-Schleife
        // Hüfte, Knie und Sprunggelenk aus der RELATIVEN Rotation benachbarter Segmente zu berechnen.
        JsonNode pelvisPoseArray = null, rThighPoseArray = null, lThighPoseArray = null;
        JsonNode rShankPoseArray = null, lShankPoseArray = null, rFootPoseArray = null, lFootPoseArray = null;

        for (JsonNode segment : segmentsArray) {
            String segmentName = segment.path("name").asText().toLowerCase().trim();
            JsonNode poseArray = segment.path("pose");

            if (poseArray.isArray() && !poseArray.isEmpty()) {
                if (segmentName.equals("pelvis")) pelvisPoseArray = poseArray;
                if (segmentName.equals("r_thigh")) rThighPoseArray = poseArray;
                if (segmentName.equals("l_thigh")) lThighPoseArray = poseArray;
                if (segmentName.equals("r_shank")) rShankPoseArray = poseArray;
                if (segmentName.equals("l_shank")) lShankPoseArray = poseArray;
                if (segmentName.equals("r_foot")) rFootPoseArray = poseArray;
                if (segmentName.equals("l_foot")) lFootPoseArray = poseArray;
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

                    // Euler-Winkel aus der 3x3-Rotationsuntermatrix via Standard-XYZ-Cardan-Zerlegung
                    // (Grood & Suntay, 1983 - siehe xyzCardanDecomposition() für die Herleitung und
                    // Belege aus echten Proband:innendaten, warum X=List, Y=Tilt, Z=Rotation gilt).
                    if (extractRotation) {
                        double[][] r = extractRotationMatrix(framePose);
                        double[] cardan = xyzCardanDecomposition(r);

                        rotList.add(cardan[0]);      // X: Frontal (List/Bending)
                        rotTilt.add(cardan[1]);       // Y: Sagittal (Tilt)
                        rotRotation.add(cardan[2]);   // Z: Transversal (Rotation)
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

        // Hüftflexion/-adduktion/-rotation aus der VOLLEN Segment-Pose (Rotation, nicht nur Position)
        // von Becken und Oberschenkel: Rrel = R_becken^T * R_oberschenkel, danach XYZ-Cardan-Zerlegung
        // (Grood & Suntay, 1983 - Joint Coordinate System; siehe xyzCardanDecomposition()). Die
        // X-Komponente ist die Flexion, Y die Adduktion, Z die Innen-/Außenrotation (Herleitung und
        // Belege siehe dort). Anders als eine einzelne 3-Punkte-Vektorwinkelberechnung (Becken-Zentrum
        // -> Hüfte -> Knie, die alle 3 Freiheitsgrade der Hüfte in einer Zahl vermischen würde) trennt
        // dieser Ansatz die drei Hüft-Freiheitsgrade sauber aus der relativen Rotation.
        if (pelvisPoseArray != null) {
            if (rThighPoseArray != null) {
                RelativeJointAngles rHip = computeRelativeJointAngles(pelvisPoseArray, rThighPoseArray);
                motionData.getData().put("hip_flexion_r", rHip.flexion());
                motionData.getData().put("hip_adduction_r", rHip.adduction());
                motionData.getData().put("hip_rotation_r", rHip.rotation());
            }
            if (lThighPoseArray != null) {
                RelativeJointAngles lHip = computeRelativeJointAngles(pelvisPoseArray, lThighPoseArray);
                motionData.getData().put("hip_flexion_l", lHip.flexion());
                motionData.getData().put("hip_adduction_l", lHip.adduction());
                motionData.getData().put("hip_rotation_l", lHip.rotation());
            }
        }

        // Knie (Oberschenkel -> Unterschenkel) und oberes Sprunggelenk (Unterschenkel -> Fuß) nach
        // demselben Prinzip wie die Hüfte. Maßgeblich ist jeweils nur die X-Komponente (Herleitung
        // siehe xyzCardanDecomposition()). Das Vorzeichen des Knies wird umgekehrt, weil die Beugung
        // eine negative Rotation um die lokale X-Achse ist, OpenCap die Knieflexion aber positiv
        // angibt. Beim Sprunggelenk ist die Dorsalextension bereits positiv.
        putKneeAngle(motionData, "knee_angle_r", rThighPoseArray, rShankPoseArray);
        putKneeAngle(motionData, "knee_angle_l", lThighPoseArray, lShankPoseArray);
        putAnkleAngle(motionData, "ankle_angle_r", rShankPoseArray, rFootPoseArray);
        putAnkleAngle(motionData, "ankle_angle_l", lShankPoseArray, lFootPoseArray);

        System.out.println("[DEBUG] JSON Parsing abgeschlossen. Kanäle extrahiert: " + motionData.getData().size());
        return motionData;
    }

    private void putKneeAngle(TheiaMotionData motionData, String key, JsonNode thighPoseArray, JsonNode shankPoseArray) {
        if (thighPoseArray != null && shankPoseArray != null) {
            List<Double> flexion = new ArrayList<>(computeRelativeJointAngles(thighPoseArray, shankPoseArray).flexion());
            flexion.replaceAll(angle -> -angle);
            motionData.getData().put(key, flexion);
        }
    }

    private void putAnkleAngle(TheiaMotionData motionData, String key, JsonNode shankPoseArray, JsonNode footPoseArray) {
        if (shankPoseArray != null && footPoseArray != null) {
            motionData.getData().put(key, computeRelativeJointAngles(shankPoseArray, footPoseArray).flexion());
        }
    }

    /**
     * Ergebnis der Zerlegung einer relativen Segment-Rotation (Rrel = R_proximal^T * R_distal) in die
     * drei Komponenten nach dem Joint-Coordinate-System-Prinzip. Die Bezeichnungen folgen der Hüfte;
     * bei Knie und Sprunggelenk wird nur {@code flexion} (X-Komponente) verwendet.
     */
    record RelativeJointAngles(List<Double> flexion, List<Double> adduction, List<Double> rotation) {
    }

    /**
     * Berechnet pro Frame die relative Rotation eines distalen Segments zu seinem proximalen
     * Bezugssegment (Rrel = R_proximal^T * R_distal) und zerlegt sie per Standard-XYZ-Cardan-Sequenz
     * (Grood &amp; Suntay, 1983) in Flexion (X), Adduktion (Y) und Innen-/Außenrotation (Z).
     * Frames ohne vollständige 3x3-Rotationsuntermatrix (weniger als 12 Werte) liefern NaN in allen drei Komponenten.
     */
    RelativeJointAngles computeRelativeJointAngles(JsonNode proximalPoseArray, JsonNode distalPoseArray) {
        int n = Math.min(proximalPoseArray.size(), distalPoseArray.size());
        List<Double> flexion = new ArrayList<>(n);
        List<Double> adduction = new ArrayList<>(n);
        List<Double> rotation = new ArrayList<>(n);

        for (int f = 0; f < n; f++) {
            JsonNode proximalPose = proximalPoseArray.get(f);
            JsonNode distalPose = distalPoseArray.get(f);

            if (proximalPose.isArray() && proximalPose.size() >= 12
                    && distalPose.isArray() && distalPose.size() >= 12) {
                double[][] rProximal = extractRotationMatrix(proximalPose);
                double[][] rDistal = extractRotationMatrix(distalPose);
                double[][] rRelative = multiplyTransposeA(rProximal, rDistal);
                double[] cardan = xyzCardanDecomposition(rRelative);

                flexion.add(cardan[0]);
                adduction.add(cardan[1]);
                rotation.add(cardan[2]);
            } else {
                flexion.add(Double.NaN);
                adduction.add(Double.NaN);
                rotation.add(Double.NaN);
            }
        }
        return new RelativeJointAngles(flexion, adduction, rotation);
    }

    /**
     * Standard-XYZ-Cardan-("Tait-Bryan")-Zerlegung einer Rotationsmatrix R = Rx(a)&middot;Ry(b)&middot;Rz(g).
     * Diese Sequenz ist - bis auf eine bekannte Vorzeichen-/Offset-Konvention bei der Ab-/Adduktion -
     * äquivalent zum Joint Coordinate System nach Grood &amp; Suntay (1983), der Standardkonvention der
     * klinischen Bewegungsanalyse. Sie ist außerdem die Konvention, die Visual3D auf Theia3D-Posen
     * anwendet (die Standardpipeline in der publizierten Theia3D-Validierungsliteratur), siehe
     * HAS-Motion/Qualisys-Dokumentation zu Visual3D.
     * <p>
     * Achsenzuordnung (X=List/Bending, Y=Tilt, Z=Rotation für Becken/LWS; X=Flexion, Y=Adduktion,
     * Z=Rotation für die aus Rrel=R_becken^T*R_oberschenkel gebildete Hüfte) wurde NICHT gegen OpenCap
     * kalibriert, sondern unabhängig aus echten Proband:innen-Trials der eigenen Datenerhebung
     * hergeleitet, indem jeweils die Bewegungsaufgabe selbst die Ziel-Achse isoliert:
     * <ul>
     *   <li>Squat-Trials (isolieren sagittale Beckenkippung und Hüftflexion): Y-Komponente des Becken-
     *   Cardan-Winkels zeigt die dominante Bewegungsamplitude (~26° ROM, 10.6°&rarr;36.3°, physiologisch
     *   plausibel für anteriore Beckenkippung bei zunehmender Kniebeugung), X- und Z-Komponente bleiben
     *   nahezu konstant (ROM &lt;4°). Die X-Komponente der Rrel-Zerlegung (Becken/Oberschenkel) zeigt
     *   analog die dominante Amplitude (~97° ROM, 10.8&deg;&rarr;107.8&deg;, physiologisch plausibel für
     *   Hüftflexion bei einer Kniebeuge), Y- und Z-Komponente bleiben klein (ROM &lt;5°).</li>
     *   <li>Becken-Rumpf-Rotations-Trials (isolieren die transversale Rumpfrotation): Z-Komponente von
     *   Becken UND LWS zeigt die dominante Amplitude (~135&deg; ROM), X- und Y-Komponente bleiben
     *   vergleichsweise klein (ROM &lt;27°).</li>
     *   <li>Knie und oberes Sprunggelenk (alle 42 Squat-Trials der 14 Proband:innen, Mittelwerte
     *   rechts/links): Beim Knie (Rrel = R_oberschenkel^T*R_unterschenkel) dominiert in allen Trials die
     *   X-Komponente (ROM 107.7&deg;/108.2&deg;; Y 6.7&deg;/7.0&deg;, Z 16.1&deg;/16.3&deg;). Sie verläuft bei
     *   zunehmender Beugung negativ (mittlere Auslenkung -105&deg;), OpenCap dagegen positiv, daher wird
     *   das Vorzeichen umgekehrt. Beim Sprunggelenk (Rrel = R_unterschenkel^T*R_fuß) dominiert die
     *   X-Komponente in 40 von 42 Trials je Seite (ROM 26.0&deg;/26.1&deg;; Y 7.0&deg;/6.8&deg;, Z 11.6&deg;/10.9&deg;).
     *   Die Ausnahmen sind die Trials SQT_0002 und SQT_0003 von Proband 5, in denen Z dominiert.
     *   Die Dorsalextension im Squat ist bereits positiv wie bei OpenCap, daher keine Vorzeichenumkehr.
     *   Ein empirischer Neutralwert ist nicht nötig: Die Theia-Segmentachsen von Unterschenkel und Fuß
     *   sind im Stand nahezu parallel (X-Komponente in den ersten 10 Frames im Mittel 2.2&deg;/2.7&deg;).</li>
     * </ul>
     * Diese Kreuzvalidierung über zwei unabhängige Bewegungsaufgaben liefert dieselbe qualitative
     * Achsenzuordnung wie die ursprüngliche (gegen OpenCap-Kurvenform kalibrierte) Fassung dieser
     * Klasse, jetzt aber unabhängig von OpenCap aus dem Bewegungsdesign selbst hergeleitet.
     *
     * @param r die 3x3-Rotationsuntermatrix
     * @return {Winkel um lokale X-Achse, Winkel um lokale Y-Achse, Winkel um lokale Z-Achse} in Grad
     */
    static double[] xyzCardanDecomposition(double[][] r) {
        double r02 = Math.max(-1.0, Math.min(1.0, r[0][2]));
        double b = Math.asin(r02);
        double a, g;
        if (Math.abs(Math.cos(b)) > 1e-6) {
            a = Math.atan2(-r[1][2], r[2][2]);
            g = Math.atan2(-r[0][1], r[0][0]);
        } else {
            // Gimbal Lock (b = ±90°): a und g werden linear abhängig; Konvention fixiert g=0,
            // analog zur allgemein üblichen Behandlung dieses Sonderfalls bei Cardan-/Euler-Winkeln.
            a = Math.atan2(r[2][1], r[1][1]);
            g = 0.0;
        }
        return new double[]{Math.toDegrees(a), Math.toDegrees(b), Math.toDegrees(g)};
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