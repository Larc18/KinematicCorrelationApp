package de.cwelsch.kinematiccorrelationapp.services.importservice.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OpenCapParser .mot File Parsing Test Suite")
class OpenCapParserTest {

    @TempDir
    Path tempDir;

    private OpenCapParser parser;

    @BeforeEach
    void setUp() {
        parser = new OpenCapParser();
    }

    private String writeMotFile(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, content);
        return file.toString();
    }

    @Test
    @DisplayName("Should parse int/double/string metadata header entries with the correct types")
    void testParse_ExtractsHeaderMetadata_WithCorrectTypes() throws IOException {
        String content = """
                Coordinates
                version=1
                mass=75.5
                subject=P01
                endheader
                time knee_angle_r
                0.00 10.0
                0.01 20.0
                """;
        String path = writeMotFile("meta.mot", content);

        Parser.MotionData data = parser.parse(path);

        assertEquals(1, data.getMetaData().get("version"));
        assertEquals(75.5, (double) data.getMetaData().get("mass"), 1e-9);
        assertEquals("P01", data.getMetaData().get("subject"));
    }

    @Test
    @DisplayName("Should normalize the time column header to lowercase regardless of the original casing")
    void testParse_NormalizesTimeColumnKeyToLowercase() throws IOException {
        String content = """
                Coordinates
                endheader
                Time knee_angle_r
                0.00 10.0
                0.01 20.0
                """;
        String path = writeMotFile("time_case.mot", content);

        Parser.MotionData data = parser.parse(path);

        assertTrue(data.getData().containsKey("time"));
        assertFalse(data.getData().containsKey("Time"));
        assertEquals(List.of(0.00, 0.01), data.getData().get("time"));
    }

    @Test
    @DisplayName("Should apply the 90-degree offset harmonization (with wraparound) to the known affected channels")
    void testParse_AppliesNinetyDegreeOffsetHarmonization_ToKnownChannels() throws IOException {
        String content = """
                Coordinates
                endheader
                time pelvis_list lumbar_bending
                0.00 90.0 300.0
                """;
        String path = writeMotFile("harmonize.mot", content);

        Parser.MotionData data = parser.parse(path);

        // pelvis_list: 90 - 90 = 0
        assertEquals(0.0, data.getData().get("pelvis_list").get(0), 1e-9);
        // lumbar_bending: 300 - 90 = 210 -> außerhalb [-180,180] -> -360 = -150
        assertEquals(-150.0, data.getData().get("lumbar_bending").get(0), 1e-9);
    }

    @Test
    @DisplayName("Should leave channels outside the known offset set unmodified")
    void testParse_DoesNotHarmonizeUnlistedChannels() throws IOException {
        String content = """
                Coordinates
                endheader
                time knee_angle_r pelvis_tilt
                0.00 90.0 90.0
                """;
        String path = writeMotFile("no_harmonize.mot", content);

        Parser.MotionData data = parser.parse(path);

        assertEquals(90.0, data.getData().get("knee_angle_r").get(0), 1e-9);
        assertEquals(90.0, data.getData().get("pelvis_tilt").get(0), 1e-9);
    }

    @Test
    @DisplayName("Should parse NaN and Inf tokens as Double.NaN")
    void testParse_ParsesNaNAndInfTokensAsNaN() throws IOException {
        String content = """
                Coordinates
                endheader
                time knee_angle_r
                0.00 NaN
                0.01 Inf
                0.02 15.0
                """;
        String path = writeMotFile("nan_inf.mot", content);

        Parser.MotionData data = parser.parse(path);
        List<Double> knee = data.getData().get("knee_angle_r");

        assertTrue(Double.isNaN(knee.get(0)));
        assertTrue(Double.isNaN(knee.get(1)));
        assertEquals(15.0, knee.get(2), 1e-9);
    }

    @Test
    @DisplayName("Should turn a non-numeric token into NaN instead of throwing and desynchronizing the row")
    void testParse_MalformedNumericToken_BecomesNaNWithoutThrowing() throws IOException {
        String content = """
                Coordinates
                endheader
                time knee_angle_r
                0.00 abc
                0.01 20.0
                """;
        String path = writeMotFile("malformed.mot", content);

        Parser.MotionData data = assertDoesNotThrow(() -> parser.parse(path));
        List<Double> knee = data.getData().get("knee_angle_r");

        assertTrue(Double.isNaN(knee.get(0)));
        assertEquals(20.0, knee.get(1), 1e-9);
    }

    @Test
    @DisplayName("Should skip a data row entirely if it has fewer values than the header has columns")
    void testParse_RowWithFewerValuesThanHeader_IsSkipped() throws IOException {
        String content = """
                Coordinates
                endheader
                time knee_angle_r hip_flexion_r
                0.00 10.0 5.0
                0.01 12.0
                0.02 14.0 7.0
                """;
        String path = writeMotFile("short_row.mot", content);

        Parser.MotionData data = parser.parse(path);
        Map<String, List<Double>> columns = data.getData();

        assertEquals(List.of(0.00, 0.02), columns.get("time"));
        assertEquals(List.of(10.0, 14.0), columns.get("knee_angle_r"));
        assertEquals(List.of(5.0, 7.0), columns.get("hip_flexion_r"));
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
        String missingPath = tempDir.resolve("does_not_exist.mot").toString();

        assertThrows(IOException.class, () -> parser.parse(missingPath));
    }

    @Test
    @DisplayName("Should throw IOException when the given path is a directory rather than a file")
    void testParse_PathIsDirectory_ThrowsIOException() throws IOException {
        Path directory = Files.createDirectory(tempDir.resolve("not_a_file.mot"));

        assertThrows(IOException.class, () -> parser.parse(directory.toString()));
    }

    @Test
    @DisplayName("Should return an empty (but non-null) MotionData for a completely empty file")
    void testParse_EmptyFile_ReturnsEmptyMotionData() throws IOException {
        String path = writeMotFile("empty.mot", "");

        Parser.MotionData data = assertDoesNotThrow(() -> parser.parse(path));

        assertTrue(data.getMetaData().isEmpty());
        assertTrue(data.getData().isEmpty());
    }

    @Test
    @DisplayName("Should return an empty result without throwing when the 'endheader' marker is missing entirely")
    void testParse_MissingEndheaderMarker_ReturnsEmptyDataWithoutThrowing() throws IOException {
        // Ohne "endheader" wird die Datei komplett als Metadaten-Header interpretiert;
        // Stage 2 (Datenzeilen) wird nie erreicht, da "headers" leer bleibt.
        String content = """
                Coordinates
                version=1
                0.00 10.0
                0.01 20.0
                """;
        String path = writeMotFile("no_endheader.mot", content);

        Parser.MotionData data = assertDoesNotThrow(() -> parser.parse(path));

        assertEquals(1, data.getMetaData().get("version"));
        assertTrue(data.getData().isEmpty(), "Ohne 'endheader' dürfen keine Spalten registriert werden");
    }

    @Test
    @DisplayName("Should return no columns when 'endheader' is the very last line (no column header line follows)")
    void testParse_EndheaderAsLastLine_ReturnsEmptyColumns() throws IOException {
        String content = """
                Coordinates
                endheader""";
        String path = writeMotFile("endheader_only.mot", content);

        Parser.MotionData data = assertDoesNotThrow(() -> parser.parse(path));

        assertTrue(data.getData().isEmpty());
    }

    @Test
    @DisplayName("Should ignore extra trailing values in a data row that has more columns than the header")
    void testParse_RowWithMoreValuesThanHeader_IgnoresExtraValues() throws IOException {
        String content = """
                Coordinates
                endheader
                time knee_angle_r
                0.00 10.0 999.0 888.0
                """;
        String path = writeMotFile("extra_values.mot", content);

        Parser.MotionData data = parser.parse(path);

        assertEquals(List.of(0.00), data.getData().get("time"));
        assertEquals(List.of(10.0), data.getData().get("knee_angle_r"));
        assertEquals(2, data.getData().size(), "Es dürfen keine zusätzlichen Spalten aus den überzähligen Werten entstehen");
    }
}
