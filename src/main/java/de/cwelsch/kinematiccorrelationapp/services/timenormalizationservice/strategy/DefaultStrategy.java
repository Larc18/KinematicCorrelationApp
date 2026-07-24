package de.cwelsch.kinematiccorrelationapp.services.timenormalizationservice.strategy;

import de.cwelsch.kinematiccorrelationapp.model.FrameData;

import java.util.List;

public class DefaultStrategy implements MovementStrategy {

    @Override
    public double[] findMovementWindowSeconds(List<FrameData> frames) {
        if (frames == null || frames.isEmpty()) return new double[]{0.0, 0.0};
        return new double[]{frames.get(0).second(), frames.get(frames.size() - 1).second()};
    }
}