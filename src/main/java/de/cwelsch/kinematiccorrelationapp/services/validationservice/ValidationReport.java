package de.cwelsch.kinematiccorrelationapp.services.validationservice;

import java.util.ArrayList;
import java.util.List;

/**
 * Domain object acting as an audit tracker for capturing validation states.
 * Collects structural errors and kinematic warnings discovered during file execution checks.
 */
public class ValidationReport {

    private final List<ValidationMessage> errors = new ArrayList<>();
    private final List<ValidationMessage> warnings = new ArrayList<>();

    /**
     * Convenience factory method for registering general pipeline execution errors.
     * * @param message The root description of the failure.
     */
    public void addError(String message) {
        errors.add(new ValidationMessage(message, "General", -1, -1.0));
    }

    /**
     * Registers a targeted data integrity anomaly mapped to a specific timeline index.
     */
    public void addError(String message, String fileName, int frameNumber, double second) {
        errors.add(new ValidationMessage(message, fileName, frameNumber, second));
    }

    /**
     * Convenience factory method for registering global session warning markers.
     * * @param message The root description of the warning threshold.
     */
    public void addWarning(String message) {
        warnings.add(new ValidationMessage(message, "General", -1, -1.0));
    }

    /**
     * Registers a specific mathematical or biomechanical out-of-bounds warning.
     */
    public void addWarning(String message, String fileName, int frameNumber, double second) {
        warnings.add(new ValidationMessage(message, fileName, frameNumber, second));
    }

    public boolean isSuccessful() {
        return errors.isEmpty();
    }

    public List<ValidationMessage> getErrors() {
        return errors;
    }

    public List<ValidationMessage> getWarnings() {
        return warnings;
    }

    @Override
    public String toString() {
        if (errors.isEmpty() && warnings.isEmpty()) {
            return "Validation Report: OK (No data errors or signal anomalies detected.)";
        }

        StringBuilder sb = new StringBuilder("=== VALIDATION REPORT ===\n");
        if (!errors.isEmpty()) {
            sb.append("  ERRORS:\n");
            for (ValidationMessage err : errors) {
                sb.append("    - ").append(err.toString()).append("\n");
            }
        }
        if (!warnings.isEmpty()) {
            sb.append("  WARNINGS:\n");
            for (ValidationMessage warn : warnings) {
                sb.append("    - ").append(warn.toString()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Immutable data record encapsulating structural log context parameters.
     */
    public static class ValidationMessage {
        private final String message;
        private final String fileName;
        private final int frameNumber;
        private final double second;

        public ValidationMessage(String message, String fileName, int frameNumber, double second) {
            this.message = message;
            this.fileName = fileName;
            this.frameNumber = frameNumber;
            this.second = second;
        }

        public String getMessage() { return message; }
        public String getFileName() { return fileName; }
        public int getFrameNumber() { return frameNumber; }
        public double getSecond() { return second; }

        @Override
        public String toString() {
            if (frameNumber == -1) {
                return String.format("[%s] %s", fileName, message);
            }
            // Formats execution time tracking down to a 4-decimal precision baseline
            return String.format("File: %s | Frame: %d | Time: %.4fs -> %s",
                    fileName, frameNumber, second, message);
        }
    }
}