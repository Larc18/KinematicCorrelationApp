package de.cwelsch.kinematiccorrelationapp.services.timenormalizationservice.strategy;

import de.cwelsch.kinematiccorrelationapp.model.FrameData;

import java.util.List;

/**
 * Strategy pattern interface for executing event-detection algorithms.
 * Isolates the functional initiation and termination frame boundaries of an active
 * kinematic movement phase out of a raw, continuous time-series recording sequence.
 */
public interface MovementStrategy {

    /**
     * Analyzes a sequential frame track timeline to identify specific biomechanical events.
     * * @param frames The sequential tracking data points collected during the trial recording.
     * @return An integer array containing exactly two elements: index [0] represents the start frame,
     * and index [1] represents the end frame of the active movement window phase.
     */
    double[] findMovementWindowSeconds(List<FrameData> frames);  // gibt Sekunden zurück
}