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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TechnicalValidationService Structural Integrity Test Suite")
class TechnicalValidationServiceTest {

    private TechnicalValidationService validationService;
    private final double dt = 1.0 / 85.0;

    @BeforeEach
    void setUp() {
        validationService = new TechnicalValidationService();
    }

    /**
     * Baut einen "sauberen" Frame mit gültigen Werten für alle kritischen Analyse-Gelenke (Joints.ANALYSIS_SCOPE).
     */
    private FrameData validFrame(int frameNumber) {
        Map<Joints, Double> angles = new HashMap<>();
        for (Joints joint : Joints.ANALYSIS_SCOPE) {
            angles.put(joint, 30.0);
        }
        return new FrameData(frameNumber, frameNumber * dt, new HashMap<>(), angles);
    }

    private TrialSession sessionWithSingleTrial(Trial trial) {
        Participant participant = new Participant("P01", Gender.MALE, 1.80, 75.0);
        List<Trial> trials = new ArrayList<>();
        trials.add(trial);
        return new TrialSession(participant, trials);
    }

    @Test
    @DisplayName("Should add a single general error for a null TrialSession")
    void testNullSession_AddsSingleGeneralError() {
        ValidationReport report = validationService.validate(null);

        assertFalse(report.isSuccessful());
        assertEquals(1, report.getErrors().size());
        assertTrue(report.getWarnings().isEmpty());
    }

    @Test
    @DisplayName("Should add a single general error for a session without any trials")
    void testEmptyTrialsList_AddsSingleGeneralError() {
        Participant participant = new Participant("P01", Gender.MALE, 1.80, 75.0);
        TrialSession session = new TrialSession(participant, new ArrayList<>());

        ValidationReport report = validationService.validate(session);

        assertFalse(report.isSuccessful());
        assertEquals(1, report.getErrors().size());
    }

    @Test
    @DisplayName("Should skip null entries inside the trial list without throwing")
    void testNullTrialInList_SkippedWithoutException() {
        Participant participant = new Participant("P01", Gender.MALE, 1.80, 75.0);
        List<Trial> trials = new ArrayList<>();
        trials.add(null);
        TrialSession session = new TrialSession(participant, trials);

        assertDoesNotThrow(() -> validationService.validate(session));
    }

    @Test
    @DisplayName("Should report no errors or warnings for a fully valid, gap-free trial")
    void testValidTrial_NoIssues() {
        List<FrameData> frames = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            frames.add(validFrame(i));
        }
        Trial trial = new Trial(frames, "sqt_0001.mot", true);
        TrialSession session = sessionWithSingleTrial(trial);

        ValidationReport report = validationService.validate(session);

        assertTrue(report.isSuccessful());
        assertTrue(report.getWarnings().isEmpty());
        assertFalse(trial.isExcluded());
    }

    @Test
    @DisplayName("Should mark a trial with an empty frame list as excluded and add a critical error")
    void testEmptyFrameList_MarksExcludedAndAddsError() {
        Trial trial = new Trial(new ArrayList<>(), "sqt_0002.mot", true);
        TrialSession session = sessionWithSingleTrial(trial);

        ValidationReport report = validationService.validate(session);

        assertFalse(report.isSuccessful());
        assertEquals(1, report.getErrors().size());
        assertTrue(trial.isExcluded());
        assertEquals("Empty frame metrics", trial.getExclusionReason());
    }

    @Test
    @DisplayName("Should detect a frame-sequence gap, add a critical error and exclude the trial")
    void testFrameNumberGap_AddsErrorAndExcludesTrial() {
        List<FrameData> frames = new ArrayList<>();
        frames.add(validFrame(1));
        frames.add(validFrame(2));
        frames.add(validFrame(4)); // Lücke: Frame 3 fehlt
        frames.add(validFrame(5));

        Trial trial = new Trial(frames, "sqt_0003.mot", true);
        TrialSession session = sessionWithSingleTrial(trial);

        ValidationReport report = validationService.validate(session);

        assertEquals(1, report.getErrors().size());
        assertTrue(report.getErrors().get(0).getMessage().contains("Timeline Gap"));
        assertTrue(trial.isExcluded());
    }

    @Test
    @DisplayName("Should detect a duplicate/out-of-order frame index, add a critical error and exclude the trial")
    void testDuplicateFrame_AddsErrorAndExcludesTrial() {
        List<FrameData> frames = new ArrayList<>();
        frames.add(validFrame(1));
        frames.add(validFrame(2));
        frames.add(validFrame(2)); // Duplikat
        frames.add(validFrame(3));

        Trial trial = new Trial(frames, "sqt_0004.mot", true);
        TrialSession session = sessionWithSingleTrial(trial);

        ValidationReport report = validationService.validate(session);

        assertEquals(1, report.getErrors().size());
        assertTrue(report.getErrors().get(0).getMessage().contains("Structural Anomaly"));
        assertTrue(trial.isExcluded());
    }

    @Test
    @DisplayName("Should add a warning (not an error) for a missing critical joint angle")
    void testMissingCriticalJointAngle_AddsWarningWithoutExcludingTrial() {
        Map<Joints, Double> angles = new HashMap<>();
        for (Joints joint : Joints.ANALYSIS_SCOPE) {
            if (joint != Joints.KNEE_ANGLE_R) {
                angles.put(joint, 30.0);
            }
        }
        List<FrameData> frames = new ArrayList<>();
        frames.add(new FrameData(1, 0.0, new HashMap<>(), angles));

        Trial trial = new Trial(frames, "sqt_0005.mot", true);
        TrialSession session = sessionWithSingleTrial(trial);

        ValidationReport report = validationService.validate(session);

        assertTrue(report.isSuccessful());
        assertEquals(1, report.getWarnings().size());
        assertTrue(report.getWarnings().get(0).getMessage().contains("KNEE_ANGLE_R"));
        assertFalse(trial.isExcluded());
    }

    @Test
    @DisplayName("Should treat a NaN critical joint angle the same as a missing one")
    void testNaNCriticalJointAngle_AddsWarning() {
        Map<Joints, Double> angles = new HashMap<>();
        for (Joints joint : Joints.ANALYSIS_SCOPE) {
            angles.put(joint, 30.0);
        }
        angles.put(Joints.ANKLE_ANGLE_L, Double.NaN);

        List<FrameData> frames = new ArrayList<>();
        frames.add(new FrameData(1, 0.0, new HashMap<>(), angles));

        Trial trial = new Trial(frames, "sqt_0006.mot", true);
        TrialSession session = sessionWithSingleTrial(trial);

        ValidationReport report = validationService.validate(session);

        assertEquals(1, report.getWarnings().size());
        assertTrue(report.getWarnings().get(0).getMessage().contains("ANKLE_ANGLE_L"));
    }
}
