package de.cwelsch.kinematiccorrelationapp.services.statisticalanalysisservice;

import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Movement;
import de.cwelsch.kinematiccorrelationapp.model.Trial;
import de.cwelsch.kinematiccorrelationapp.model.TrialSession;
import de.cwelsch.kinematiccorrelationapp.model.participant.Gender;
import de.cwelsch.kinematiccorrelationapp.model.participant.Participant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("StatisticalAnalysisService RMSE/NRMSE Aggregation Test Suite")
class StatisticalAnalysisServiceTest {

    private static final int POINTS = 101;

    /**
     * Erstellt ein 101-Punkte-Array mit überall demselben konstanten Wert.
     */
    private double[] constantCurve(double value) {
        double[] curve = new double[POINTS];
        Arrays.fill(curve, value);
        return curve;
    }

    /**
     * Erstellt einen normalisierten Trial-Stub, dessen Kurve direkt gesetzt wird (ohne echtes Frame-Tracking).
     */
    private Trial trialWithCurve(String fileName, boolean isOpenCap, Joints joint, double[] curve) {
        Trial trial = new Trial(new ArrayList<>(), fileName, isOpenCap);
        trial.setNormalizedAngles(joint, curve);
        return trial;
    }

    private TrialSession singleParticipantSession(String id, Trial openCap, Trial theia) {
        Participant participant = new Participant(id, Gender.MALE, 1.80, 75.0);
        List<Trial> trials = new ArrayList<>();
        trials.add(openCap);
        trials.add(theia);
        return new TrialSession(participant, trials);
    }

    @Test
    @DisplayName("Should compute the exact RMSE and NRMSE for a single participant with a known offset and ROM")
    void testRmseAndNrmse_SingleParticipant_KnownOffsetAndRom() {
        // OpenCap-Kurve mit ROM=100 (linear von 0 bis 100), Theia exakt um +5° verschoben.
        double[] openCapCurve = new double[POINTS];
        double[] theiaCurve = new double[POINTS];
        for (int i = 0; i < POINTS; i++) {
            openCapCurve[i] = i;
            theiaCurve[i] = i + 5.0;
        }

        Trial oc = trialWithCurve("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, openCapCurve);
        Trial th = trialWithCurve("sqt_0001.json", false, Joints.KNEE_ANGLE_R, theiaCurve);
        TrialSession session = singleParticipantSession("P01", oc, th);

        StatisticalAnalysisService service = new StatisticalAnalysisService(List.of(session));
        AnalysisResults results = service.analyze();

        double[] rmse = results.getRmseForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R);
        double[] nrmse = results.getNrmseForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R);

        assertNotNull(rmse);
        assertNotNull(nrmse);
        for (int i = 0; i < POINTS; i++) {
            // Ein einzelner Proband: RMSE ist exakt der absolute Fehler an diesem Punkt.
            assertEquals(5.0, rmse[i], 1e-9, "RMSE weicht an Punkt " + i + " ab");
            // NRMSE = RMSE / ROM * 100 = 5 / 100 * 100 = 5%
            assertEquals(5.0, nrmse[i], 1e-9, "NRMSE weicht an Punkt " + i + " ab");
        }
    }

    @Test
    @DisplayName("Should aggregate RMSE across multiple participants as the root-mean-square of their individual errors")
    void testRmse_MultipleParticipants_AggregatesAsRootMeanSquare() {
        // Proband A: Fehler von -3° an jedem Punkt. Proband B: Fehler von +3° an jedem Punkt.
        // RMSE = sqrt((9 + 9) / 2) = 3.0 exakt -- verifiziert, dass sich Ober-/Unterschätzung
        // NICHT einfach aufheben (kein simpler Mittelwert der Fehler, sondern RMS).
        Trial ocA = trialWithCurve("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, constantCurve(10.0));
        Trial thA = trialWithCurve("sqt_0001.json", false, Joints.KNEE_ANGLE_R, constantCurve(13.0));
        TrialSession sessionA = singleParticipantSession("P01", ocA, thA);

        Trial ocB = trialWithCurve("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, constantCurve(10.0));
        Trial thB = trialWithCurve("sqt_0001.json", false, Joints.KNEE_ANGLE_R, constantCurve(7.0));
        TrialSession sessionB = singleParticipantSession("P02", ocB, thB);

        StatisticalAnalysisService service = new StatisticalAnalysisService(List.of(sessionA, sessionB));
        AnalysisResults results = service.analyze();

        double[] rmse = results.getRmseForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R);

        assertNotNull(rmse);
        for (double v : rmse) {
            assertEquals(3.0, v, 1e-9);
        }
    }

    @Test
    @DisplayName("Should leave movement/joint combinations without matched trial pairs unpopulated instead of defaulting to zero")
    void testUnmatchedCombination_StaysNull() {
        // Nur SQUAT/KNEE_ANGLE_R wird beliefert - andere Kombinationen dürfen NICHT fälschlich
        // mit 0.0 auftauchen (siehe CRITICAL FIX-Historie in CsvExportService/HtmlChartExportService:
        // ein leerer Stream lieferte früher getAverage()==0.0 statt "keine Daten").
        Trial oc = trialWithCurve("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, constantCurve(10.0));
        Trial th = trialWithCurve("sqt_0001.json", false, Joints.KNEE_ANGLE_R, constantCurve(12.0));
        TrialSession session = singleParticipantSession("P01", oc, th);

        StatisticalAnalysisService service = new StatisticalAnalysisService(List.of(session));
        AnalysisResults results = service.analyze();

        assertNull(results.getRmseForJoint(Movement.SQUAT, Joints.ANKLE_ANGLE_R));
        assertNull(results.getRmseForJoint(Movement.COUNTERMOVEMENT_JUMP, Joints.KNEE_ANGLE_R));
    }

    @Test
    @DisplayName("Should skip trials whose source file name has no extension when matching OpenCap/Theia pairs")
    void testFileNameWithoutExtension_TrialNotMatched() {
        // ImportService liefert Dateinamen normalerweise immer mit Endung; ohne "." kann
        // StatisticalAnalysisService den Basisnamen nicht extrahieren und überspringt das Paar
        // vollständig, statt versehentlich einen falschen Partner zu matchen.
        Trial oc = trialWithCurve("sqt_0001_no_extension", true, Joints.KNEE_ANGLE_R, constantCurve(10.0));
        Trial th = trialWithCurve("sqt_0001.json", false, Joints.KNEE_ANGLE_R, constantCurve(12.0));
        TrialSession session = singleParticipantSession("P01", oc, th);

        StatisticalAnalysisService service = new StatisticalAnalysisService(List.of(session));
        AnalysisResults results = service.analyze();

        assertNull(results.getRmseForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R));
    }

    @Test
    @DisplayName("Should report a pure constant offset entirely as bias, leaving the centered RMSE at zero")
    void testBias_PureConstantOffset_CenteredRmseIsZero() {
        // OpenCap liegt an JEDEM Punkt exakt 5° unter Theia -> die Kurvenform stimmt perfekt,
        // die komplette Abweichung steckt im konstanten Versatz.
        double[] openCapCurve = new double[POINTS];
        double[] theiaCurve = new double[POINTS];
        for (int i = 0; i < POINTS; i++) {
            openCapCurve[i] = i;
            theiaCurve[i] = i + 5.0;
        }

        Trial oc = trialWithCurve("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, openCapCurve);
        Trial th = trialWithCurve("sqt_0001.json", false, Joints.KNEE_ANGLE_R, theiaCurve);
        TrialSession session = singleParticipantSession("P01", oc, th);

        AnalysisResults results = new StatisticalAnalysisService(List.of(session)).analyze();

        Double bias = results.getBiasForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R);
        Double rmseCentered = results.getRmseCenteredForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R);

        assertNotNull(bias);
        assertNotNull(rmseCentered);
        // Bias ist signiert (OpenCap - Theia): OpenCap liegt tiefer -> negativ.
        assertEquals(-5.0, bias, 1e-9);
        assertEquals(0.0, rmseCentered, 1e-9, "Ohne Formabweichung muss der zentrierte RMSE verschwinden");
    }

    @Test
    @DisplayName("Should report a pure shape deviation without offset as zero bias and an unchanged centered RMSE")
    void testBias_PureShapeDeviation_BiasIsZeroAndCenteredRmseEqualsRmse() {
        // Differenz oszilliert um 0: Punkt 0 ohne Abweichung, danach abwechselnd +2°/-2°
        // (50 x +2, 50 x -2) -> Bias exakt 0, der Fehler liegt vollständig im Kurvenverlauf.
        double[] openCapCurve = constantCurve(10.0);
        double[] theiaCurve = constantCurve(10.0);
        for (int i = 1; i < POINTS; i++) {
            theiaCurve[i] = 10.0 + ((i % 2 == 1) ? -2.0 : 2.0);
        }

        Trial oc = trialWithCurve("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, openCapCurve);
        Trial th = trialWithCurve("sqt_0001.json", false, Joints.KNEE_ANGLE_R, theiaCurve);
        TrialSession session = singleParticipantSession("P01", oc, th);

        AnalysisResults results = new StatisticalAnalysisService(List.of(session)).analyze();

        Double bias = results.getBiasForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R);
        Double rmseCentered = results.getRmseCenteredForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R);

        assertNotNull(bias);
        assertNotNull(rmseCentered);
        assertEquals(0.0, bias, 1e-9);
        // 100 Punkte mit |d| = 2, ein Punkt mit d = 0 -> RMSE = sqrt(400 / 101).
        assertEquals(Math.sqrt(400.0 / POINTS), rmseCentered, 1e-9,
                "Ohne Versatz darf die Zentrierung den RMSE nicht verändern");
    }

    @Test
    @DisplayName("Should satisfy the identity rmseCentered^2 = rmse^2 - bias^2 for a mixed offset and shape deviation")
    void testBias_MixedDeviation_SatisfiesVarianceIdentity() {
        // Differenz = 4° (Versatz) plus abwechselnd ±1° (Formabweichung).
        double[] openCapCurve = constantCurve(20.0);
        double[] theiaCurve = new double[POINTS];
        for (int i = 0; i < POINTS; i++) {
            theiaCurve[i] = 20.0 - 4.0 + ((i % 2 == 0) ? 1.0 : -1.0);
        }

        Trial oc = trialWithCurve("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, openCapCurve);
        Trial th = trialWithCurve("sqt_0001.json", false, Joints.KNEE_ANGLE_R, theiaCurve);
        TrialSession session = singleParticipantSession("P01", oc, th);

        AnalysisResults results = new StatisticalAnalysisService(List.of(session)).analyze();

        double rmse = results.getPerParticipantMeanRmse().get("P01");
        double bias = results.getPerParticipantMeanBias().get("P01");
        double rmseCentered = results.getPerParticipantMeanRmseCentered().get("P01");

        // Zerlegung des Gesamtfehlers: Ein großer RMSE bei kleinem zentrierten RMSE bedeutet,
        // dass die Abweichung praktisch vollständig aus einem festen Offset stammt.
        assertEquals(rmse * rmse - bias * bias, rmseCentered * rmseCentered, 1e-9);
        assertTrue(rmseCentered < rmse, "Der zentrierte RMSE muss kleiner sein, sobald ein Versatz existiert");
    }

    @Test
    @DisplayName("Should let opposing participant offsets cancel in the cohort bias while keeping each centered RMSE at zero")
    void testBias_MultipleParticipants_OpposingOffsetsCancel() {
        // Proband A: OpenCap 3° über Theia. Proband B: 3° darunter. Der Kohorten-Bias mittelt sich
        // zu 0 (das ist bei einem signierten Versatz korrekt), der RMSE bleibt bei 3°.
        Trial ocA = trialWithCurve("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, constantCurve(13.0));
        Trial thA = trialWithCurve("sqt_0001.json", false, Joints.KNEE_ANGLE_R, constantCurve(10.0));
        TrialSession sessionA = singleParticipantSession("P01", ocA, thA);

        Trial ocB = trialWithCurve("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, constantCurve(7.0));
        Trial thB = trialWithCurve("sqt_0001.json", false, Joints.KNEE_ANGLE_R, constantCurve(10.0));
        TrialSession sessionB = singleParticipantSession("P02", ocB, thB);

        AnalysisResults results = new StatisticalAnalysisService(List.of(sessionA, sessionB)).analyze();

        assertEquals(0.0, results.getBiasForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R), 1e-9);
        assertEquals(3.0, results.getPerParticipantMeanBias().get("P01"), 1e-9);
        assertEquals(-3.0, results.getPerParticipantMeanBias().get("P02"), 1e-9);

        // Der zentrierte RMSE wird PRO PROBAND gebildet und erst danach gemittelt -- deshalb bleibt
        // er hier 0 und wird nicht durch den gegenläufigen Versatz des anderen Probanden aufgeblasen.
        assertEquals(0.0, results.getRmseCenteredForJoint(Movement.SQUAT, Joints.KNEE_ANGLE_R), 1e-9);
    }

    @Test
    @DisplayName("Should leave bias unpopulated for movement/joint combinations without matched trial pairs")
    void testBias_UnmatchedCombination_StaysNull() {
        Trial oc = trialWithCurve("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, constantCurve(10.0));
        Trial th = trialWithCurve("sqt_0001.json", false, Joints.KNEE_ANGLE_R, constantCurve(12.0));
        TrialSession session = singleParticipantSession("P01", oc, th);

        AnalysisResults results = new StatisticalAnalysisService(List.of(session)).analyze();

        assertNull(results.getBiasForJoint(Movement.SQUAT, Joints.ANKLE_ANGLE_R));
        assertNull(results.getRmseCenteredForJoint(Movement.COUNTERMOVEMENT_JUMP, Joints.KNEE_ANGLE_R));
    }

    @Test
    @DisplayName("Should skip NaN points consistently across RMSE, bias and centered RMSE")
    void testBias_NaNPoints_AreSkippedConsistently() {
        // Nur die erste Hälfte der Kurve ist belegt: Die NaN-Punkte dürfen nicht als 0-Differenz
        // in den Bias einfließen, sonst würde der Versatz systematisch unterschätzt.
        double[] openCapCurve = constantCurve(Double.NaN);
        double[] theiaCurve = constantCurve(Double.NaN);
        for (int i = 0; i < 50; i++) {
            openCapCurve[i] = 16.0;
            theiaCurve[i] = 10.0;
        }

        Trial oc = trialWithCurve("sqt_0001.mot", true, Joints.KNEE_ANGLE_R, openCapCurve);
        Trial th = trialWithCurve("sqt_0001.json", false, Joints.KNEE_ANGLE_R, theiaCurve);
        TrialSession session = singleParticipantSession("P01", oc, th);

        AnalysisResults results = new StatisticalAnalysisService(List.of(session)).analyze();

        assertEquals(6.0, results.getPerParticipantMeanBias().get("P01"), 1e-9);
        assertEquals(6.0, results.getPerParticipantMeanRmse().get("P01"), 1e-9);
        assertEquals(0.0, results.getPerParticipantMeanRmseCentered().get("P01"), 1e-9);
    }
}
