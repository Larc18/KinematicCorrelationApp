package de.cwelsch.kinematiccorrelationapp.services.exportservice;

import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Movement;
import de.cwelsch.kinematiccorrelationapp.model.Trial;
import de.cwelsch.kinematiccorrelationapp.model.TrialSession;
import de.cwelsch.kinematiccorrelationapp.model.participant.Gender;
import de.cwelsch.kinematiccorrelationapp.model.participant.Participant;
import de.cwelsch.kinematiccorrelationapp.services.statisticalanalysisservice.AnalysisResults;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CsvExportService Aggregated Metrics Export Test Suite")
class CsvExportServiceTest {

    private static final int POINTS = 101;

    @TempDir
    Path tempDir;

    private CsvExportService service;

    @BeforeEach
    void setUp() {
        service = new CsvExportService(tempDir.toString());
    }

    private double[] constantArray(double value) {
        double[] arr = new double[POINTS];
        Arrays.fill(arr, value);
        return arr;
    }

    private Trial trialWithNormalizedAngles(String fileName, Joints joint, double[] normalizedAngles, boolean fallbackUsed) {
        Trial trial = new Trial(new ArrayList<>(), fileName, true);
        trial.setNormalizedAngles(joint, normalizedAngles);
        trial.setWindowFallbackUsed(fallbackUsed);
        return trial;
    }

    private TrialSession sessionWith(Trial... trials) {
        Participant participant = new Participant("P01", Gender.MALE, 1.80, 75.0);
        return new TrialSession(participant, new ArrayList<>(List.of(trials)));
    }

    private List<String> readExportedCsvLines() throws IOException {
        try (Stream<Path> stream = Files.list(tempDir)) {
            Path csvFile = stream.filter(p -> p.getFileName().toString().startsWith("biomechanical_metrics_"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No CSV file was exported"));
            return Files.readAllLines(csvFile, StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("Should write only the header row when there are no trial sessions")
    void testExport_EmptyInput_WritesOnlyHeader() throws IOException {
        service.exportAggregatedMetricsToCsv(new ArrayList<>(), new AnalysisResults());

        List<String> lines = readExportedCsvLines();

        assertEquals(1, lines.size());
        assertEquals("Bewegungsform;Anatomischer_Gelenkwinkel;ICC_Mittelwert;RMSE_Grad;Validitaetsstufe;Fenster_Fallback_Trials", lines.get(0));
    }

    @Test
    @DisplayName("Should write a data row with German decimal formatting (comma) for a present combination")
    void testExport_WritesDataRow_WithGermanDecimalFormatting() throws IOException {
        Trial trial = trialWithNormalizedAngles("sqt_0001.mot", Joints.KNEE_ANGLE_R, constantArray(30.0), false);
        TrialSession session = sessionWith(trial);

        AnalysisResults results = new AnalysisResults();
        results.setResultsForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R, constantArray(0.8), constantArray(2.5), constantArray(0.0));

        service.exportAggregatedMetricsToCsv(List.of(session), results);

        List<String> lines = readExportedCsvLines();

        assertEquals(2, lines.size());
        assertEquals("SQUAT;Rechtes Knie Flexion/Extension;0,8000;2,50;Exzellent;0/1", lines.get(1));
    }

    @Test
    @DisplayName("Should skip a combination that has trial data but no matching statistical results")
    void testExport_SkipsCombination_WhenNoStatisticalResultsExist() throws IOException {
        Trial trial = trialWithNormalizedAngles("sqt_0001.mot", Joints.KNEE_ANGLE_R, constantArray(30.0), false);
        TrialSession session = sessionWith(trial);

        // AnalysisResults bleibt komplett leer -> getIccForJoint/getRmseForJoint liefern null
        AnalysisResults results = new AnalysisResults();

        service.exportAggregatedMetricsToCsv(List.of(session), results);

        List<String> lines = readExportedCsvLines();

        assertEquals(1, lines.size(), "Nur die Header-Zeile darf vorhanden sein");
    }

    @Test
    @DisplayName("Should skip a row when all ICC/RMSE values are NaN instead of falsely reporting 0.0")
    void testExport_SkipsRow_WhenAllIccAndRmseValuesAreNaN() throws IOException {
        Trial trial = trialWithNormalizedAngles("sqt_0001.mot", Joints.KNEE_ANGLE_R, constantArray(30.0), false);
        TrialSession session = sessionWith(trial);

        AnalysisResults results = new AnalysisResults();
        double[] allNaN = constantArray(Double.NaN);
        results.setResultsForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R, allNaN, allNaN, allNaN);

        service.exportAggregatedMetricsToCsv(List.of(session), results);

        List<String> lines = readExportedCsvLines();

        assertEquals(1, lines.size(), "Zeile mit ausschließlich NaN-Werten darf nicht als '0.0'-Zeile erscheinen");
    }

    @Test
    @DisplayName("Should map ICC averages to the correct validity label, including the exact thresholds")
    void testExport_ValidityThresholds_MapToCorrectLabels() throws IOException {
        Trial trial = new Trial(new ArrayList<>(), "sqt_0001.mot", true);
        trial.setNormalizedAngles(Joints.HIP_FLEXION_R, constantArray(30.0));
        trial.setNormalizedAngles(Joints.HIP_FLEXION_L, constantArray(30.0));
        trial.setNormalizedAngles(Joints.KNEE_ANGLE_R, constantArray(30.0));
        trial.setNormalizedAngles(Joints.KNEE_ANGLE_L, constantArray(30.0));
        TrialSession session = sessionWith(trial);

        AnalysisResults results = new AnalysisResults();
        // genau am Grenzwert 0.75 -> Exzellent
        results.setResultsForJoint(Movement.SQUAT, Joints.HIP_FLEXION_R, constantArray(0.75), constantArray(1.0), constantArray(0.0));
        // knapp darunter -> Moderat
        results.setResultsForJoint(Movement.SQUAT, Joints.HIP_FLEXION_L, constantArray(0.7499), constantArray(1.0), constantArray(0.0));
        // genau am Grenzwert 0.40 -> Moderat
        results.setResultsForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R, constantArray(0.40), constantArray(1.0), constantArray(0.0));
        // knapp darunter -> Mangelhaft
        results.setResultsForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_L, constantArray(0.3999), constantArray(1.0), constantArray(0.0));

        service.exportAggregatedMetricsToCsv(List.of(session), results);

        List<String> lines = readExportedCsvLines();

        assertTrue(lines.get(1).contains("Rechte Hüfte Flexion/Extension") && lines.get(1).endsWith("Exzellent;0/1"));
        assertTrue(lines.get(2).contains("Linke Hüfte Flexion/Extension") && lines.get(2).endsWith("Moderat;0/1"));
        assertTrue(lines.get(3).contains("Rechtes Knie Flexion/Extension") && lines.get(3).endsWith("Moderat;0/1"));
        assertTrue(lines.get(4).contains("Linkes Knie Flexion/Extension") && lines.get(4).endsWith("Mangelhaft;0/1"));
    }

    @Test
    @DisplayName("Should show the combined fallback ratio across all contributing trials")
    void testExport_FallbackRatio_ReflectsWindowFallbackUsage() throws IOException {
        Trial fallbackTrial = trialWithNormalizedAngles("sqt_0001.mot", Joints.KNEE_ANGLE_R, constantArray(30.0), true);
        Trial normalTrial = trialWithNormalizedAngles("sqt_0002.mot", Joints.KNEE_ANGLE_R, constantArray(30.0), false);
        TrialSession session = sessionWith(fallbackTrial, normalTrial);

        AnalysisResults results = new AnalysisResults();
        results.setResultsForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R, constantArray(0.8), constantArray(2.5), constantArray(0.0));

        service.exportAggregatedMetricsToCsv(List.of(session), results);

        List<String> lines = readExportedCsvLines();

        assertEquals(2, lines.size());
        assertTrue(lines.get(1).endsWith("1/2"), "1 von 2 beitragenden Trials nutzte den Fenster-Fallback");
    }

    @Test
    @DisplayName("Should order rows by the fixed movement order, then by the fixed joint order, not by insertion order")
    void testExport_RowOrder_FollowsFixedMovementThenJointOrder() throws IOException {
        Trial cmjTrial = new Trial(new ArrayList<>(), "cmj_0001.mot", true);
        cmjTrial.setNormalizedAngles(Joints.KNEE_ANGLE_R, constantArray(30.0));

        // Absichtlich in umgekehrter ANALYSIS_SCOPE-Reihenfolge gesetzt (ANKLE vor HIP)
        Trial squatTrial = new Trial(new ArrayList<>(), "sqt_0001.mot", true);
        squatTrial.setNormalizedAngles(Joints.ANKLE_ANGLE_R, constantArray(30.0));
        squatTrial.setNormalizedAngles(Joints.HIP_FLEXION_R, constantArray(30.0));

        TrialSession session = sessionWith(cmjTrial, squatTrial);

        AnalysisResults results = new AnalysisResults();
        results.setResultsForJoint(Movement.COUNTERMOVEMENT_JUMP, Joints.KNEE_ANGLE_R, constantArray(0.8), constantArray(2.5), constantArray(0.0));
        results.setResultsForJoint(Movement.SQUAT, Joints.ANKLE_ANGLE_R, constantArray(0.8), constantArray(2.5), constantArray(0.0));
        results.setResultsForJoint(Movement.SQUAT, Joints.HIP_FLEXION_R, constantArray(0.8), constantArray(2.5), constantArray(0.0));

        service.exportAggregatedMetricsToCsv(List.of(session), results);

        List<String> lines = readExportedCsvLines();

        // MOVEMENT_ORDER: SQUAT vor COUNTERMOVEMENT_JUMP; innerhalb SQUAT: HIP_FLEXION_R vor ANKLE_ANGLE_R (ANALYSIS_SCOPE-Reihenfolge)
        assertEquals(4, lines.size());
        assertTrue(lines.get(1).startsWith("SQUAT;Rechte Hüfte Flexion/Extension"));
        assertTrue(lines.get(2).startsWith("SQUAT;Rechtes Oberes Sprunggelenk"));
        assertTrue(lines.get(3).startsWith("COUNTERMOVEMENT_JUMP;Rechtes Knie Flexion/Extension"));
    }
}
