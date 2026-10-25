package de.cwelsch.kinematiccorrelationapp.services.importservice;

import de.cwelsch.kinematiccorrelationapp.model.FrameData;
import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Trial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ImportService Folder-Scanning and OpenCap/Theia Pairing Test Suite")
class ImportServiceTest {

    @TempDir
    Path tempDir;

    private void writeFile(String subfolder, String fileName, String content) throws IOException {
        Path dir = tempDir.resolve(subfolder);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName), content);
    }

    private String minimalOpenCapContent() {
        return """
                Coordinates
                endheader
                time knee_angle_r hip_flexion_r
                0.00 10.0 5.0
                0.01 12.0 6.0
                """;
    }

    private String minimalTheiaContent() {
        return """
                {
                  "trial": { "frame_rate": 85.0 },
                  "data": {
                    "subjects": [
                      {
                        "segments": [
                          {
                            "name": "pelvis",
                            "pose": [
                              [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1],
                              [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]
                            ]
                          }
                        ]
                      }
                    ]
                  }
                }
                """;
    }

    @Test
    @DisplayName("Should end up with an empty trial list (no exception) for a null URL")
    void testConstructor_NullUrl_ResultsInEmptyTrialListWithoutThrowing() throws IOException {
        ImportService service = new ImportService(null);

        assertTrue(service.getTrialList().isEmpty());
    }

    @Test
    @DisplayName("Should end up with an empty trial list (no exception) for a blank URL")
    void testConstructor_BlankUrl_ResultsInEmptyTrialListWithoutThrowing() throws IOException {
        ImportService service = new ImportService("   ");

        assertTrue(service.getTrialList().isEmpty());
    }

    @Test
    @DisplayName("Should end up with an empty trial list (no exception) when the folder does not exist")
    void testConstructor_NonExistentFolder_ResultsInEmptyTrialListWithoutThrowing() throws IOException {
        String missingPath = tempDir.resolve("does_not_exist").toString();

        ImportService service = new ImportService(missingPath);

        assertTrue(service.getTrialList().isEmpty());
    }

    @Test
    @DisplayName("Should end up with an empty trial list when the folder has no 'opencap'/'theia' subfolders")
    void testConstructor_EmptyFolderWithoutOpenCapOrTheiaSubfolders_ResultsInEmptyTrialList() throws IOException {
        ImportService service = new ImportService(tempDir.toString());

        assertTrue(service.getTrialList().isEmpty());
    }

    @Test
    @DisplayName("Should produce two trials (OpenCap + Theia) with correct isOpenCap flags for a matched file pair")
    void testImport_MatchedOpenCapAndTheiaPair_ProducesTwoTrialsWithCorrectFlags() throws IOException {
        writeFile("opencap", "sqt_0001.mot", minimalOpenCapContent());
        writeFile("theia", "sqt_0001.json", minimalTheiaContent());

        ImportService service = new ImportService(tempDir.toString());
        List<Trial> trials = service.getTrialList();

        assertEquals(2, trials.size());
        Trial ocTrial = trials.get(0);
        Trial thTrial = trials.get(1);

        assertNotNull(ocTrial);
        assertNotNull(thTrial);
        assertTrue(ocTrial.isOpenCap());
        assertFalse(thTrial.isOpenCap());
        assertEquals("sqt_0001.mot", ocTrial.getSourceFileName());
        assertEquals("sqt_0001.json", thTrial.getSourceFileName());
        assertFalse(ocTrial.getFrameDataList().isEmpty());
        assertFalse(thTrial.getFrameDataList().isEmpty());
    }

    @Test
    @DisplayName("Should skip an OpenCap file that has no matching Theia partner file")
    void testImport_UnmatchedOpenCapFileWithoutTheiaPartner_IsSkipped() throws IOException {
        writeFile("opencap", "sqt_0001.mot", minimalOpenCapContent());
        // Kein passendes theia/sqt_0001.json vorhanden

        ImportService service = new ImportService(tempDir.toString());

        assertTrue(service.getTrialList().isEmpty(), "Ohne Partnerdatei darf kein Trial-Paar entstehen");
    }

    @Test
    @DisplayName("Should match base file names case-insensitively, independent of the file extension")
    void testImport_FileNameMatchingIsCaseInsensitiveAcrossExtensions() throws IOException {
        writeFile("opencap", "SQT_0001.mot", minimalOpenCapContent());
        writeFile("theia", "sqt_0001.json", minimalTheiaContent());

        ImportService service = new ImportService(tempDir.toString());

        assertEquals(2, service.getTrialList().size(), "Basisname-Matching muss Groß-/Kleinschreibung ignorieren");
    }

    @Test
    @DisplayName("Should detect a file with an uppercase extension (e.g. .MOT) just like a lowercase one")
    void testImport_UppercaseFileExtension_IsDetected() throws IOException {
        writeFile("opencap", "sqt_0001.MOT", minimalOpenCapContent());
        writeFile("theia", "sqt_0001.json", minimalTheiaContent());

        ImportService service = new ImportService(tempDir.toString());

        assertEquals(2, service.getTrialList().size(), "Großgeschriebene Dateiendungen müssen genauso erkannt werden wie kleingeschriebene");
    }

    @Test
    @DisplayName("Should ignore files with an unsupported extension")
    void testImport_IgnoresFilesWithUnsupportedExtension() throws IOException {
        writeFile("opencap", "sqt_0001.mot", minimalOpenCapContent());
        writeFile("theia", "sqt_0001.json", minimalTheiaContent());
        writeFile("opencap", "readme.txt", "not a motion file");

        ImportService service = new ImportService(tempDir.toString());

        assertEquals(2, service.getTrialList().size());
    }

    @Test
    @DisplayName("Should ignore hidden files (names starting with '.')")
    void testImport_IgnoresHiddenFiles() throws IOException {
        writeFile("opencap", ".hidden.mot", minimalOpenCapContent());

        ImportService service = new ImportService(tempDir.toString());

        assertTrue(service.getTrialList().isEmpty(), "Versteckte Dateien (Präfix '.') dürfen nicht importiert werden");
    }

    @Test
    @DisplayName("Should ignore files that are not inside an 'opencap' or 'theia' subfolder")
    void testImport_IgnoresFilesOutsideOpenCapOrTheiaSubfolders() throws IOException {
        Files.writeString(tempDir.resolve("sqt_0001.mot"), minimalOpenCapContent());

        ImportService service = new ImportService(tempDir.toString());

        assertTrue(service.getTrialList().isEmpty());
    }

    @Test
    @DisplayName("Should map OpenCap columns to joints per frame and exclude the time column from the joint angles")
    void testParseFrames_OpenCapFile_MapsColumnsToJointsAndExcludesTimeColumn() throws IOException {
        writeFile("opencap", "sqt_0001.mot", minimalOpenCapContent());
        writeFile("theia", "sqt_0001.json", minimalTheiaContent());

        ImportService service = new ImportService(tempDir.toString());
        List<FrameData> frames = service.getTrialList().get(0).getFrameDataList();

        assertEquals(2, frames.size());

        FrameData first = frames.get(0);
        assertEquals(1, first.frame());
        assertEquals(0.00, first.second(), 1e-9);
        assertEquals(10.0, first.jointAngle().get(Joints.KNEE_ANGLE_R), 1e-9);
        assertEquals(5.0, first.jointAngle().get(Joints.HIP_FLEXION_R), 1e-9);
        assertFalse(first.jointAngle().containsKey(Joints.KNEE_ANGLE_L), "Nicht in der Datei vorhandene Gelenke dürfen nicht auftauchen");

        FrameData second = frames.get(1);
        assertEquals(2, second.frame());
        assertEquals(0.01, second.second(), 1e-9);
        assertEquals(12.0, second.jointAngle().get(Joints.KNEE_ANGLE_R), 1e-9);
    }

    @Test
    @DisplayName("Should derive KNEE_ANGLE_R and ANKLE_ANGLE_R from raw 3D joint centers for a Theia trial")
    void testParseFrames_TheiaFile_DerivesKneeAndAnkleAngleFromJointCenters3D() throws IOException {
        writeFile("opencap", "sqt_0001.mot", minimalOpenCapContent());
        // Oberschenkel(0,1,0) - Knie(0,0,0) - Fuß(1,0,0) - Zehen(1,1,0): rein geometrisch konstruiert,
        // sodass sich Knie- und Sprunggelenkwinkel von Hand exakt nachrechnen lassen (siehe unten).
        writeFile("theia", "sqt_0001.json", """
                {
                  "trial": { "frame_rate": 85.0 },
                  "data": {
                    "subjects": [
                      {
                        "segments": [
                          { "name": "r_thigh", "pose": [ [0,0,0,0, 0,0,0,1, 0,0,0,0, 0,0,0,1] ] },
                          { "name": "r_shank", "pose": [ [0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,1] ] },
                          { "name": "r_foot",  "pose": [ [0,0,0,1, 0,0,0,0, 0,0,0,0, 0,0,0,1] ] },
                          { "name": "r_toes",  "pose": [ [0,0,0,1, 0,0,0,1, 0,0,0,0, 0,0,0,1] ] }
                        ]
                      }
                    ]
                  }
                }
                """);

        ImportService service = new ImportService(tempDir.toString());
        Trial theiaTrial = service.getTrialList().get(1);
        assertNotNull(theiaTrial);
        assertFalse(theiaTrial.isOpenCap());

        FrameData frame = theiaTrial.getFrameDataList().get(0);

        // Oberschenkel-Knie-Fuß-Vektoren stehen im rechten Winkel zueinander -> |180° - 90°| = 90° Beugung
        assertEquals(90.0, frame.jointAngle().get(Joints.KNEE_ANGLE_R), 1e-6);
        // Knie-Fuß-Zehen-Vektoren stehen im rechten Winkel -> roher Winkel = 90°. Das ist NICHT die
        // neutrale Sprunggelenkstellung (siehe ANKLE_NEUTRAL_REFERENCE_DEGREES in ImportService:
        // empirisch liegt Theias Rohwinkel im echten entspannten Stand bei ~111.6°, nicht 90°) ->
        // erwarteter Ausgabewert ist der volle Korrekturterm ANKLE_NEUTRAL_REFERENCE_DEGREES - 90.0.
        assertEquals(21.59, frame.jointAngle().get(Joints.ANKLE_ANGLE_R), 1e-6);
    }
}
