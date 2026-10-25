package de.cwelsch.kinematiccorrelationapp.services.importservice.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TheiaMarkerlessParser JSON Pose-Matrix Parsing Test Suite")
class TheiaMarkerlessParserTest {

    @TempDir
    Path tempDir;

    private TheiaMarkerlessParser parser;

    @BeforeEach
    void setUp() {
        parser = new TheiaMarkerlessParser();
    }

    private String writeJsonFile(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, content);
        return file.toString();
    }

    @Test
    @DisplayName("Should extract the frame rate and the translation channels (x/y/z) for a non-rotation-tracked segment")
    void testParse_ExtractsFrameRateAndTranslationCoordinates() throws IOException {
        // Pose-Layout (row-major 4x4): [R00,R01,R02,Tx, R10,R11,R12,Ty, R20,R21,R22,Tz, 0,0,0,1]
        // Rotation ist für r_thigh irrelevant (kein Becken/Rumpf) - daher hier bewusst auf 0 gesetzt.
        String content = """
                {
                  "trial": { "frame_rate": 100.0 },
                  "data": {
                    "subjects": [
                      {
                        "segments": [
                          {
                            "name": "r_thigh",
                            "pose": [
                              [0,0,0,1, 0,0,0,2, 0,0,0,3, 0,0,0,1],
                              [0,0,0,4, 0,0,0,5, 0,0,0,6, 0,0,0,1]
                            ]
                          }
                        ]
                      }
                    ]
                  }
                }
                """;
        String path = writeJsonFile("translation.json", content);

        Parser.MotionData data = parser.parse(path);

        assertEquals(List.of(0.0, 1.0 / 100.0), data.getData().get("time"));
        assertEquals(List.of(1.0, 4.0), data.getData().get("r_thigh_x"));
        assertEquals(List.of(2.0, 5.0), data.getData().get("r_thigh_y"));
        assertEquals(List.of(3.0, 6.0), data.getData().get("r_thigh_z"));
        assertFalse(data.getData().containsKey("pelvis_tilt"), "r_thigh ist kein Rotations-Tracking-Segment");
        assertFalse(data.getData().containsKey("hip_flexion_r"), "ohne Becken-Segment darf keine Hüftflexion berechnet werden");
    }

    @Test
    @DisplayName("Should compute the pelvis Euler rotation angles from a known 30-degree rotation matrix (regular case)")
    void testParse_ComputesPelvisEulerRotation_RegularCase() throws IOException {
        // Reine Rotation um 30° (R00=cos30, R10=sin30, restliche Achse unverändert):
        // erwartete Werte: pelvis_rotation=30°, pelvis_tilt=0°, pelvis_list=0°
        String content = """
                {
                  "trial": { "frame_rate": 85.0 },
                  "data": {
                    "subjects": [
                      {
                        "segments": [
                          {
                            "name": "pelvis",
                            "pose": [
                              [0.86602540,-0.5,0,0, 0.5,0.86602540,0,0, 0,0,1,0, 0,0,0,1]
                            ]
                          }
                        ]
                      }
                    ]
                  }
                }
                """;
        String path = writeJsonFile("pelvis_regular.json", content);

        Parser.MotionData data = parser.parse(path);

        assertEquals(30.0, data.getData().get("pelvis_rotation").get(0), 1e-3);
        assertEquals(0.0, data.getData().get("pelvis_tilt").get(0), 1e-3);
        assertEquals(0.0, data.getData().get("pelvis_list").get(0), 1e-3);
    }

    @Test
    @DisplayName("Should switch to the gimbal-lock formula when the Y-axis Cardan angle is near ±90°")
    void testParse_ComputesPelvisEulerRotation_GimbalLockCase() throws IOException {
        // R02=1 -> b=asin(1)=90° -> cos(b)≈0 -> singulärer Fall (Y=Tilt bei genau ±90°, physiologisch
        // nie erreicht, aber numerisch abzusichern). Im singulären Zweig: a=atan2(R21,R11)=atan2(0,1)=0°
        // (List), g bleibt konventionsgemäß auf 0.0 fixiert (Rotation) — siehe xyzCardanDecomposition().
        String content = """
                {
                  "trial": { "frame_rate": 85.0 },
                  "data": {
                    "subjects": [
                      {
                        "segments": [
                          {
                            "name": "pelvis",
                            "pose": [
                              [0,0,1,0, 0,1,0,0, 1,0,1,0, 0,0,0,1]
                            ]
                          }
                        ]
                      }
                    ]
                  }
                }
                """;
        String path = writeJsonFile("pelvis_gimbal_lock.json", content);

        Parser.MotionData data = parser.parse(path);

        assertEquals(90.0, data.getData().get("pelvis_tilt").get(0), 1e-3);
        assertEquals(0.0, data.getData().get("pelvis_list").get(0), 1e-3);
        assertEquals(0.0, data.getData().get("pelvis_rotation").get(0), 1e-9);
    }

    @Test
    @DisplayName("Should decompose a general (non-axis-aligned) rotation into all three XYZ Cardan components")
    void testXyzCardanDecomposition_GeneralCase_MatchesHandComputedAngles() {
        // R = Rx(20°) * Ry(-15°) * Rz(35°) — von Hand über die Standardformel R=Rx(a)Ry(b)Rz(g)
        // konstruiert, um alle drei Komponenten gleichzeitig (nicht nur eine isolierte Achse) zu prüfen.
        double a = Math.toRadians(20), b = Math.toRadians(-15), g = Math.toRadians(35);
        double ca = Math.cos(a), sa = Math.sin(a);
        double cb = Math.cos(b), sb = Math.sin(b);
        double cg = Math.cos(g), sg = Math.sin(g);

        double[][] r = {
                {cb * cg, -cb * sg, sb},
                {sa * sb * cg + ca * sg, -sa * sb * sg + ca * cg, -sa * cb},
                {-ca * sb * cg + sa * sg, ca * sb * sg + sa * cg, ca * cb}
        };

        double[] result = TheiaMarkerlessParser.xyzCardanDecomposition(r);

        assertEquals(20.0, result[0], 1e-6);
        assertEquals(-15.0, result[1], 1e-6);
        assertEquals(35.0, result[2], 1e-6);
    }

    @Test
    @DisplayName("Should compute the relative hip flexion from pelvis and thigh poses via Rrel = R_pelvis^T * R_thigh")
    void testParse_ComputesRelativeHipFlexion_FromPelvisAndThighPoses() throws IOException {
        // Becken = Identität (keine Rotation). Oberschenkel = reine 40°-Rotation um die X-Achse.
        // Da das Becken die Identität ist, ist Rrel = R_thigh direkt -> Flexion = atan2(R21,R22) = 40°.
        String content = """
                {
                  "trial": { "frame_rate": 85.0 },
                  "data": {
                    "subjects": [
                      {
                        "segments": [
                          {
                            "name": "pelvis",
                            "pose": [
                              [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]
                            ]
                          },
                          {
                            "name": "r_thigh",
                            "pose": [
                              [1,0,0,0, 0,0.76604444,-0.64278761,0, 0,0.64278761,0.76604444,0, 0,0,0,1]
                            ]
                          }
                        ]
                      }
                    ]
                  }
                }
                """;
        String path = writeJsonFile("hip_flexion.json", content);

        Parser.MotionData data = parser.parse(path);

        assertEquals(40.0, data.getData().get("hip_flexion_r").get(0), 1e-3);
        assertEquals(0.0, data.getData().get("hip_adduction_r").get(0), 1e-3, "reine X-Rotation darf nicht in die Adduktionskomponente durchschlagen");
        assertEquals(0.0, data.getData().get("hip_rotation_r").get(0), 1e-3, "reine X-Rotation darf nicht in die Rotationskomponente durchschlagen");
        assertFalse(data.getData().containsKey("hip_flexion_l"), "ohne l_thigh-Segment darf keine linke Hüftflexion entstehen");
    }

    @Test
    @DisplayName("Should separate flexion, adduction and rotation from a composite (non-identity-pelvis) hip rotation")
    void testParse_ComputesAllThreeHipComponents_FromCompositeRelativeRotation() throws IOException {
        // Becken UND Oberschenkel sind beide nicht-triviale Rotationen (anders als im einfacheren
        // Flexions-Test oben, wo das Becken die Identität ist). Konstruiert per
        // Rrel_target = Rx(35°)*Ry(8°)*Rz(-6°), Becken P = Rx(6°)*Ry(-4°)*Rz(12°), Oberschenkel T = P*Rrel_target,
        // sodass Rrel = P^T*T = Rrel_target exakt gilt. Prüft, dass Flexion/Adduktion/Rotation über die
        // volle Rrel=R_becken^T*R_oberschenkel-Zerlegung sauber getrennt werden, nicht nur bei Rrel=R_thigh.
        String content = """
                {
                  "trial": { "frame_rate": 85.0 },
                  "data": {
                    "subjects": [
                      {
                        "segments": [
                          {
                            "name": "pelvis",
                            "pose": [
                              [0.97576488,-0.20740523,-0.06975647,0, 0.19964053,0.97430520,-0.10427384,0, 0.08959103,0.08782053,0.99209929,0, 0,0,0,1]
                            ]
                          },
                          {
                            "name": "r_thigh",
                            "pose": [
                              [0.97435998,-0.10865395,0.19702017,0, 0.20861351,0.76428798,-0.61020020,0, -0.08427949,0.63565572,0.76735830,0, 0,0,0,1]
                            ]
                          }
                        ]
                      }
                    ]
                  }
                }
                """;
        String path = writeJsonFile("hip_composite.json", content);

        Parser.MotionData data = parser.parse(path);

        assertEquals(35.0, data.getData().get("hip_flexion_r").get(0), 1e-3);
        assertEquals(8.0, data.getData().get("hip_adduction_r").get(0), 1e-3);
        assertEquals(-6.0, data.getData().get("hip_rotation_r").get(0), 1e-3);
    }

    @Test
    @DisplayName("Should return only metadata (no columns) when the subjects array is missing or empty")
    void testParse_MissingSubjectsArray_ReturnsMetadataOnly() throws IOException {
        String content = """
                { "trial": { "frame_rate": 90.0 } }
                """;
        String path = writeJsonFile("no_subjects.json", content);

        Parser.MotionData data = parser.parse(path);

        assertEquals(90.0, (double) data.getMetaData().get("frame_rate"), 1e-9);
        assertTrue(data.getData().isEmpty());
    }

    @Test
    @DisplayName("Should default the frame rate to 85.0 when the trial.frame_rate field is missing")
    void testParse_DefaultsFrameRateTo85_WhenMissing() throws IOException {
        String content = """
                {
                  "data": {
                    "subjects": [
                      {
                        "segments": [
                          {
                            "name": "r_thigh",
                            "pose": [
                              [0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,1],
                              [0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,1]
                            ]
                          }
                        ]
                      }
                    ]
                  }
                }
                """;
        String path = writeJsonFile("default_rate.json", content);

        Parser.MotionData data = parser.parse(path);

        assertEquals(85.0, (double) data.getMetaData().get("frame_rate"), 1e-9);
        assertEquals(List.of(0.0, 1.0 / 85.0), data.getData().get("time"));
    }

    @Test
    @DisplayName("Should fill NaN for translation and rotation channels when a frame pose has fewer than 12 elements")
    void testParse_MalformedFramePose_ProducesNaNForTranslationAndRotation() throws IOException {
        String content = """
                {
                  "trial": { "frame_rate": 85.0 },
                  "data": {
                    "subjects": [
                      {
                        "segments": [
                          {
                            "name": "pelvis",
                            "pose": [
                              [1,0,0,1, 0,1,0,2, 0,0,1,3, 0,0,0,1],
                              [0.0]
                            ]
                          }
                        ]
                      }
                    ]
                  }
                }
                """;
        String path = writeJsonFile("malformed_pose.json", content);

        Parser.MotionData data = parser.parse(path);

        assertEquals(1.0, data.getData().get("pelvis_x").get(0), 1e-9);
        assertTrue(Double.isNaN(data.getData().get("pelvis_x").get(1)));
        assertTrue(Double.isNaN(data.getData().get("pelvis_y").get(1)));
        assertTrue(Double.isNaN(data.getData().get("pelvis_z").get(1)));
        assertTrue(Double.isNaN(data.getData().get("pelvis_tilt").get(1)));
        assertTrue(Double.isNaN(data.getData().get("pelvis_list").get(1)));
        assertTrue(Double.isNaN(data.getData().get("pelvis_rotation").get(1)));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for a null or blank file path")
    void testParse_NullOrEmptyFilePath_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(null));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("   "));
    }

    @Test
    @DisplayName("Should throw IOException for a file that does not exist")
    void testParse_NonExistentFile_ThrowsIOException() {
        String missingPath = tempDir.resolve("does_not_exist.json").toString();

        assertThrows(IOException.class, () -> parser.parse(missingPath));
    }

    @Test
    @DisplayName("Should throw IOException when the given path is a directory rather than a file")
    void testParse_PathIsDirectory_ThrowsIOException() throws IOException {
        Path directory = Files.createDirectory(tempDir.resolve("not_a_file.json"));

        assertThrows(IOException.class, () -> parser.parse(directory.toString()));
    }

    @Test
    @DisplayName("Should throw IOException for a file with invalid (non-parseable) JSON content")
    void testParse_InvalidJsonContent_ThrowsIOException() throws IOException {
        String path = writeJsonFile("invalid.json", "{ this is not valid json ][");

        assertThrows(IOException.class, () -> parser.parse(path));
    }

    @Test
    @DisplayName("Should treat a completely empty file like a missing 'subjects' array and return metadata only, without throwing")
    void testParse_EmptyFile_ReturnsMetadataOnlyWithoutThrowing() throws IOException {
        // Jackson's readTree() liefert für leeren Input einen MissingNode statt einer Exception zu werfen
        // (anders als readValue()) - rootNode.path(...) verhält sich dann wie ein fehlendes "subjects"-Array.
        String path = writeJsonFile("empty.json", "");

        Parser.MotionData data = assertDoesNotThrow(() -> parser.parse(path));

        assertEquals(85.0, (double) data.getMetaData().get("frame_rate"), 1e-9);
        assertTrue(data.getData().isEmpty());
    }

    @Test
    @DisplayName("Should return only metadata when a subject is present but has no 'segments' field")
    void testParse_SubjectPresentButNoSegments_ReturnsMetadataOnly() throws IOException {
        String content = """
                {
                  "trial": { "frame_rate": 85.0 },
                  "data": {
                    "subjects": [
                      { "name": "subject1" }
                    ]
                  }
                }
                """;
        String path = writeJsonFile("no_segments.json", content);

        Parser.MotionData data = parser.parse(path);

        assertEquals(85.0, (double) data.getMetaData().get("frame_rate"), 1e-9);
        assertTrue(data.getData().isEmpty(), "Ohne 'segments' darf nicht einmal die 'time'-Spalte entstehen");
    }

    @Test
    @DisplayName("Should log a warning but not throw when a later segment's frame count does not match the time axis")
    void testParse_FrameCountMismatchAcrossSegments_LogsWarningWithoutThrowing() throws IOException {
        // Erstes Segment (pelvis) definiert die Zeitachse mit 2 Frames.
        // Zweites Segment (r_thigh) hat abweichend nur 1 Frame -> Warnung statt Absturz/Ausrichtungsfehler.
        String content = """
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
                          },
                          {
                            "name": "r_thigh",
                            "pose": [
                              [0,0,0,7, 0,0,0,8, 0,0,0,9, 0,0,0,1]
                            ]
                          }
                        ]
                      }
                    ]
                  }
                }
                """;
        String path = writeJsonFile("frame_mismatch.json", content);

        Parser.MotionData data = assertDoesNotThrow(() -> parser.parse(path));

        assertEquals(2, data.getData().get("time").size());
        // Das abweichende Segment behält trotzdem seine eigene (kürzere) Länge statt abgeschnitten zu werden
        assertEquals(1, data.getData().get("r_thigh_x").size());
        assertEquals(7.0, data.getData().get("r_thigh_x").get(0), 1e-9);
    }
}
