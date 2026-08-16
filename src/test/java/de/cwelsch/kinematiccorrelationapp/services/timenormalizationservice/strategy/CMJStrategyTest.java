package de.cwelsch.kinematiccorrelationapp.services.timenormalizationservice.strategy;

import de.cwelsch.kinematiccorrelationapp.model.FrameData;
import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Point3D;
// Expliziter Import der eigentlichen Strategie aus dem gekapselten Subpackage

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CMJStrategy Kinematic Gating Test Suite")
class CMJStrategyTest {

    private CMJStrategy cmjStrategy;
    private final double sampleRate = 85.0;

    @BeforeEach
    void setUp() {
        cmjStrategy = new CMJStrategy();
    }

    @Test
    @DisplayName("Should successfully detect movement window using Pelvis TY (OpenCap Path)")
    void testFindWindowViaPelvis() {
        List<FrameData> frames = new ArrayList<>();
        int totalFrames = 180;
        double standingHeight = 1.0;

        for (int i = 0; i < totalFrames; i++) {
            double timeInSeconds = i / sampleRate;
            Map<Joints, Point3D> centers = new HashMap<>();
            Map<Joints, Double> angles = new HashMap<>();

            double currentPelvisY = standingHeight;

            // Biomechanisch optimierte Flug- und Landekurve
            if (i >= 35 && i < 60) {
                // 1. Exzentrische Ausholphase (Becken sinkt ab)
                double progress = (i - 35) / 25.0;
                currentPelvisY = standingHeight - (0.25 * Math.sin(progress * Math.PI));
            } else if (i >= 60 && i < 110) {
                // 2. Reine Flugphase: Bleibt bis exakt Frame 109 deutlich über der Baseline
                double progress = (i - 60) / 50.0;
                currentPelvisY = standingHeight + 0.05 + (0.45 * Math.sin(progress * Math.PI));
            } else if (i >= 110 && i < 140) {
                // 3. Landung & Amortisation: Schlägt bei 110 ein und geht SOFORT tief (unter 1.0m)
                // Wir starten direkt mit einer spürbaren Absenkung, damit die Baseline nicht beim Fallen gecrasht wird
                double progress = (i - 110) / 30.0;
                currentPelvisY = (standingHeight - 0.02) - (0.25 * Math.sin(progress * Math.PI));
            } else if (i >= 140) {
                // 4. Stabilisierung zurück in den aufrechten Stand
                currentPelvisY = standingHeight;
            }

            angles.put(Joints.PELVIS_TY, currentPelvisY);
            frames.add(new FrameData(i + 1, timeInSeconds, centers, angles));
        }

        MovementWindow window = cmjStrategy.findMovementWindow(frames);

        assertNotNull(window);
        assertFalse(window.fallbackUsed(), "PELVIS_TY-Pfad muss ein echtes Fenster erkennen, kein Fallback");

        // Grundlegende Toleranzgrenzen prüfen
        assertTrue(window.startSeconds() >= 0.0, "Startzeitpunkt darf nicht negativ sein");
        assertTrue(window.startSeconds() < window.endSeconds(), "Start-Frame muss vor dem End-Frame liegen");

        // Da das Becken bei der Landung sofort unter die Baseline sackt und erst bei Frame 140 wieder hochkommt,
        // findet die Strategie das Ende nun garantiert erst beim echten Auslaufen im Stand (nahe Frame 140)!
        assertTrue(window.endSeconds() > (110.0 / sampleRate), "Endzeitpunkt (" + window.endSeconds() + "s) muss deutlich nach der Flugphase liegen");
    }

    @Test
    @DisplayName("Should successfully detect movement window using Knee Angle (Theia Path)")
    void testFindWindowViaKnee() {
        List<FrameData> frames = new ArrayList<>();
        int totalFrames = 160;
        double standingKneeAngle = 5.0;

        for (int i = 0; i < totalFrames; i++) {
            double timeInSeconds = i / sampleRate;
            Map<Joints, Point3D> centers = new HashMap<>();
            Map<Joints, Double> angles = new HashMap<>();

            double currentKnee = standingKneeAngle;

            if (i >= 30 && i < 55) {
                // Ausholphase (Winkel steigt an bei Kniebeugung)
                double progress = (i - 30) / 25.0;
                currentKnee = standingKneeAngle + (70.0 * Math.sin(progress * Math.PI));
            } else if (i >= 55 && i < 85) {
                // Flugphase (Beine gestreckt in der Luft)
                currentKnee = 2.0;
            } else if (i >= 85 && i < 120) {
                // Lande-Abfederung (Zweiter tiefer Beuge-Peak)
                double progress = (i - 85) / 35.0;
                currentKnee = standingKneeAngle + (65.0 * Math.sin(progress * Math.PI));
            }

            angles.put(Joints.KNEE_ANGLE_R, currentKnee);
            frames.add(new FrameData(i + 1, timeInSeconds, centers, angles));
        }

        MovementWindow window = cmjStrategy.findMovementWindow(frames);

        assertNotNull(window);
        assertFalse(window.fallbackUsed(), "Ein klar erkennbarer CMJ darf nicht auf den Fallback zurückfallen");

        // Erlaube den gewollten 5-Frame-Puffer der Strategie nach vorne (30 - 5 = 25 Frames)
        double minAllowedStart = (30.0 - 6.0) / sampleRate;
        assertTrue(window.startSeconds() >= minAllowedStart, "Gating darf nicht unbegründet vor der Ausholphase starten");
        assertTrue(window.endSeconds() > (110.0 / sampleRate), "Gating muss das Abfangen der Landung mitnehmen");
    }

    @Test
    @DisplayName("Should gracefully fall back to full file boundaries on empty or insufficient inputs")
    void testFallbackScenarios() {
        MovementWindow nullWindow = cmjStrategy.findMovementWindow(null);
        assertEquals(new MovementWindow(0.0, 0.0, true), nullWindow);

        List<FrameData> shortList = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            shortList.add(new FrameData(i + 1, i * 0.1, new HashMap<>(), new HashMap<>()));
        }
        MovementWindow shortWindow = cmjStrategy.findMovementWindow(shortList);
        assertTrue(shortWindow.fallbackUsed());
        assertEquals(0.0, shortWindow.startSeconds());
        assertEquals(0.4, shortWindow.endSeconds(), 0.0001);

        List<FrameData> emptyChannelsList = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            emptyChannelsList.add(new FrameData(i + 1, i * 0.01, new HashMap<>(), new HashMap<>()));
        }
        MovementWindow emptyChannelsWindow = cmjStrategy.findMovementWindow(emptyChannelsList);
        assertTrue(emptyChannelsWindow.fallbackUsed());
        assertEquals(0.0, emptyChannelsWindow.startSeconds());
        assertEquals(0.49, emptyChannelsWindow.endSeconds(), 0.0001);
    }
}