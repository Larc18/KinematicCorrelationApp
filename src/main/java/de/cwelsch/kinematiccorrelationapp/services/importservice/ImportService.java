package de.cwelsch.kinematiccorrelationapp.services.importservice;

import de.cwelsch.kinematiccorrelationapp.model.FrameData;
import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Point3D;
import de.cwelsch.kinematiccorrelationapp.model.Trial;
import de.cwelsch.kinematiccorrelationapp.services.importservice.parser.OpenCapParser;
import de.cwelsch.kinematiccorrelationapp.services.importservice.parser.Parser;
import de.cwelsch.kinematiccorrelationapp.services.importservice.parser.TheiaMarkerlessParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Imports OpenCap/Theia motion capture file pairs from a folder and converts them into
 * {@link Trial} instances with per-frame joint centers and joint angles. OpenCap and Theia
 * files are matched by their base file name and expected to live in {@code opencap}/{@code theia}
 * subfolders of the scanned directory.
 */
public class ImportService {

    private final String url;
    private final List<Trial> trialList;

    /**
     * Creates the import service and immediately imports all trial pairs found under {@code url}.
     *
     * @param url the path to the folder containing the {@code opencap}/{@code theia} subfolders
     * @throws IOException if the folder or its files cannot be read
     */
    public ImportService(String url) throws IOException {
        this.url = url;
        this.trialList = new ArrayList<>();
        if (checkImportField()) {
            importAllTrials();
        }
    }

    private boolean checkImportField() {
        if (url == null || url.trim().isEmpty()) {
            System.err.println("[Error] Specified input folder path configuration is missing or empty.");
            return false;
        }
        return true;
    }

    private void importAllTrials() throws IOException {
        Path movementFolder = Path.of(url);

        if (!Files.exists(movementFolder)) {
            System.err.printf("[Error] Specified target directory location does not exist: %s%n", url);
            return;
        }

        Map<String, Path> openCapFilesMap = new TreeMap<>();
        Map<String, Path> theiaFilesMap = new TreeMap<>();

        try (Stream<Path> stream = Files.walk(movementFolder, 3)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> {
                        String lowerName = p.getFileName().toString().toLowerCase();
                        return lowerName.endsWith(".csv") || lowerName.endsWith(".mot") || lowerName.endsWith(".json");
                    })
                    .forEach(p -> {
                        Path parentDir = p.getParent();
                        if (parentDir != null) {
                            String parentDirName = parentDir.getFileName().toString().toLowerCase();
                            String fullFileName = p.getFileName().toString().toLowerCase().trim();
                            String baseNameKey = fullFileName.substring(0, fullFileName.lastIndexOf('.'));

                            if (parentDirName.equals("opencap")) {
                                openCapFilesMap.put(baseNameKey, p);
                            } else if (parentDirName.equals("theia")) {
                                theiaFilesMap.put(baseNameKey, p);
                            }
                        }
                    });
        }

        Set<String> allBaseNames = new TreeSet<>();
        allBaseNames.addAll(openCapFilesMap.keySet());
        allBaseNames.addAll(theiaFilesMap.keySet());

        if (allBaseNames.isEmpty()) {
            System.err.printf("[Warning] No compatible motion data assets found for target: %s%n",
                    movementFolder.getFileName());
            return;
        }

        int pairIndex = 0;

        for (String baseNameKey : allBaseNames) {
            Path openCapFile = openCapFilesMap.get(baseNameKey);
            Path theiaFile = theiaFilesMap.get(baseNameKey);

            if (openCapFile == null || theiaFile == null) {
                System.err.printf("[Match-Error] Missing partner file for [%s]. OpenCap: %s, Theia: %s. Skipping!%n",
                        baseNameKey.toUpperCase(),
                        openCapFile != null ? "FOUND" : "MISSING",
                        theiaFile != null ? "FOUND" : "MISSING");
                continue;
            }

            int ocTargetIndex = pairIndex * 2;
            int thTargetIndex = (pairIndex * 2) + 1;

            ensureListSize(thTargetIndex);
            importOpenCapFile(openCapFile, ocTargetIndex);
            importTheiaFile(theiaFile, thTargetIndex);

            pairIndex++;
        }
    }

    private void ensureListSize(int targetIndex) {
        while (trialList.size() <= targetIndex) {
            trialList.add(null);
        }
    }

    private void importOpenCapFile(Path file, int targetIndex) throws IOException {
        Parser.MotionData motionData = new OpenCapParser().parse(file.toString());
        List<FrameData> frames = parseFrames(motionData, true);
        if (!frames.isEmpty()) {
            trialList.set(targetIndex, new Trial(frames, file.getFileName().toString(), true));
        }
    }

    private void importTheiaFile(Path file, int targetIndex) throws IOException {
        Parser.MotionData motionData = new TheiaMarkerlessParser().parse(file.toString());
        List<FrameData> frames = parseFrames(motionData, false);
        if (!frames.isEmpty()) {
            trialList.set(targetIndex, new Trial(frames, file.getFileName().toString(), false));
        }
    }

    private List<FrameData> parseFrames(Parser.MotionData motionData, boolean isOpenCap) {
        if (motionData == null || motionData.getData() == null) {
            return Collections.emptyList();
        }

        Map<String, List<Double>> columns = motionData.getData();
        List<Double> timeColumn = null;
        String actualTimeKey = null;

        for (Map.Entry<String, List<Double>> entry : columns.entrySet()) {
            String lowerKey = entry.getKey().trim().toLowerCase();
            if (lowerKey.contains("time") || lowerKey.contains("frame") || lowerKey.contains("second")) {
                timeColumn = entry.getValue();
                actualTimeKey = entry.getKey();
                break;
            }
        }

        if (timeColumn == null && !columns.isEmpty()) {
            String firstKey = columns.keySet().iterator().next();
            timeColumn = columns.get(firstKey);
            actualTimeKey = firstKey;
        }

        if (timeColumn == null || timeColumn.isEmpty()) {
            System.err.println("[Skip] No valid time column found.");
            return Collections.emptyList();
        }

        int totalFrames = timeColumn.size();
        List<FrameData> frames = new ArrayList<>(totalFrames);

        for (int i = 0; i < totalFrames; i++) {
            Map<Joints, Point3D> jointCenters = new HashMap<>();
            Map<Joints, Double> jointAngles = new HashMap<>();

            if (isOpenCap) {
                // OPENCAP: Gelenkwinkel liegen direkt als Spalten vor
                for (Map.Entry<String, List<Double>> column : columns.entrySet()) {
                    String columnName = column.getKey();
                    if (columnName.equals(actualTimeKey)) continue;
                    Joints joint = mapColumnToJoint(columnName);
                    if (joint != null && i < column.getValue().size()) {
                        jointAngles.put(joint, column.getValue().get(i));
                    }
                }

            } else {
                // THEIA MARKERLESS
                // ── Schritt 1: Alle Spaltenwerte für Frame i flach einlesen ──────────────
                Map<String, Double> coords = new HashMap<>();
                for (Map.Entry<String, List<Double>> column : columns.entrySet()) {
                    if (i < column.getValue().size()) {
                        // Originalkey für direkte Suche behalten, lowercase für coords-Map
                        coords.put(column.getKey().toLowerCase().trim(), column.getValue().get(i));
                    }
                }

                // ── Schritt 2: Euler-Winkel direkt aus vorberechneten Parser-Spalten lesen ─
                // Becken- und LWS-Rotationen sowie Hüftflexion (rechts/links) wurden im
                // TheiaMarkerlessParser aus vollen Rotationsmatrizen extrahiert (relative Rotation
                // Becken->Oberschenkel für die Hüfte) und unter OpenCap-kompatiblen Keys gespeichert.
                // PELVIS_TX/TY/TZ werden bewusst NICHT gelesen (Einheit: Meter, nicht Grad).
                for (Joints joint : List.of(
                        Joints.PELVIS_TILT, Joints.PELVIS_LIST, Joints.PELVIS_ROTATION,
                        Joints.LUMBAR_EXTENSION, Joints.LUMBAR_BENDING, Joints.LUMBAR_ROTATION,
                        Joints.HIP_FLEXION_R, Joints.HIP_FLEXION_L)) {
                    // Direkter Key-Lookup auf der originalen columns-Map (case-sensitive, exakt wie Parser)
                    Double val = coords.get(joint.getOpenCapKey().toLowerCase().trim());
                    if (val != null && !val.isNaN()) {
                        jointAngles.put(joint, val);
                    }
                }

                // ── Schritt 3: 3D-Koordinaten auf Joints mappen ──────────────────────────
                for (Joints joint : Joints.values()) {
                    String key = joint.getTheiaKey().toLowerCase().trim();
                    Double x = coords.get(key + "_x");
                    Double y = coords.get(key + "_y");
                    Double z = coords.get(key + "_z");
                    if (x != null && y != null && z != null
                            && !x.isNaN() && !y.isNaN() && !z.isNaN()) {
                        jointCenters.put(joint, new Point3D(x, y, z));
                    }
                }

                // ── Schritt 4: Gelenkwinkel aus 3D-Koordinaten berechnen ─────────────────
                // HINWEIS: HIP_FLEXION_R/L werden NICHT mehr hier aus einem 3-Punkte-Vektorwinkel
                // berechnet (vermischte alle 3 Hüft-Freiheitsgrade) — siehe Schritt 2, wo der Wert
                // jetzt aus der sauber zerlegten relativen Becken->Oberschenkel-Rotationsmatrix
                // gelesen wird (siehe TheiaMarkerlessParser.computeRelativeFlexion).

                // --- RECHTE UNTERE EXTREMITÄT ---
                Point3D rThigh = jointCenters.get(Joints.HIP_FLEXION_R);
                Point3D rShank = jointCenters.get(Joints.KNEE_ANGLE_R);
                Point3D rFoot = jointCenters.get(Joints.ANKLE_ANGLE_R);
                Point3D rToes = jointCenters.get(Joints.MTP_ANGLE_R);

                if (rThigh != null && rShank != null && rFoot != null) {
                    jointAngles.put(Joints.KNEE_ANGLE_R,
                            Math.abs(180.0 - Point3D.calculateAngle(rThigh.subtract(rShank), rFoot.subtract(rShank))));
                }
                if (rShank != null && rFoot != null && rToes != null) {
                    // CRITICAL FIX: KNEE_ANGLE/HIP_FLEXION dürfen Math.abs() behalten, weil ihr
                    // Referenzpunkt (180°) am RAND des möglichen Wertebereichs von calculateAngle()
                    // ([0°,180°]) liegt — abs() ist dort ein No-op. Der Sprunggelenk-Referenzpunkt
                    // (90°) liegt dagegen MITTEN im Wertebereich, und echte Bewegungen (z.B. Squat)
                    // durchqueren ihn: abs() faltete Dorsalflexion und Plantarflexion auf dieselbe
                    // positive Kurve — die Kurve lief dadurch spiegelverkehrt zur signierten
                    // OpenCap-Kurve (Tal statt Berg), empirisch an echten Squat-Daten bestätigt.
                    jointAngles.put(Joints.ANKLE_ANGLE_R,
                            90.0 - Point3D.calculateAngle(rShank.subtract(rFoot), rToes.subtract(rFoot)));
                }

                // --- LINKE UNTERE EXTREMITÄT ---
                Point3D lThigh = jointCenters.get(Joints.HIP_FLEXION_L);
                Point3D lShank = jointCenters.get(Joints.KNEE_ANGLE_L);
                Point3D lFoot = jointCenters.get(Joints.ANKLE_ANGLE_L);
                Point3D lToes = jointCenters.get(Joints.MTP_ANGLE_L);

                if (lThigh != null && lShank != null && lFoot != null) {
                    jointAngles.put(Joints.KNEE_ANGLE_L,
                            Math.abs(180.0 - Point3D.calculateAngle(lThigh.subtract(lShank), lFoot.subtract(lShank))));
                }
                if (lShank != null && lFoot != null && lToes != null) {
                    jointAngles.put(Joints.ANKLE_ANGLE_L,
                            90.0 - Point3D.calculateAngle(lShank.subtract(lFoot), lToes.subtract(lFoot)));
                }

                // --- OBERE EXTREMITÄTEN ---
                Double tx = coords.get("thorax_x");
                Double ty = coords.get("thorax_y");
                Double tz = coords.get("thorax_z");
                Point3D thorax = (tx != null && ty != null && tz != null) ? new Point3D(tx, ty, tz) : null;

                Point3D rUarm = jointCenters.get(Joints.ARM_FLEX_R);
                Point3D rLarm = jointCenters.get(Joints.ELBOW_FLEX_R);
                Point3D rHand = jointCenters.get(Joints.PRO_SUP_R);

                if (thorax != null && rUarm != null && rLarm != null) {
                    jointAngles.put(Joints.ARM_FLEX_R,
                            Math.abs(180.0 - Point3D.calculateAngle(thorax.subtract(rUarm), rLarm.subtract(rUarm))));
                }
                if (rUarm != null && rLarm != null && rHand != null) {
                    jointAngles.put(Joints.ELBOW_FLEX_R,
                            Math.abs(180.0 - Point3D.calculateAngle(rUarm.subtract(rLarm), rHand.subtract(rLarm))));
                }

                Point3D lUarm = jointCenters.get(Joints.ARM_FLEX_L);
                Point3D lLarm = jointCenters.get(Joints.ELBOW_FLEX_L);
                Point3D lHand = jointCenters.get(Joints.PRO_SUP_L);

                if (thorax != null && lUarm != null && lLarm != null) {
                    jointAngles.put(Joints.ARM_FLEX_L,
                            Math.abs(180.0 - Point3D.calculateAngle(thorax.subtract(lUarm), lLarm.subtract(lUarm))));
                }
                if (lUarm != null && lLarm != null && lHand != null) {
                    jointAngles.put(Joints.ELBOW_FLEX_L,
                            Math.abs(180.0 - Point3D.calculateAngle(lUarm.subtract(lLarm), lHand.subtract(lLarm))));
                }
            }

            frames.add(new FrameData(i + 1, timeColumn.get(i), jointCenters, jointAngles));
        }

        return frames;
    }

    private Joints mapColumnToJoint(String columnName) {
        if (columnName == null) return null;
        String lower = columnName.toLowerCase().trim();

        for (Joints joint : Joints.values()) {
            if (joint.getOpenCapKey() != null && joint.getOpenCapKey().equalsIgnoreCase(lower)) {
                return joint;
            }
        }
        for (Joints joint : Joints.values()) {
            if (joint.getOpenCapKey() != null && lower.contains(joint.getOpenCapKey())) {
                return joint;
            }
        }
        return null;
    }

    public List<Trial> getTrialList() {
        return trialList;
    }
}