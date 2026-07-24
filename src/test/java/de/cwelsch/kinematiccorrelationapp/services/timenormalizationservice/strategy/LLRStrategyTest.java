package de.cwelsch.kinematiccorrelationapp.services.timenormalizationservice.strategy;

import de.cwelsch.kinematiccorrelationapp.model.FrameData;
import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Point3D;
import de.cwelsch.kinematiccorrelationapp.services.timenormalizationservice.strategy.LLRStrategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LLRStrategy Kinematic Gating Test Suite")
class LLRStrategyTest {

    private LLRStrategy llrStrategy;
    private final double sampleRate = 85.0; // Standardfrequenz deiner Datenerhebung (85 Hz)

    @BeforeEach
    void setUp() {
        llrStrategy = new LLRStrategy();
    }

    @Test
    @DisplayName("Should successfully detect lateral lunge right window using Right Knee Angle")
    void testFindWindowViaRightKnee() {
        // GIVEN: Ein künstlicher LLR-Datensatz (Rechtes Knie ist das aktive Arbeitsbein)
        List<FrameData> frames = new ArrayList<>();
        int totalFrames = 220;
        double standingKneeAngle = 0.0; // Aufrechter Stand ohne Beugung (0° Flexion)

        for (int i = 0; i < totalFrames; i++) {
            double timeInSeconds = i / sampleRate;
            Map<Joints, Point3D> centers = new HashMap<>();
            Map<Joints, Double> angles = new HashMap<>();

            double currentKnee = standingKneeAngle;

            // Seitlicher Ausfallschritt nach rechts von Frame 45 bis 165 (Tiefpunkt/Hocke bei Frame 105)
            if (i >= 45 && i < 165) {
                double progress = (i - 45) / 120.0;
                // Knieflexion steigt dynamisch bis auf 85° an
                currentKnee = standingKneeAngle + (85.0 * Math.sin(progress * Math.PI));
            }

            angles.put(Joints.KNEE_ANGLE_R, currentKnee);
            frames.add(new FrameData(i + 1, timeInSeconds, centers, angles));
        }

        // WHEN: Gating-Fenster berechnen
        double[] window = llrStrategy.findMovementWindowSeconds(frames);

        // THEN: Validierung der zeitlichen Gating-Grenzen
        assertNotNull(window);
        assertEquals(2, window.length);
        assertTrue(window[0] >= 0.0, "Startzeitpunkt darf nicht negativ sein");
        assertTrue(window[0] < window[1], "Start-Frame muss vor dem End-Frame liegen");

        // Die Bewegung startet bei Frame 45. Abzüglich des 5-Frame-Puffers der Strategie (45 - 5 = 40)
        // darf das Gating frühestens ab Frame 39/40 anspringen.
        double expectedMinStart = (45.0 - 6.0) / sampleRate;
        assertTrue(window[0] >= expectedMinStart, "Start-Gating hat fälschlicherweise zu früh angeschlagen");

        // Die Rückkehr zur gestreckten Baseline (< 5°) wird um Frame 165 erwartet
        assertTrue(window[1] > (155.0 / sampleRate), "End-Gating hat die Streckphase zu früh abgebrochen");
        assertTrue(window[1] <= (175.0 / sampleRate), "End-Gating hat die Stabilisierungsphase verpasst");
    }

    @Test
    @DisplayName("Should gracefully fall back to full file boundaries on insufficient data")
    void testFallbackScenarios() {
        // Fall 1: Null-Sicherheit prüfen
        double[] nullWindow = llrStrategy.findMovementWindowSeconds(null);
        assertArrayEquals(new double[]{0.0, 0.0}, nullWindow);

        // Fall 2: Zu wenige Frames für valide Baseline (weniger als 10 Frames)
        List<FrameData> shortList = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            shortList.add(new FrameData(i + 1, i * 0.1, new HashMap<>(), new HashMap<>()));
        }
        double[] shortWindow = llrStrategy.findMovementWindowSeconds(shortList);
        assertEquals(0.0, shortWindow[0]);
        assertEquals(0.7, shortWindow[1], 0.0001);

        // Fall 3: Ausreichend Frames, aber der master-relevante Kanal KNEE_ANGLE_R fehlt komplett
        List<FrameData> missingChannelList = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Map<Joints, Double> angles = new HashMap<>();
            angles.put(Joints.KNEE_ANGLE_L, 15.0); // Linkes Knie ist beim LLR-Pfad blind
            missingChannelList.add(new FrameData(i + 1, i * 0.02, new HashMap<>(), angles));
        }
        double[] missingChannelWindow = llrStrategy.findMovementWindowSeconds(missingChannelList);
        assertEquals(0.0, missingChannelWindow[0]);
        assertEquals(49 * 0.02, missingChannelWindow[1], 0.0001);
    }
}