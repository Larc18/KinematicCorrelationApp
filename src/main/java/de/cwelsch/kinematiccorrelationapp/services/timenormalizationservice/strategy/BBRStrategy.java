package de.cwelsch.kinematiccorrelationapp.services.timenormalizationservice.strategy;

import de.cwelsch.kinematiccorrelationapp.model.FrameData;
import de.cwelsch.kinematiccorrelationapp.model.Joints;

import java.util.List;

/**
 * Refactored Movement Strategy for Becken-Rumpf-Rotation (BRR).
 * Employs peak-centered relative ROM thresholds on rotation angles (Lumbar/Pelvis Rotation)
 * to capture the full oscillation cycle (neutral -> left rotation -> right rotation -> neutral)
 * with robust, rate-independent cross-system synchronization.
 */
public class BBRStrategy implements MovementStrategy {

    private static final double ROM_ONSET_RATIO = 0.05; // 5% Schwellenwert der Rotations-Amplitude

    @Override
    public double[] findMovementWindowSeconds(List<FrameData> frames) {
        if (frames == null || frames.size() < 10) return fallback(frames);

        boolean hasPelvisRotation = frames.stream()
                .anyMatch(f -> f.jointAngle().get(Joints.PELVIS_ROTATION) != null);
        boolean hasLumbarRotation = frames.stream()
                .anyMatch(f -> f.jointAngle().get(Joints.LUMBAR_ROTATION) != null);

        if (!hasPelvisRotation && !hasLumbarRotation) return fallback(frames);

        // Bevorzuge LUMBAR_ROTATION, da sie die Rumpfbewegung direkter abbildet
        Joints rotationJoint = hasLumbarRotation ? Joints.LUMBAR_ROTATION : Joints.PELVIS_ROTATION;

        // 1. Stand-Baseline bestimmen (Erste 5% der Aufzeichnung)
        int baselineRange = Math.max(3, (int) (frames.size() * 0.05));
        double baselineRotation = 0.0;
        int validCount = 0;

        for (int i = 0; i < baselineRange; i++) {
            Double val = frames.get(i).jointAngle().get(rotationJoint);
            if (val != null && !Double.isNaN(val)) {
                baselineRotation += val;
                validCount++;
            }
        }
        baselineRotation = validCount > 0 ? baselineRotation / validCount : 0.0;

        // 2. Finde den absoluten Amplituden-Peak (entweder maximal ins Negative oder Positive)
        int peakFrame = -1;
        double maxAbsoluteDeviation = 0.0;

        for (int i = 0; i < frames.size(); i++) {
            Double rot = frames.get(i).jointAngle().get(rotationJoint);
            if (rot != null && !Double.isNaN(rot)) {
                double deviation = Math.abs(rot - baselineRotation);
                if (deviation > maxAbsoluteDeviation) {
                    maxAbsoluteDeviation = deviation;
                    peakFrame = i;
                }
            }
        }

        if (peakFrame == -1 || maxAbsoluteDeviation < 5.0) {
            return fallback(frames);
        }

        // 3. Berechne den 5%-Schwellenwert bezüglich der maximalen Rotations-Amplitude
        double thresholdDelta = maxAbsoluteDeviation * ROM_ONSET_RATIO;

        // 4. Startpunkt (Rückwärtssuche vom Peak bis zum Unterschreiten des Schwellenwerts)
        int startFrame = 0;
        for (int i = peakFrame; i >= 0; i--) {
            Double rot = frames.get(i).jointAngle().get(rotationJoint);
            if (rot != null && !Double.isNaN(rot) && Math.abs(rot - baselineRotation) <= thresholdDelta) {
                startFrame = i;
                break;
            }
        }

        // 5. Endpunkt (Vorwärtssuche nach der Gegenrotation bis zur Stabilisierung an der Baseline)
        int endFrame = frames.size() - 1;
        for (int i = peakFrame; i < frames.size(); i++) {
            Double rot = frames.get(i).jointAngle().get(rotationJoint);
            if (rot != null && !Double.isNaN(rot) && Math.abs(rot - baselineRotation) <= thresholdDelta) {
                endFrame = i;
                break;
            }
        }

        if (startFrame >= endFrame) return fallback(frames);
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