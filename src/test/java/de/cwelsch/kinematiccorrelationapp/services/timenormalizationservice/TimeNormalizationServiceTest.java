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
    private final double sampleRateTheia   = 85.0;

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
            int endFrame   = (int) (110 * (sampleRateTheia / sampleRateOpenCap));

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
        assertEquals(standingKneeAngle, ocNormalized[0], 2.0);
        assertEquals(standingKneeAngle, thNormalized[0], 2.0);

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
}