package de.cwelsch.kinematiccorrelationapp.services.timenormalizationservice.strategy;

import de.cwelsch.kinematiccorrelationapp.model.FrameData;
import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Point3D;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BBRStrategy Kinematic Gating Test Suite")
class BRRStrategyTest {

    private BBRStrategy bbrStrategy;
    private final double sampleRate = 85.0; // 85 Hz Standardfrequenz

    @BeforeEach
    void setUp() {
        bbrStrategy = new BBRStrategy();
    }

    @Test
    @DisplayName("Should successfully detect rotational window using Lumbar Rotation channel")
    void testFindWindowViaLumbarRotation() {
        // GIVEN: Ein künstlicher BRR-Datensatz (Wechselkurve: neutral -> links -> rechts -> neutral)
        List<FrameData> frames = new ArrayList<>();
        int totalFrames = 240;
        double baselineAngle = 0.0; // Neutrale Ausgangsposition bei 0°

        for (int i = 0; i < totalFrames; i++) {
            double timeInSeconds = i / sampleRate;
            Map<Joints, Point3D> centers = new HashMap<>();
            Map<Joints, Double> angles = new HashMap<>();

            double currentRotation = baselineAngle;

            // Rotation startet bei Frame 40 und endet bei Frame 180
            if (i >= 40 && i < 180) {
                // Wir modellieren eine vollständige Sinuswelle, um die Links-Rechts-Kombination abzubilden
                // i = 40 bis 110 (Linksphase, negative Gradzahlen bis -30°)
                // i = 110 bis 180 (Rechtsphase, positive Gradzahlen bis +30°)
                double progress = (i - 40) / 140.0;
                currentRotation = baselineAngle - (35.0 * Math.sin(progress * 2.0 * Math.PI));
            }

            angles.put(Joints.LUMBAR_ROTATION, currentRotation);
            frames.add(new FrameData(i + 1, timeInSeconds, centers, angles));
        }

        // WHEN: Gating-Fenster berechnen
        double[] window = bbrStrategy.findMovementWindowSeconds(frames);

        // THEN: Validierung der Phasenübergänge
        assertNotNull(window);
        assertEquals(2, window.length);
        assertTrue(window[0] >= 0.0, "Startzeitpunkt darf nicht negativ sein");
        assertTrue(window[0] < window[1], "Start-Frame muss vor dem End-Frame liegen");

        // Bewegung startet bei Frame 40. Mit dem 5-Frame-Vorlauf-Puffer (40 - 5 = 35) erwarten wir den Start ab Frame 35
        double expectedMinStart = (40.0 - 6.0) / sampleRate;
        assertTrue(window[0] >= expectedMinStart, "Start-Gating hat unbegründet vor dem Bewegungsonset angeschlagen");

        // Das Ende (Rückkehr zur Baseline < 5° nach dem zweiten Peak) sollte um Frame 180 detektiert werden
        assertTrue(window[1] > (165.0 / sampleRate), "End-Gating hat die Rückrotationsphase zu früh abgeschnitten");
        assertTrue(window[1] <= (195.0 / sampleRate), "End-Gating hat den Übergang in die finale Ruhephase verpasst");
    }

    @Test
    @DisplayName("Should successfully detect rotational window using Pelvis Rotation fallback")
    void testFindWindowViaPelvisRotationFallback() {
        // GIVEN: Ein Datensatz OHNE Lumbar-Rotation, aber mit PELVIS_ROTATION (Validierung des Pfad-Fallbacks)
        List<FrameData> frames = new ArrayList<>();
        int totalFrames = 200;
        double baselineAngle = 0.0;

        for (int i = 0; i < totalFrames; i++) {
            double timeInSeconds = i / sampleRate;
            Map<Joints, Point3D> centers = new HashMap<>();
            Map<Joints, Double> angles = new HashMap<>();

            double currentRotation = baselineAngle;

            // Rotation von Frame 35 bis 155
            if (i >= 35 && i < 155) {
                double progress = (i - 35) / 120.0;
                currentRotation = baselineAngle - (25.0 * Math.sin(progress * 2.0 * Math.PI));
            }

            angles.put(Joints.PELVIS_ROTATION, currentRotation);
            frames.add(new FrameData(i + 1, timeInSeconds, centers, angles));
        }

        // WHEN: Gating-Fenster über Pelvis-Fallback berechnen
        double[] window = bbrStrategy.findMovementWindowSeconds(frames);

        // THEN: Validierung
        assertNotNull(window);
        assertEquals(2, window.length);
        assertTrue(window[0] < window[1]);
        assertTrue(window[0] >= (30.0 / sampleRate), "Pelvis-Fallback-Start zu früh berechnet");
        assertTrue(window[1] > (145.0 / sampleRate), "Pelvis-Fallback-Ende hat Rückrotation abgeschnitten");
    }

    @Test
    @DisplayName("Should gracefully fall back to full file boundaries on insufficient data entries")
    void testFallbackScenarios() {
        // Fall 1: Übergabe von null abfangen
        double[] nullWindow = bbrStrategy.findMovementWindowSeconds(null);
        assertArrayEquals(new double[]{0.0, 0.0}, nullWindow);

        // Fall 2: Zu kurze Aufnahmedauer (weniger als 10 Frames für die Baseline)
        List<FrameData> shortList = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            shortList.add(new FrameData(i + 1, i * 0.1, new HashMap<>(), new HashMap<>()));
        }
        double[] shortWindow = bbrStrategy.findMovementWindowSeconds(shortList);
        assertEquals(0.0, shortWindow[0]);
        assertEquals(0.6, shortWindow[1], 0.0001);

        // Fall 3: Ausreichend Frames, aber kein einziger rotatorischer Kanal vorhanden
        List<FrameData> missingChannelsList = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            Map<Joints, Double> angles = new HashMap<>();
            angles.put(Joints.KNEE_ANGLE_R, 45.0); // Kniewinkel nützt bei Rumpfrotation nichts
            missingChannelsList.add(new FrameData(i + 1, i * 0.02, new HashMap<>(), angles));
        }
        double[] missingChannelsWindow = bbrStrategy.findMovementWindowSeconds(missingChannelsList);
        assertEquals(0.0, missingChannelsWindow[0]);
        assertEquals(59 * 0.02, missingChannelsWindow[1], 0.0001);
    }
}