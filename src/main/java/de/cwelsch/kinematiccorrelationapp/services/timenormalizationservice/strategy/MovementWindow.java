package de.cwelsch.kinematiccorrelationapp.services.timenormalizationservice.strategy;

/**
 * Result of a movement-window detection pass.
 *
 * @param startSeconds start of the detected active movement phase, in seconds.
 * @param endSeconds   end of the detected active movement phase, in seconds.
 * @param fallbackUsed true if the strategy could not reliably detect a movement window
 *                     (e.g. amplitude/plausibility guards failed) and fell back to using
 *                     the entire trial recording as the window instead.
 */
public record MovementWindow(double startSeconds, double endSeconds, boolean fallbackUsed) {
}
