package de.cwelsch.kinematiccorrelationapp.services.timenormalizationservice.strategy;

import de.cwelsch.kinematiccorrelationapp.model.FrameData;

import java.util.List;

/**
 * Fallback strategy used for movements without a dedicated detection algorithm (or unrecognized
 * movement names). Treats the entire trial recording as the movement window.
 */
public class DefaultStrategy implements MovementStrategy {

    /**
     * Returns the entire trial recording as the movement window, always flagged as a fallback.
     *
     * @param frames the sequential tracking data points collected during the trial recording
     * @return a window spanning the first to the last frame timestamp, with {@code fallbackUsed} set;
     * {@code (0.0, 0.0, true)} if {@code frames} is null or empty
     */
    @Override
    public MovementWindow findMovementWindow(List<FrameData> frames) {
        if (frames == null || frames.isEmpty()) return new MovementWindow(0.0, 0.0, true);
        return new MovementWindow(frames.get(0).second(), frames.get(frames.size() - 1).second(), true);
    }
}