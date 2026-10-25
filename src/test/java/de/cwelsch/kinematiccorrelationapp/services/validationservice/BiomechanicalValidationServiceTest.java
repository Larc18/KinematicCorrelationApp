package de.cwelsch.kinematiccorrelationapp.services.validationservice;

import de.cwelsch.kinematiccorrelationapp.model.FrameData;
import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Trial;
import de.cwelsch.kinematiccorrelationapp.model.TrialSession;
import de.cwelsch.kinematiccorrelationapp.model.participant.Gender;
import de.cwelsch.kinematiccorrelationapp.model.participant.Participant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BiomechanicalValidationService Anatomical Plausibility Test Suite")
class BiomechanicalValidationServiceTest {

    private BiomechanicalValidationService validationService;

    @BeforeEach
    void setUp() {
        validationService = new BiomechanicalValidationService();
    }

    /**
     * Baut einen Frame mit genau einem gesetzten Gelenkwinkel; alle anderen Gelenke bleiben unbelegt (null).
     */
    private FrameData frame(int frameNumber, Joints joint, double angle) {
        Map<Joints, Double> angles = new HashMap<>();
        angles.put(joint, angle);
        return new FrameData(frameNumber, frameNumber * 0.01, new HashMap<>(), angles);
    }

    private TrialSession sessionWithFrames(List<FrameData> frames) {
        Trial trial = new Trial(frames, "sqt_0001.mot", true);
        Participant participant = new Participant("P01", Gender.MALE, 1.80, 75.0);
        List<Trial> trials = new ArrayList<>();
        trials.add(trial);
        return new TrialSession(participant, trials);
    }

    @Test
    @DisplayName("Should return an empty, successful report for a null or empty TrialSession")
    void testNullOrEmptySession_ReturnsEmptySuccessfulReport() {
        ValidationReport nullReport = validationService.validate(null);
        assertTrue(nullReport.isSuccessful());
        assertTrue(nullReport.getWarnings().isEmpty());

        Participant participant = new Participant("P01", Gender.MALE, 1.80, 75.0);
        ValidationReport emptyReport = validationService.validate(new TrialSession(participant, new ArrayList<>()));
        assertTrue(emptyReport.isSuccessful());
        assertTrue(emptyReport.getWarnings().isEmpty());
    }

    @Test
    @DisplayName("Should not warn for knee/hip angles within the anatomically plausible range, including the exact boundaries")
    void testPlausibleAngles_NoWarnings() {
        List<FrameData> frames = List.of(
                frame(1, Joints.KNEE_ANGLE_R, -10.0),   // untere Knie-Grenze, gerade noch plausibel
                frame(2, Joints.KNEE_ANGLE_R, 165.0),   // obere Knie-Grenze, gerade noch plausibel
                frame(3, Joints.HIP_FLEXION_L, -30.0),  // untere Hüft-Grenze, gerade noch plausibel
                frame(4, Joints.HIP_FLEXION_L, 145.0)   // obere Hüft-Grenze, gerade noch plausibel
        );

        ValidationReport report = validationService.validate(sessionWithFrames(frames));

        assertTrue(report.isSuccessful());
        assertTrue(report.getWarnings().isEmpty());
    }

    @Test
    @DisplayName("Should warn exactly once per joint for a value just beyond the plausible knee/hip boundaries")
    void testImplausibleBoundaryAngles_AddWarnings() {
        List<FrameData> frames = List.of(
                frame(1, Joints.KNEE_ANGLE_R, -10.01),
                frame(2, Joints.KNEE_ANGLE_L, 165.01),
                frame(3, Joints.HIP_FLEXION_R, -30.01),
                frame(4, Joints.HIP_FLEXION_L, 145.01)
        );

        ValidationReport report = validationService.validate(sessionWithFrames(frames));

        assertTrue(report.isSuccessful()); // Anatomische Ausreißer sind Warnungen, keine Fehler
        assertEquals(4, report.getWarnings().size());
    }

    @Test
    @DisplayName("Should collapse a contiguous run of out-of-bounds knee angles into a single warning")
    void testContiguousKneeExcursion_CollapsesIntoSingleWarning() {
        List<FrameData> frames = new ArrayList<>();
        frames.add(frame(1, Joints.KNEE_ANGLE_R, 30.0));   // normal
        frames.add(frame(2, Joints.KNEE_ANGLE_R, 200.0));  // Exkursion beginnt
        frames.add(frame(3, Joints.KNEE_ANGLE_R, 210.0));  // weiterhin außerhalb
        frames.add(frame(4, Joints.KNEE_ANGLE_R, 190.0));  // weiterhin außerhalb
        frames.add(frame(5, Joints.KNEE_ANGLE_R, 30.0));   // zurück im plausiblen Bereich

        ValidationReport report = validationService.validate(sessionWithFrames(frames));

        assertEquals(1, report.getWarnings().size());
        assertTrue(report.getWarnings().get(0).getMessage().contains("KNEE_ANGLE_R"));
    }

    @Test
    @DisplayName("Should emit a new warning for each separate excursion, not just the first one")
    void testTwoSeparateExcursions_AddTwoWarnings() {
        List<FrameData> frames = new ArrayList<>();
        frames.add(frame(1, Joints.KNEE_ANGLE_R, 200.0)); // Exkursion 1
        frames.add(frame(2, Joints.KNEE_ANGLE_R, 30.0));  // zurück normal
        frames.add(frame(3, Joints.KNEE_ANGLE_R, 200.0)); // Exkursion 2

        ValidationReport report = validationService.validate(sessionWithFrames(frames));

        assertEquals(2, report.getWarnings().size());
    }

    @Test
    @DisplayName("Should ignore null and NaN angle values without throwing or warning")
    void testNullAndNaNAngles_IgnoredSafely() {
        Map<Joints, Double> angles = new HashMap<>();
        angles.put(Joints.KNEE_ANGLE_L, Double.NaN);
        // KNEE_ANGLE_R absichtlich nicht gesetzt (null)
        List<FrameData> frames = List.of(new FrameData(1, 0.0, new HashMap<>(), angles));

        ValidationReport report = assertDoesNotThrow(() -> validationService.validate(sessionWithFrames(frames)));

        assertTrue(report.getWarnings().isEmpty());
    }
}
