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

@DisplayName("SquatStrategy Kinematic Gating Test Suite")
class SquatStrategyTest {

    private SquatStrategy squatStrategy;
    private final double sampleRate = 85.0; // 85 Hz Standard-Frequenz

    @BeforeEach
    void setUp() {
        squatStrategy = new SquatStrategy();
    }

    @Test
    @DisplayName("Should successfully detect squat window using Knee Angle and Pelvis TY (OpenCap Path)")
    void testFindWindowViaKneeAndPelvis() {
        // GIVEN: Ein künstlicher OpenCap-Squat-Datensatz (Knie beugt sich, Becken sinkt)
        List<FrameData> frames = new ArrayList<>();
        int totalFrames = 200;
        double standingKneeAngle = 0.0;  // 0° Beugung im Stand (nach unserer Transformation)
        double standingPelvisY = 1.0;    // 1 Meter Hüfthöhe im Stand

        for (int i = 0; i < totalFrames; i++) {
            double timeInSeconds = i / sampleRate;
            Map<Joints, Point3D> centers = new HashMap<>();
            Map<Joints, Double> angles = new HashMap<>();

            double currentKnee = standingKneeAngle;
            double currentPelvisY = standingPelvisY;

            // Kniebeuge von Frame 40 bis 150 modellieren (Peak bei Frame 95)
            if (i >= 40 && i < 150) {
                double progress = (i - 40) / 110.0;
                // Knie beugt sich bis auf 90° maximal in der Hocke
                currentKnee = standingKneeAngle + (90.0 * Math.sin(progress * Math.PI));
                // Becken sinkt um 40 cm nach unten ab
                currentPelvisY = standingPelvisY - (0.4 * Math.sin(progress * Math.PI));
            }

            angles.put(Joints.KNEE_ANGLE_R, currentKnee);
            angles.put(Joints.PELVIS_TY, currentPelvisY);
            frames.add(new FrameData(i + 1, timeInSeconds, centers, angles));
        }

        // WHEN: Gating-Fenster berechnen
        double[] window = squatStrategy.findMovementWindowSeconds(frames);

        // THEN: Validierung des Fensters
        assertNotNull(window);
        assertEquals(2, window.length);
        assertTrue(window[0] >= 0.0, "Startzeitpunkt darf nicht negativ sein");
        assertTrue(window[0] < window[1], "Start-Frame muss zeitlich vor dem End-Frame liegen");

        // Der Start wird detektiert, sobald Knie > 5° UND Becken sinkt > 0.015m (kurz nach Frame 40)
        // Wegen des gewollten 5-Frame-Puffers nach vorne (40 - 5 = 35) erlauben wir Werte ab Frame 35
        assertTrue(window[0] >= (35.0 / sampleRate), "Startzeitpunkt liegt zu weit vorne");

        // Das Ende wird detektiert, sobald Knie und Becken wieder nahe der Baseline sind (kurz vor Frame 150)
        assertTrue(window[1] > (140.0 / sampleRate), "Endzeitpunkt hat die Aufwärtsbewegung zu früh abgeschnitten");
        assertTrue(window[1] <= (155.0 / sampleRate), "Endzeitpunkt hat die Stabilisierungsphase nach dem Squat verpasst");
    }

    @Test
    @DisplayName("Should successfully detect squat window using Knee Angle only (Theia Path without Pelvis TY)")
    void testFindWindowViaKneeOnly() {
        // GIVEN: Ein künstlicher Theia-Squat-Datensatz (NUR KNEE_ANGLE_R vorhanden)
        List<FrameData> frames = new ArrayList<>();
        int totalFrames = 180;
        double standingKneeAngle = 0.0;

        for (int i = 0; i < totalFrames; i++) {
            double timeInSeconds = i / sampleRate;
            Map<Joints, Point3D> centers = new HashMap<>();
            Map<Joints, Double> angles = new HashMap<>();

            double currentKnee = standingKneeAngle;

            // Kniebeuge von Frame 35 bis 145 (Peak bei Frame 90)
            if (i >= 35 && i < 145) {
                double progress = (i - 35) / 110.0;
                currentKnee = standingKneeAngle + (85.0 * Math.sin(progress * Math.PI));
            }

            angles.put(Joints.KNEE_ANGLE_R, currentKnee);
            // PELVIS_TY wird hier bewusst weggelassen, um den Theia-Zweig zu testen
            frames.add(new FrameData(i + 1, timeInSeconds, centers, angles));
        }

        // WHEN: Gating-Fenster berechnen
        double[] window = squatStrategy.findMovementWindowSeconds(frames);

        // THEN: Validierung des reinen Knie-Zweigs
        assertNotNull(window);
        assertEquals(2, window.length);
        assertTrue(window[0] < window[1]);

        // Start darf wegen des 5-Frame-Puffers ab Frame 30 liegen (35 - 5 = 30)
        assertTrue(window[0] >= (30.0 / sampleRate), "Knie-Start-Gating zu früh angesprungen");

        // Ende sollte um Frame 145 detektiert werden
        assertTrue(window[1] > (135.0 / sampleRate), "Knie-End-Gating hat die Extension zu früh beendet");
    }

    @Test
    @DisplayName("Should gracefully fall back to full file boundaries on insufficient data")
    void testFallbackScenarios() {
        // Fall 1: Null-Sicherheit
        double[] nullWindow = squatStrategy.findMovementWindowSeconds(null);
        assertArrayEquals(new double[]{0.0, 0.0}, nullWindow);

        // Fall 2: Zu kurze Datenreihe (weniger als 10 Frames)
        List<FrameData> shortList = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            shortList.add(new FrameData(i + 1, i * 0.1, new HashMap<>(), new HashMap<>()));
        }
        double[] shortWindow = squatStrategy.findMovementWindowSeconds(shortList);
        assertEquals(0.0, shortWindow[0]);
        assertEquals(0.5, shortWindow[1], 0.0001);

        // Fall 3: Genug Frames, aber das zwingend erforderliche KNEE_ANGLE_R fehlt komplett
        List<FrameData> missingKneeList = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Map<Joints, Double> angles = new HashMap<>();
            angles.put(Joints.PELVIS_TY, 1.0); // Becken da, aber kein Knie
            missingKneeList.add(new FrameData(i + 1, i * 0.02, new HashMap<>(), angles));
        }
        double[] missingKneeWindow = squatStrategy.findMovementWindowSeconds(missingKneeList);
        assertEquals(0.0, missingKneeWindow[0]);
        assertEquals(39 * 0.02, missingKneeWindow[1], 0.0001);
    }
}