package de.cwelsch.kinematiccorrelationapp.services.timenormalizationservice.strategy;

import de.cwelsch.kinematiccorrelationapp.model.FrameData;
import de.cwelsch.kinematiccorrelationapp.model.Joints;

import java.util.List;

/**
 * Biomechanically validated Squat Window Strategy.
 * Uses peak-centered relative ROM thresholds (5% Onset/Offset rule)
 * to ensure rate-independent and noise-robust movement boundary detection.
 */
public class SquatStrategy implements MovementStrategy {

    private static final double ROM_ONSET_RATIO = 0.05; // 5% Amplitude als Bewegungsgrenze

    /**
     * Detects the squat movement window by locating the deepest knee flexion (peak frame) and
     * expanding outward until the knee angle drops below a 5%-of-ROM threshold above baseline.
     * Falls back to the entire trial if no plausible peak, ROM, or window boundary is found.
     *
     * @param frames the sequential tracking data points collected during the trial recording
     * @return the detected squat movement window, or a fallback window covering the entire trial
     */
    @Override
    public MovementWindow findMovementWindow(List<FrameData> frames) {
        if (frames == null || frames.size() < 10) {
            return fallback(frames);
        }

        // 1. Suche die maximale Kniebeugung (Tiefster Punkt / Bottom Frame)
        int peakFrame = -1;
        double maxKneeAngle = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < frames.size(); i++) {
            Double kneeR = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_R);
            Double kneeL = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_L);

            // Bevorzuge rechtes Knie, nutze links als Fallback
            Double currentVal = (kneeR != null && !Double.isNaN(kneeR)) ? kneeR : kneeL;

            if (currentVal != null && !Double.isNaN(currentVal) && currentVal > maxKneeAngle) {
                maxKneeAngle = currentVal;
                peakFrame = i;
            }
        }

        if (peakFrame == -1 || maxKneeAngle < 20.0) { // Kein gültiger Squat erkennbar
            return fallback(frames);
        }

        // 2. Bestimme den Stand-Baseline-Winkel (Mittelwert der ersten 5% der Aufzeichnung)
        int baselineRange = Math.max(3, (int) (frames.size() * 0.05));
        double baselineAngle = 0.0;
        int validBaselineCount = 0;

        for (int i = 0; i < baselineRange; i++) {
            Double val = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_R);
            if (val != null && !Double.isNaN(val)) {
                baselineAngle += val;
                validBaselineCount++;
            }
        }
        baselineAngle = validBaselineCount > 0 ? baselineAngle / validBaselineCount : 0.0;

        // 3. Berechne die absolute Bewegungsamplitude (ROM)
        double totalROM = maxKneeAngle - baselineAngle;
        if (totalROM < 15.0) { // Zu geringe Amplitude
            return fallback(frames);
        }

        // Schwellenwert für Start & Ende: Baseline + 5% der Amplitude
        double thresholdAngle = baselineAngle + (totalROM * ROM_ONSET_RATIO);

        // 4. Rückwärtssuche vom Peak zum Start (5% Threshold)
        int startFrame = 0;
        for (int i = peakFrame; i >= 0; i--) {
            Double val = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_R);
            if (val != null && !Double.isNaN(val) && val <= thresholdAngle) {
                startFrame = i;
                break;
            }
        }

        // 5. Vorwärtssuche vom Peak zum Ende (5% Threshold)
        int endFrame = frames.size() - 1;
        for (int i = peakFrame; i < frames.size(); i++) {
            Double val = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_R);
            if (val != null && !Double.isNaN(val) && val <= thresholdAngle) {
                endFrame = i;
                break;
            }
        }

        // Sicherheitsprüfungen
        if (startFrame >= endFrame) {
            return fallback(frames);
        }

        return new MovementWindow(
                frames.get(startFrame).second(),
                frames.get(endFrame).second(),
                false
        );
    }

    private MovementWindow fallback(List<FrameData> frames) {
        if (frames == null || frames.isEmpty()) return new MovementWindow(0.0, 0.0, true);
        return new MovementWindow(frames.get(0).second(), frames.get(frames.size() - 1).second(), true);
    }
}