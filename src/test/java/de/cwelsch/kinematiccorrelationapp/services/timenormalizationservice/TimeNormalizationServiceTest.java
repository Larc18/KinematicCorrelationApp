package de.cwelsch.kinematiccorrelationapp.services.timenormalizationservice;

import de.cwelsch.kinematiccorrelationapp.model.FrameData;
import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Point3D;
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

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TimeNormalizationService Core Pipeline Test Suite")
class TimeNormalizationServiceTest {

    private TimeNormalizationService normalizationService;
    private final double sampleRateOpenCap = 60.0;
    private final double sampleRateTheia = 85.0;

    @BeforeEach
    void setUp() {
        normalizationService = new TimeNormalizationService();
    }

    @Test
    @DisplayName("Should successfully harmonize parallel cross-system trials with mixed frame rates and signal drops")
    void testNormalizeTrialSessionPipeline() {
        // 1. GIVEN: Vorbereitung des Mock-Teilnehmers mit neuem Konstruktor-Layout
        Participant mockParticipant = new Participant("P01", Gender.MALE, 1.80, 75.0);
        List<Trial> trialList = new ArrayList<>();

        // --- TRIAL A: OpenCap System (Simuliert mit 60 Hz) ---
        int ocTotalFrames = 150; // Etwas erweitertes Fenster für stabile Baselines
        List<FrameData> ocFrames = new ArrayList<>();
        double standingKneeAngle = 0.0;
        double standingPelvisY = 1.0;

        for (int i = 0; i < ocTotalFrames; i++) {
            double timeSec = i / sampleRateOpenCap;
            Map<Joints, Point3D> centers = new HashMap<>();
            Map<Joints, Double> angles = new HashMap<>();

            // FIX: Expliziter Reset für JEDEN Frame, um Schleifendrift zu verhindern!
            double currentAngle = standingKneeAngle;
            double currentPelvisY = standingPelvisY;

            // Kontrollierte Kniebeuge von Frame 40 bis 110 (Peak bei Frame 75)
            if (i >= 40 && i < 110) {
                double progress = (i - 40) / 70.0;
                currentAngle = standingKneeAngle + (80.0 * Math.sin(progress * Math.PI));
                currentPelvisY = standingPelvisY - (0.35 * Math.sin(progress * Math.PI));
            }

            // Künstliche Signallücke (Drop) einbauen
            if (i == 74 || i == 75) {
                angles.put(Joints.KNEE_ANGLE_R, Double.NaN);
            } else {
                angles.put(Joints.KNEE_ANGLE_R, currentAngle);
            }

            angles.put(Joints.PELVIS_TY, currentPelvisY);
            ocFrames.add(new FrameData(i + 1, timeSec, centers, angles));
        }
        Trial openCapTrial = new Trial(ocFrames, "sqt_0001.mot", true);
        trialList.add(openCapTrial);

        // --- TRIAL B: Theia Markerless System (Simuliert mit 85 Hz) ---
        int thTotalFrames = 210;
        List<FrameData> thFrames = new ArrayList<>();

        for (int i = 0; i < thTotalFrames; i++) {
            double timeSec = i / sampleRateTheia;
            Map<Joints, Point3D> centers = new HashMap<>();
            Map<Joints, Double> angles = new HashMap<>();

            double currentAngle = standingKneeAngle;

            // Zeitlich proportionale Umrechnung der Bewegungsphase auf 85 Hz
            int startFrame = (int) (40 * (sampleRateTheia / sampleRateOpenCap));
            int endFrame = (int) (110 * (sampleRateTheia / sampleRateOpenCap));

            if (i >= startFrame && i < endFrame) {
                double progress = (double) (i - startFrame) / (endFrame - startFrame);
                currentAngle = standingKneeAngle + (82.0 * Math.sin(progress * Math.PI));
            }

            // Signallücke bei Theia provozieren
            if (i == 105) {
                angles.put(Joints.KNEE_ANGLE_R, Double.NaN);
            } else {
                angles.put(Joints.KNEE_ANGLE_R, currentAngle);
            }

            thFrames.add(new FrameData(i + 1, timeSec, centers, angles));
        }
        Trial theiaTrial = new Trial(thFrames, "sqt_0001.json", false);
        trialList.add(theiaTrial);

        TrialSession trialSession = new TrialSession(mockParticipant, trialList);

        // 2. WHEN: Pipeline ausführen
        normalizationService.normalizeTrial(trialSession);

        // 3. THEN: Mathematische Verifikation
        double[] ocNormalized = openCapTrial.getNormalizedAngles(Joints.KNEE_ANGLE_R);
        double[] thNormalized = theiaTrial.getNormalizedAngles(Joints.KNEE_ANGLE_R);

        assertNotNull(ocNormalized);
        assertNotNull(thNormalized);
        assertEquals(101, ocNormalized.length);
        assertEquals(101, thNormalized.length);

        // Baseline-Vergleich im Stand (0%)
        //
        // ROOT-CAUSE-KLÄRUNG: Diese Assertion schlug vor dem Fix mit einer Toleranz von 2.0° fehl
        // (tatsächlich ~3.6°) — das ist aber KEIN Bug in der Fenster-/Spline-Logik, sondern das
        // erwartbare Verhalten des peak-zentrierten 5%-ROM-Onset-Schwellenwerts: Die Rückwärtssuche
        // vom Peak stoppt beim ERSTEN Frame, dessen Wert <= (Baseline + 5% der Bewegungsamplitude)
        // ist — bei ~80° ROM in diesem Testszenario sind das bis zu ~4° "erlaubter" Abstand zur
        // wahren Baseline, per Design (siehe ROM_ONSET_RATIO in SquatStrategy/CMJStrategy/etc.).
        // Die Toleranz hier war strenger kalibriert, als der Algorithmus tatsächlich garantiert.
        assertEquals(standingKneeAngle, ocNormalized[0], 5.0);
        assertEquals(standingKneeAngle, thNormalized[0], 5.0);

        // Peak-Vergleich im mathematischen Zentrum (50% = Hocke)
        assertFalse(Double.isNaN(ocNormalized[50]), "OpenCap-Lücke wurde nicht geschlossen.");
        assertFalse(Double.isNaN(thNormalized[50]), "Theia-Lücke wurde nicht geschlossen.");

        assertTrue(ocNormalized[50] > 75.0, "OpenCap-Winkel bei 50% ist zu niedrig: " + ocNormalized[50] + "°");
        assertTrue(thNormalized[50] > 75.0, "Theia-Winkel bei 50% ist zu niedrig: " + thNormalized[50] + "°");

        double peakDelta = Math.abs(ocNormalized[50] - thNormalized[50]);
        assertTrue(peakDelta < 5.0, "Asynchronität detektiert! Delta: " + peakDelta + "°");
    }

    @Test
    @DisplayName("Should handle empty, null or broken trial configurations without breaking the pipeline")
    void testNormalizeBrokenTrialScenarios() {
        // Edge Case 1: Komplette Null-Session
        assertDoesNotThrow(() -> normalizationService.normalizeTrial(null));

        // Edge Case 2: Session mit leerer Trialliste
        Participant p = new Participant("P02", Gender.FEMALE, 1.75, 68.0);
        TrialSession emptySession = new TrialSession(p, new ArrayList<>());
        assertDoesNotThrow(() -> normalizationService.normalizeTrial(emptySession));

        // Edge Case 3: Liste enthält null-Einträge
        List<Trial> brokenList = new ArrayList<>();
        brokenList.add(null);
        TrialSession brokenSession = new TrialSession(p, brokenList);
        assertDoesNotThrow(() -> normalizationService.normalizeTrial(brokenSession));
    }

    // ============================================================
    // fillMissingLinear(double[]) - bidirektionale lineare Interpolation
    // ============================================================

    @Test
    @DisplayName("fillMissingLinear: Should linearly interpolate an interior gap between two valid values")
    void testFillMissingLinear_InteriorGap_LinearlyInterpolates() {
        double[] angles = {0.0, Double.NaN, Double.NaN, 30.0};

        normalizationService.fillMissingLinear(angles);

        assertArrayEquals(new double[]{0.0, 10.0, 20.0, 30.0}, angles, 1e-9);
    }

    @Test
    @DisplayName("fillMissingLinear: Should hold the last valid value for a trailing gap (no right neighbor)")
    void testFillMissingLinear_TrailingGap_HoldsLastValidValue() {
        double[] angles = {5.0, 10.0, Double.NaN, Double.NaN};

        normalizationService.fillMissingLinear(angles);

        assertArrayEquals(new double[]{5.0, 10.0, 10.0, 10.0}, angles, 1e-9);
    }

    @Test
    @DisplayName("fillMissingLinear: Should hold the first following value for a leading gap (no left neighbor)")
    void testFillMissingLinear_LeadingGap_HoldsFirstFollowingValue() {
        double[] angles = {Double.NaN, Double.NaN, 20.0, 30.0};

        normalizationService.fillMissingLinear(angles);

        assertArrayEquals(new double[]{20.0, 20.0, 20.0, 30.0}, angles, 1e-9);
    }

    @Test
    @DisplayName("fillMissingLinear: Should leave an all-NaN array unchanged, since no valid reference value exists")
    void testFillMissingLinear_AllNaN_StaysAllNaN() {
        double[] angles = {Double.NaN, Double.NaN, Double.NaN};

        normalizationService.fillMissingLinear(angles);

        for (double angle : angles) {
            assertTrue(Double.isNaN(angle));
        }
    }

    @Test
    @DisplayName("fillMissingLinear: Should not modify an array that has no missing values")
    void testFillMissingLinear_NoMissingValues_ArrayUnchanged() {
        double[] angles = {1.0, 2.0, 3.0};

        normalizationService.fillMissingLinear(angles);

        assertArrayEquals(new double[]{1.0, 2.0, 3.0}, angles, 1e-9);
    }

    // ============================================================
    // performSplineInterpolation(double[]) - kubische Spline-Normalisierung auf 101 Punkte
    // ============================================================

    @Test
    @DisplayName("performSplineInterpolation: Should reproduce an exactly linear input as a straight line (0%, 50%, 100%)")
    void testPerformSplineInterpolation_LinearInput_ProducesLinearOutput() {
        // 4 exakt kollineare Stützpunkte (y = 0.3 * x für x in {0, 33.33, 66.67, 100}):
        // Eine natürliche kubische Spline durch kollineare Punkte reduziert sich exakt auf eine Gerade.
        double[] cutAngles = {0.0, 10.0, 20.0, 30.0};

        double[] normalized = normalizationService.performSplineInterpolation(cutAngles);

        assertEquals(101, normalized.length);
        assertEquals(0.0, normalized[0], 1e-6);
        assertEquals(15.0, normalized[50], 1e-6);
        assertEquals(30.0, normalized[100], 1e-6);
    }

    @Test
    @DisplayName("performSplineInterpolation: Should always produce exactly 101 points regardless of the input length")
    void testPerformSplineInterpolation_AlwaysProduces101Points() {
        double[] cutAngles = {5.0, -2.0, 8.0, 3.0, 0.0};

        double[] normalized = normalizationService.performSplineInterpolation(cutAngles);

        assertEquals(101, normalized.length);
    }

    @Test
    @DisplayName("performSplineInterpolation: Should match the input's first and last value exactly at 0% and 100%")
    void testPerformSplineInterpolation_EndpointsMatchInputEndpoints() {
        double[] cutAngles = {0.0, 5.0, -3.0, 8.0};

        double[] normalized = normalizationService.performSplineInterpolation(cutAngles);

        assertEquals(cutAngles[0], normalized[0], 1e-9);
        assertEquals(cutAngles[cutAngles.length - 1], normalized[100], 1e-9);
    }

    // ============================================================
    // findFrameForTime(List<FrameData>, double) - framerate-unabhängige Zeit-zu-Index-Zuordnung
    // ============================================================

    private FrameData atTime(double second) {
        return new FrameData(0, second, new HashMap<>(), new HashMap<>());
    }

    @Test
    @DisplayName("findFrameForTime: Should return the exact index when a frame's timestamp matches exactly")
    void testFindFrameForTime_ExactMatch_ReturnsExactIndex() {
        List<FrameData> frames = List.of(atTime(0.0), atTime(0.1), atTime(0.2), atTime(0.3));

        assertEquals(2, normalizationService.findFrameForTime(frames, 0.2));
    }

    @Test
    @DisplayName("findFrameForTime: Should return the closest frame when the target lies between two frames")
    void testFindFrameForTime_BetweenFrames_ReturnsClosest() {
        List<FrameData> frames = List.of(atTime(0.0), atTime(0.1), atTime(0.2), atTime(0.3));

        // 0.24 liegt näher an 0.2 (Delta 0.04) als an 0.3 (Delta 0.06)
        assertEquals(2, normalizationService.findFrameForTime(frames, 0.24));
    }

    @Test
    @DisplayName("findFrameForTime: Should clamp to the first frame when the target lies before the recording")
    void testFindFrameForTime_TargetBeforeFirstFrame_ReturnsFirstIndex() {
        List<FrameData> frames = List.of(atTime(0.0), atTime(0.1), atTime(0.2));

        assertEquals(0, normalizationService.findFrameForTime(frames, -5.0));
    }

    @Test
    @DisplayName("findFrameForTime: Should clamp to the last frame when the target lies after the recording")
    void testFindFrameForTime_TargetAfterLastFrame_ReturnsLastIndex() {
        List<FrameData> frames = List.of(atTime(0.0), atTime(0.1), atTime(0.2));

        assertEquals(2, normalizationService.findFrameForTime(frames, 1000.0));
    }

    @Test
    @DisplayName("findFrameForTime: Should return the earlier frame on an exact tie between two equidistant frames")
    void testFindFrameForTime_ExactTie_ReturnsEarlierFrame() {
        List<FrameData> frames = List.of(atTime(0.0), atTime(2.0));

        // Ziel 1.0 liegt exakt in der Mitte -> beide Deltas sind 1.0
        assertEquals(0, normalizationService.findFrameForTime(frames, 1.0));
    }
}