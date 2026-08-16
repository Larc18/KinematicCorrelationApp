package de.cwelsch.kinematiccorrelationapp.services.timenormalizationservice.strategy;

import de.cwelsch.kinematiccorrelationapp.model.FrameData;
import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Point3D;
import de.cwelsch.kinematiccorrelationapp.services.timenormalizationservice.strategy.LLLStrategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LLLStrategy Kinematic Gating Test Suite")
class LLLStrategyTest {

    private LLLStrategy lllStrategy;
    private final double sampleRate = 85.0; // 85 Hz Biomechanik-Standard

    @BeforeEach
    void setUp() {
        lllStrategy = new LLLStrategy();
    }

    @Test
    @DisplayName("Should successfully detect lateral lunge left window using Left Knee Angle")
    void testFindWindowViaLeftKnee() {
        // GIVEN: Ein künstlicher LLL-Datensatz (Linkes Knie ist das aktive Arbeitsbein)
        List<FrameData> frames = new ArrayList<>();
        int totalFrames = 220;
        double standingKneeAngle = 0.0; // Gestrecktes Bein im Stand (0° Beugung)

        for (int i = 0; i < totalFrames; i++) {
            double timeInSeconds = i / sampleRate;
            Map<Joints, Point3D> centers = new HashMap<>();
            Map<Joints, Double> angles = new HashMap<>();

            double currentKnee = standingKneeAngle;

            // Seitlicher Ausfallschritt nach links von Frame 45 bis 165 (Peak bei Frame 105)
            if (i >= 45 && i < 165) {
                double progress = (i - 45) / 120.0;
                // Das linke Knie beugt sich tief in der Abfederungsphase (bis zu 85° Flexion)
                currentKnee = standingKneeAngle + (85.0 * Math.sin(progress * Math.PI));
            }

            angles.put(Joints.KNEE_ANGLE_L, currentKnee);
            frames.add(new FrameData(i + 1, timeInSeconds, centers, angles));
        }

        // WHEN: Gating-Fenster berechnen
        MovementWindow window = lllStrategy.findMovementWindow(frames);

        // THEN: Phasenbezogene Grenzen validieren
        assertNotNull(window);
        assertFalse(window.fallbackUsed(), "Ein klar erkennbarer LLL darf nicht auf den Fallback zurückfallen");
        assertTrue(window.startSeconds() >= 0.0, "Startzeitpunkt darf nicht negativ sein");
        assertTrue(window.startSeconds() < window.endSeconds(), "Startzeitpunkt muss vor dem Endzeitpunkt liegen");

        // Da die Bewegung bei Frame 45 startet und die Strategie einen 5-Frame-Puffer abzieht (45 - 5 = 40),
        // muss der berechnete Startzeitpunkt präzise ab Frame 40 liegen.
        double expectedMinStart = (45.0 - 6.0) / sampleRate;
        assertTrue(window.startSeconds() >= expectedMinStart, "Start-Gating hat unbegründet zu früh angeschlagen");

        // Das Ende (Rückkehr zur Baseline < 5°) sollte um Frame 165 liegen
        assertTrue(window.endSeconds() > (155.0 / sampleRate), "End-Gating hat die konzentrische Streckphase zu früh abgeschnitten");
        assertTrue(window.endSeconds() <= (175.0 / sampleRate), "End-Gating hat den Übergang in den stabilen Stand verpasst");
    }

    @Test
    @DisplayName("Should gracefully fall back to full file boundaries on insufficient data")
    void testFallbackScenarios() {
        // Fall 1: Übergabe von null fangen
        MovementWindow nullWindow = lllStrategy.findMovementWindow(null);
        assertEquals(new MovementWindow(0.0, 0.0, true), nullWindow);

        // Fall 2: Zu kurze Aufnahmedauer (weniger als 10 Frames)
        List<FrameData> shortList = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            shortList.add(new FrameData(i + 1, i * 0.1, new HashMap<>(), new HashMap<>()));
        }
        MovementWindow shortWindow = lllStrategy.findMovementWindow(shortList);
        assertTrue(shortWindow.fallbackUsed());
        assertEquals(0.0, shortWindow.startSeconds());
        assertEquals(0.6, shortWindow.endSeconds(), 0.0001);

        // Fall 3: Ausreichend Frames, aber der master-relevante Kanal KNEE_ANGLE_L fehlt komplett
        List<FrameData> missingChannelList = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Map<Joints, Double> angles = new HashMap<>();
            angles.put(Joints.KNEE_ANGLE_R, 12.0); // Rechtes Knie nützt uns bei LLL nichts
            missingChannelList.add(new FrameData(i + 1, i * 0.02, new HashMap<>(), angles));
        }
        MovementWindow missingChannelWindow = lllStrategy.findMovementWindow(missingChannelList);
        assertTrue(missingChannelWindow.fallbackUsed());
        assertEquals(0.0, missingChannelWindow.startSeconds());
        assertEquals(49 * 0.02, missingChannelWindow.endSeconds(), 0.0001);
    }
}