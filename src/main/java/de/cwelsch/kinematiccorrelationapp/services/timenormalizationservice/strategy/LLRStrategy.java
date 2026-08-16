package de.cwelsch.kinematiccorrelationapp.services.timenormalizationservice.strategy;

import de.cwelsch.kinematiccorrelationapp.model.FrameData;
import de.cwelsch.kinematiccorrelationapp.model.Joints;

import java.util.List;

/**
 * Refactored Kinematic Gating Strategy for Lateral Lunge Right (Seitlicher Ausfallschritt rechts).
 * Employs peak-centered relative ROM thresholds (5% onset/offset rule) on the active working leg
 * (right knee) to guarantee rate-independent and noise-robust window synchronization.
 */
public class LLRStrategy implements MovementStrategy {

    private static final double ROM_ONSET_RATIO = 0.05; // 5% Schwellenwert der Beuge-Amplitude

    /**
     * Detects the lateral-lunge-right movement window by locating the deepest right-knee flexion
     * (bottom frame) and expanding outward until the knee angle drops below a 5%-of-ROM threshold
     * above baseline. Falls back to the entire trial if no plausible peak, ROM, or window boundary is found.
     *
     * @param frames the sequential tracking data points collected during the trial recording
     * @return the detected movement window, or a fallback window covering the entire trial
     */
    @Override
    public MovementWindow findMovementWindow(List<FrameData> frames) {
        if (frames == null || frames.size() < 10) {
            return fallback(frames);
        }

        // Überprüfung, ob das rechte Knie als primäres Signalband im Datensatz existiert
        boolean hasKneeR = frames.stream()
                .anyMatch(f -> f.jointAngle().get(Joints.KNEE_ANGLE_R) != null);

        if (!hasKneeR) {
            return fallback(frames);
        }

        // 1. Tiefpunkt (Amortisationsphase / max. Flexion des rechten Beins) ermitteln
        int bottomFrame = -1;
        double maxKneeAngle = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < frames.size(); i++) {
            Double currentKnee = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_R);
            if (currentKnee != null && !Double.isNaN(currentKnee) && currentKnee > maxKneeAngle) {
                maxKneeAngle = currentKnee;
                bottomFrame = i;
            }
        }

        if (bottomFrame == -1 || maxKneeAngle < 15.0) {
            return fallback(frames);
        }

        // 2. Stand-Baseline bestimmen (Erste 5% der Aufzeichnung)
        int baselineRange = Math.max(3, (int) (frames.size() * 0.05));
        double baselineKneeAngle = 0.0;
        int validKneeCount = 0;

        for (int i = 0; i < baselineRange; i++) {
            Double kneeVal = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_R);
            if (kneeVal != null && !Double.isNaN(kneeVal)) {
                baselineKneeAngle += kneeVal;
                validKneeCount++;
            }
        }
        baselineKneeAngle = validKneeCount > 0 ? baselineKneeAngle / validKneeCount : 0.0;

        // 3. Bewegungsamplitude (ROM) und Schwellenwert berechnen
        double totalROM = maxKneeAngle - baselineKneeAngle;
        if (totalROM < 10.0) {
            return fallback(frames);
        }
        double thresholdAngle = baselineKneeAngle + (totalROM * ROM_ONSET_RATIO);

        // 4. Startpunkt (Rückwärtssuche vom Tiefpunkt bis zum Unterschreiten des Schwellenwerts)
        int startFrame = 0;
        for (int i = bottomFrame; i >= 0; i--) {
            Double currentKnee = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_R);
            if (currentKnee != null && !Double.isNaN(currentKnee) && currentKnee <= thresholdAngle) {
                startFrame = i;
                break;
            }
        }

        // 5. Endpunkt (Vorwärtssuche vom Tiefpunkt bis zur Rückkehr unter den Schwellenwert)
        int endFrame = frames.size() - 1;
        for (int i = bottomFrame; i < frames.size(); i++) {
            Double currentKnee = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_R);
            if (currentKnee != null && !Double.isNaN(currentKnee) && currentKnee <= thresholdAngle) {
                endFrame = i;
                break;
            }
        }

        // Plausibilitäts-Guard
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