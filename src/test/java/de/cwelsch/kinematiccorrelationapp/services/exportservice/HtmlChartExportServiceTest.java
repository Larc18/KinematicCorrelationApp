package de.cwelsch.kinematiccorrelationapp.services.exportservice;

import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Movement;
import de.cwelsch.kinematiccorrelationapp.model.Trial;
import de.cwelsch.kinematiccorrelationapp.model.TrialSession;
import de.cwelsch.kinematiccorrelationapp.model.participant.Gender;
import de.cwelsch.kinematiccorrelationapp.model.participant.Participant;
import de.cwelsch.kinematiccorrelationapp.services.statisticalanalysisservice.AnalysisResults;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HtmlChartExportService Dashboard Aggregation Test Suite")
class HtmlChartExportServiceTest {

    private static final int POINTS = 101;

    @TempDir
    Path tempDir;

    private HtmlChartExportService service;
    private Locale originalLocale;

    @BeforeEach
    void setUp() {
        service = new HtmlChartExportService(tempDir.toString());
        originalLocale = Locale.getDefault();
    }

    @AfterEach
    void tearDown() {
        Locale.setDefault(originalLocale);
    }

    private double[] constantArray(double value) {
        double[] arr = new double[POINTS];
        Arrays.fill(arr, value);
        return arr;
    }

    private Trial trialWithNormalizedAngles(String fileName, boolean isOpenCap, Joints joint, double[] normalizedAngles, boolean fallbackUsed) {
        Trial trial = new Trial(new ArrayList<>(), fileName, isOpenCap);
        trial.setNormalizedAngles(joint, normalizedAngles);
        trial.setWindowFallbackUsed(fallbackUsed);
        return trial;
    }

    private TrialSession sessionWith(Trial... trials) {
        Participant participant = new Participant("P01", Gender.MALE, 1.80, 75.0);
        return new TrialSession(participant, new ArrayList<>(List.of(trials)));
    }

    private String readExportedHtml() throws IOException {
        try (Stream<Path> stream = Files.list(tempDir)) {
            Path htmlFile = stream.filter(p -> p.getFileName().toString().startsWith("biomechanical_dashboard_"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No HTML dashboard was exported"));
            return Files.readString(htmlFile);
        }
    }

    private long countOccurrences(String text, String needle) {
        long count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Test
    @DisplayName("Should create a non-empty, well-formed HTML file")
    void testExport_CreatesNonEmptyHtmlFile() throws IOException {
        service.exportAggregatedDashboard(new ArrayList<>(), new AnalysisResults());

        String html = readExportedHtml();

        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.trim().endsWith("</html>"));
    }

    @Test
    @DisplayName("Should include a table row for a combination with OpenCap data and matching statistical results")
    void testExport_IncludesTableRow_WhenOpenCapDataAndStatisticalResultsPresent() throws IOException {
        Trial oc = trialWithNormalizedAngles("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, constantArray(30.0), false);
        TrialSession session = sessionWith(oc);

        AnalysisResults results = new AnalysisResults();
        results.setResultsForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R, constantArray(0.8), constantArray(2.5), constantArray(5.0));

        service.exportAggregatedDashboard(List.of(session), results);
        String html = readExportedHtml();

        assertTrue(html.contains("Rechtes Knie Flexion/Extension"));
        assertTrue(html.contains("badge-good\">Exzellent</span>"));
    }

    @Test
    @DisplayName("Should exclude a combination from the table when only Theia (no OpenCap) contributed data")
    void testExport_ExcludesTableRow_WhenOnlyTheiaDataPresent() throws IOException {
        Trial theia = trialWithNormalizedAngles("sqt_0001.json", false, Joints.KNEE_ANGLE_R, constantArray(30.0), false);
        TrialSession session = sessionWith(theia);

        AnalysisResults results = new AnalysisResults();
        results.setResultsForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R, constantArray(0.8), constantArray(2.5), constantArray(5.0));

        service.exportAggregatedDashboard(List.of(session), results);
        String html = readExportedHtml();

        assertFalse(html.contains("Exzellent"), "Ohne OpenCap-Beitrag darf die Tabellenzeile nicht erscheinen (Gatekeeper ist openCapAggregates)");
    }

    @Test
    @DisplayName("Should show no validity badges when statistical results are missing entirely")
    void testExport_NoValidityBadges_WhenStatisticalResultsEmpty() throws IOException {
        Trial oc = trialWithNormalizedAngles("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, constantArray(30.0), false);
        TrialSession session = sessionWith(oc);

        service.exportAggregatedDashboard(List.of(session), new AnalysisResults());
        String html = readExportedHtml();

        assertFalse(html.contains("Exzellent"));
        assertFalse(html.contains("Moderat"));
        assertFalse(html.contains("Mangelhaft"));
    }

    @Test
    @DisplayName("Should map ICC averages to the correct validity badge for each threshold")
    void testExport_ValidityBadges_MatchIccThresholds() throws IOException {
        Trial oc = new Trial(new ArrayList<>(), "sqt_0001.mot", true);
        oc.setNormalizedAngles(Joints.HIP_FLEXION_R, constantArray(30.0));
        oc.setNormalizedAngles(Joints.KNEE_ANGLE_R, constantArray(30.0));
        oc.setNormalizedAngles(Joints.ANKLE_ANGLE_R, constantArray(30.0));
        TrialSession session = sessionWith(oc);

        AnalysisResults results = new AnalysisResults();
        results.setResultsForJoint(Movement.SQUAT, Joints.HIP_FLEXION_R, constantArray(0.9), constantArray(1.0), constantArray(0.0));
        results.setResultsForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R, constantArray(0.5), constantArray(1.0), constantArray(0.0));
        results.setResultsForJoint(Movement.SQUAT, Joints.ANKLE_ANGLE_R, constantArray(0.2), constantArray(1.0), constantArray(0.0));

        service.exportAggregatedDashboard(List.of(session), results);
        String html = readExportedHtml();

        assertEquals(1, countOccurrences(html, "badge-good\">Exzellent</span>"));
        assertEquals(1, countOccurrences(html, "badge-acceptable\">Moderat</span>"));
        assertEquals(1, countOccurrences(html, "badge-poor\">Mangelhaft</span>"));
    }

    @Test
    @DisplayName("Should combine OpenCap and Theia fallback counts into a single fallback badge")
    void testExport_FallbackBadge_CombinesOpenCapAndTheiaCounts() throws IOException {
        Trial oc = trialWithNormalizedAngles("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, constantArray(30.0), true);
        Trial theia = trialWithNormalizedAngles("sqt_0001.json", false, Joints.KNEE_ANGLE_R, constantArray(30.0), false);
        TrialSession session = sessionWith(oc, theia);

        AnalysisResults results = new AnalysisResults();
        results.setResultsForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R, constantArray(0.8), constantArray(2.5), constantArray(5.0));

        service.exportAggregatedDashboard(List.of(session), results);
        String html = readExportedHtml();

        assertTrue(html.contains("badge-poor\">1/2</span>"), "1 von 2 beitragenden Trials (OpenCap+Theia) nutzte den Fallback");
    }

    @Test
    @DisplayName("Should use a good (green) fallback badge when no contributing trial used the fallback window")
    void testExport_FallbackBadge_IsGoodWhenNoFallbackUsed() throws IOException {
        Trial oc = trialWithNormalizedAngles("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, constantArray(30.0), false);
        TrialSession session = sessionWith(oc);

        AnalysisResults results = new AnalysisResults();
        results.setResultsForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R, constantArray(0.8), constantArray(2.5), constantArray(5.0));

        service.exportAggregatedDashboard(List.of(session), results);
        String html = readExportedHtml();

        assertTrue(html.contains("badge-good\">0/1</span>"));
    }

    @Test
    @DisplayName("Should format numbers with German decimal commas regardless of the JVM default locale, consistent with CsvExportService")
    void testExport_NumberFormatting_IsLocaleIndependentAndConsistentWithCsvExport() throws IOException {
        Trial oc = trialWithNormalizedAngles("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, constantArray(30.0), false);
        TrialSession session = sessionWith(oc);

        AnalysisResults results = new AnalysisResults();
        results.setResultsForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R, constantArray(0.8), constantArray(2.5), constantArray(5.0));

        // Simuliert ein System mit US-Standard-Locale (z.B. viele englischsprachige Windows/macOS-Installationen).
        // Sowohl CSV- als auch HTML-Export erzwingen Locale.GERMAN im Format-String, unabhängig davon.
        Locale.setDefault(Locale.US);

        CsvExportService csvService = new CsvExportService(tempDir.toString());
        csvService.exportAggregatedMetricsToCsv(List.of(session), results);
        service.exportAggregatedDashboard(List.of(session), results);

        String html = readExportedHtml();
        String csv;
        try (Stream<Path> stream = Files.list(tempDir)) {
            Path csvFile = stream.filter(p -> p.getFileName().toString().startsWith("biomechanical_metrics_"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No CSV file was exported"));
            csv = Files.readString(csvFile);
        }

        assertTrue(csv.contains("0,8000"), "CSV muss auch unter Locale.US ein Komma verwenden");
        assertTrue(html.contains("0,8000"), "HTML muss auch unter Locale.US ein Komma verwenden, konsistent zum CSV-Export");
        assertFalse(html.contains("0.8000"), "HTML darf nicht der System-Locale folgen und einen Punkt verwenden");
    }

    @Test
    @DisplayName("Should render the signed bias and the centered RMSE in the metrics table")
    void testExport_RendersBiasAndCenteredRmse_InMetricsTable() throws IOException {
        Trial oc = trialWithNormalizedAngles("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, constantArray(30.0), false);
        TrialSession session = sessionWith(oc);

        AnalysisResults results = new AnalysisResults();
        results.setResultsForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R, constantArray(0.8), constantArray(2.5), constantArray(5.0));
        results.setBiasResultsForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R, -1.75, 1.25);

        service.exportAggregatedDashboard(List.of(session), results);
        String html = readExportedHtml();

        assertTrue(html.contains("<th>Bias (Mittelwert)</th>"));
        assertTrue(html.contains("<th>RMSE zentriert (Mittelwert)</th>"));
        // Das Vorzeichen ist beim Bias die eigentliche Information und wird deshalb erzwungen.
        assertTrue(html.contains("-1,75&deg;"), "Negativer Bias (OpenCap unter Theia) muss mit Vorzeichen erscheinen");
        assertTrue(html.contains("1,25&deg;"));
    }

    @Test
    @DisplayName("Should render a dash instead of zero when bias results are missing for a combination")
    void testExport_RendersDash_WhenBiasResultsMissing() throws IOException {
        Trial oc = trialWithNormalizedAngles("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, constantArray(30.0), false);
        TrialSession session = sessionWith(oc);

        // ICC/RMSE vorhanden, Bias nicht (z.B. kein gematchtes OpenCap/Theia-Kurvenpaar):
        // ein ausgegebenes 0,00° würde hier fälschlich "kein Versatz" statt "keine Daten" bedeuten.
        AnalysisResults results = new AnalysisResults();
        results.setResultsForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R, constantArray(0.8), constantArray(2.5), constantArray(5.0));

        service.exportAggregatedDashboard(List.of(session), results);
        String html = readExportedHtml();

        assertTrue(html.contains("&ndash;"), "Fehlende Bias-Werte müssen als Gedankenstrich erscheinen");
        assertFalse(html.contains("+0,00&deg;"));
    }

    @Test
    @DisplayName("Should list per-participant bias and centered RMSE as copyable value lists aligned to the RMSE list")
    void testExport_PerParticipantSection_ListsBiasAndCenteredRmse() throws IOException {
        Trial oc = trialWithNormalizedAngles("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, constantArray(30.0), false);
        TrialSession session = sessionWith(oc);

        AnalysisResults results = new AnalysisResults();
        results.setResultsForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R, constantArray(0.8), constantArray(2.5), constantArray(5.0));
        results.setPerParticipantMeanRmse(new LinkedHashMap<>(Map.of("P01", 6.0, "P02", 4.0)));
        results.setPerParticipantMeanBias(
                new LinkedHashMap<>(Map.of("P01", -2.0, "P02", 3.0)),
                new LinkedHashMap<>(Map.of("P01", 5.0, "P02", 2.5)));

        service.exportAggregatedDashboard(List.of(session), results);
        String html = readExportedHtml();

        assertTrue(html.contains("Bias-Werteliste"));
        assertTrue(html.contains("Zentrierte RMSE-Werteliste"));
        // Probanden werden alphabetisch sortiert -> P01 zuerst, in allen drei Listen identisch.
        assertTrue(html.contains("6.0000, 4.0000"), "RMSE-Liste in Probandenreihenfolge");
        assertTrue(html.contains("-2.0000, 3.0000"), "Bias-Liste in derselben Reihenfolge");
        assertTrue(html.contains("5.0000, 2.5000"), "Zentrierte RMSE-Liste in derselben Reihenfolge");
    }

    @Test
    @DisplayName("Should mark a participant without bias values as NA instead of shifting the remaining values")
    void testExport_PerParticipantSection_MarksMissingBiasAsNa() throws IOException {
        Trial oc = trialWithNormalizedAngles("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, constantArray(30.0), false);
        TrialSession session = sessionWith(oc);

        AnalysisResults results = new AnalysisResults();
        results.setPerParticipantMeanRmse(new LinkedHashMap<>(Map.of("P01", 6.0, "P02", 4.0)));
        // Nur für P02 liegt ein Bias vor -- P01 darf die Liste nicht nach links verschieben.
        results.setPerParticipantMeanBias(
                new LinkedHashMap<>(Map.of("P02", 3.0)),
                new LinkedHashMap<>(Map.of("P02", 2.5)));

        service.exportAggregatedDashboard(List.of(session), results);
        String html = readExportedHtml();

        assertTrue(html.contains("NA, 3.0000"), "Fehlender Wert muss als NA an seiner Position stehen");
        assertTrue(html.contains("NA, 2.5000"));
    }
}
