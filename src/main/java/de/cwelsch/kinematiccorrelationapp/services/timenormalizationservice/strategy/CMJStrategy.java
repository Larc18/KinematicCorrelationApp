package de.cwelsch.kinematiccorrelationapp.services.timenormalizationservice.strategy;

import de.cwelsch.kinematiccorrelationapp.model.FrameData;
import de.cwelsch.kinematiccorrelationapp.model.Joints;

import java.util.List;

/**
 * Refactored CMJ Window Strategy using peak-centered relative ROM thresholds.
 * Evaluates the full jump cycle from initiation (countermovement onset)
 * through takeoff/flight to landing stabilization based purely on joint kinematics,
 * ensuring robust 1:1 synchronization between OpenCap and Theia.
 */
public class CMJStrategy implements MovementStrategy {

    private static final double ROM_ONSET_RATIO = 0.05; // 5% Schwellenwert der Beuge-Amplitude

    @Override
    public double[] findMovementWindowSeconds(List<FrameData> frames) {
        if (frames == null || frames.size() < 10) {
            return fallback(frames);
        }

        // 1. Suche nach dem ersten Beuge-Peak (Countermovement-Tiefpunkt vor dem Abstoß)
        int countermovementPeakFrame = -1;
        double maxCountermovementKnee = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < frames.size(); i++) {
            Double kneeR = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_R);
            Double kneeL = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_L);
            Double currentVal = (kneeR != null && !Double.isNaN(kneeR)) ? kneeR : kneeL;

            if (currentVal != null && !Double.isNaN(currentVal) && currentVal > maxCountermovementKnee) {
                maxCountermovementKnee = currentVal;
                countermovementPeakFrame = i;
            }
        }

        if (countermovementPeakFrame == -1 || maxCountermovementKnee < 15.0) {
            return fallback(frames);
        }

        // 2. Bestimme die Stand-Baseline (Erste 5% der Frames)
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

        // 3. Bewegungsamplitude (ROM) und 5%-Schwellenwert
        double totalROM = maxCountermovementKnee - baselineAngle;
        if (totalROM < 10.0) {
            return fallback(frames);
        }
        double thresholdAngle = baselineAngle + (totalROM * ROM_ONSET_RATIO);

        // 4. Startpunkt (Rückwärtssuche vor dem Countermovement-Peak)
        int startFrame = 0;
        for (int i = countermovementPeakFrame; i >= 0; i--) {
            Double val = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_R);
            if (val != null && !Double.isNaN(val) && val <= thresholdAngle) {
                startFrame = i;
                break;
            }
        }

        // 5. Suche nach dem zweiten Beuge-Peak (Landung nach der Flugphase)
        int landingPeakFrame = countermovementPeakFrame;
        double maxLandingKnee = Double.NEGATIVE_INFINITY;

        // Suche ab 20 Frames nach dem Countermovement-Peak nach dem Landungs-Flexionspeak
        int searchStart = Math.min(countermovementPeakFrame + 20, frames.size() - 1);
        for (int i = searchStart; i < frames.size(); i++) {
            Double kneeR = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_R);
            Double kneeL = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_L);
            Double currentVal = (kneeR != null && !Double.isNaN(kneeR)) ? kneeR : kneeL;

            if (currentVal != null && !Double.isNaN(currentVal) && currentVal > maxLandingKnee) {
                maxLandingKnee = currentVal;
                landingPeakFrame = i;
            }
        }

        // 6. Endpunkt (Vorwärtssuche nach der Lande-Stabilisierung)
        int endFrame = frames.size() - 1;
        for (int i = landingPeakFrame; i < frames.size(); i++) {
            Double val = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_R);
            if (val != null && !Double.isNaN(val) && val <= thresholdAngle) {
                endFrame = i;
                break;
            }
        }

        if (startFrame >= endFrame) {
            return fallback(frames);
        }

        return new double[]{
                frames.get(startFrame).second(),
                frames.get(endFrame).second()
        };
    }

    private double[] fallback(List<FrameData> frames) {
        if (frames == null || frames.isEmpty()) return new double[]{0.0, 0.0};
        return new double[]{frames.get(0).second(), frames.get(frames.size() - 1).second()};
    }
}