package de.cwelsch.kinematiccorrelationapp.services.validationservice;

/**
 * Universal validation engine contract for data integrity auditing.
 * Enforces a standardized interface for filtering technical data anomalies
 * and biomechanical limit violations across processing stages.
 *
 * @param <T> The target domain type requiring verification (e.g., TrialSession or Trial).
 */
public interface ValidationService<T> {

    /**
     * Executes a comprehensive matrix check on the provided dataset.
     *
     * @param data The tracking entity target to be evaluated for data sanity.
     * @return A detailed validation report containing detected structural anomalies, tracking skips, or warning thresholds.
     */
    ValidationReport validate(T data);
}