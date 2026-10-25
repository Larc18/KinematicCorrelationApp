package de.cwelsch.kinematiccorrelationapp.services.validationservice;

import de.cwelsch.kinematiccorrelationapp.model.FrameData;
import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Trial;
import de.cwelsch.kinematiccorrelationapp.model.TrialSession;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Concrete validation service responsible for anatomical and physiological data sanity checking.
 * Evaluates motion capture frames for joint-range anomalies (e.g., knee hyperextension, hip
 * hyperextension) within the study's analysis scope (Hüfte/Knie/Sprunggelenk).
 */
public class BiomechanicalValidationService implements ValidationService<TrialSession> {

    /**
     * Checks every frame of every trial in the session for anatomically implausible knee and hip
     * flexion angles, emitting one warning per contiguous out-of-bounds sequence (not per frame).
     *
     * @param trialSession the session whose trials are validated
     * @return a report listing the biomechanical plausibility warnings found
     */
    @Override
    public ValidationReport validate(TrialSession trialSession) {
        System.out.println("Starting BiomechanicalValidationService...");
        ValidationReport report = new ValidationReport();

        if (trialSession == null || trialSession.getTrials() == null || trialSession.getTrials().isEmpty()) {
            return report;
        }

        for (Trial trial : trialSession.getTrials()) {
            if (trial == null || trial.getFrameDataList().isEmpty()) {
                continue;
            }

            String fileName = trial.getSourceFileName() != null ? trial.getSourceFileName() : "Unknown_File";
            List<FrameData> frames = trial.getFrameDataList();

            // Tracking sets for cascading sequence errors (state locks preventing duplicate notifications)
            Set<Joints> activeKneeErrors = new HashSet<>();
            Set<Joints> activeHipErrors = new HashSet<>();
            Set<Joints> activeAnkleErrors = new HashSet<>();

            for (FrameData frame : frames) {
                int frameNum = frame.frame();
                double second = frame.second();

                // Check 1: Knee Range-of-Motion (ROM) plausibility analysis
                checkKneePlausibility(frame, report, fileName, frameNum, second, activeKneeErrors);

                // Check 2: Hip flexion/extension plane alignment integrity
                checkHipPlausibility(frame, report, fileName, frameNum, second, activeHipErrors);

                // Check 3: Ankle dorsi-/plantarflexion plausibility analysis
                checkAnklePlausibility(frame, report, fileName, frameNum, second, activeAnkleErrors);
            }
        }

        System.out.println("Biomechanical Validation complete: " + report);
        return report;
    }

    private void checkKneePlausibility(FrameData frame, ValidationReport report, String fileName, int frameNum, double second, Set<Joints> activeErrors) {
        Joints[] knees = {Joints.KNEE_ANGLE_R, Joints.KNEE_ANGLE_L};
        for (Joints knee : knees) {
            Double angle = frame.jointAngle().get(knee);

            // CRITICAL FIX: null UND NaN abfangen
            if (angle != null && !Double.isNaN(angle)) {
                // Biomechanical bounds: Knee hyperextension under -10° or deep squad flexion over 165°
                boolean isOutOfBounds = (angle < -10.0 || angle > 165.0);

                if (isOutOfBounds) {
                    if (!activeErrors.contains(knee)) {
                        report.addWarning(
                                String.format("Anatomically implausible knee angle detected at [%s]: %.2f° (Error cascade sequence initialized)", knee.name(), angle),
                                fileName, frameNum, second
                        );
                        activeErrors.add(knee);
                    }
                } else {
                    activeErrors.remove(knee);
                }
            }
        }
    }

    private void checkHipPlausibility(FrameData frame, ValidationReport report, String fileName, int frameNum, double second, Set<Joints> activeErrors) {
        Joints[] hipFlexions = {Joints.HIP_FLEXION_R, Joints.HIP_FLEXION_L};
        for (Joints hip : hipFlexions) {
            Double angle = frame.jointAngle().get(hip);

            // CRITICAL FIX: null UND NaN abfangen
            if (angle != null && !Double.isNaN(angle)) {
                // Biomechanical bounds: Hip hyperextension under -30° or maximum flexion compression over 145°
                boolean isOutOfBounds = (angle < -30.0 || angle > 145.0);

                if (isOutOfBounds) {
                    if (!activeErrors.contains(hip)) {
                        report.addWarning(
                                String.format("Anatomically implausible hip flexion angle detected at [%s]: %.2f° (Error cascade sequence initialized)", hip.name(), angle),
                                fileName, frameNum, second
                        );
                        activeErrors.add(hip);
                    }
                } else {
                    activeErrors.remove(hip);
                }
            }
        }
    }

    private void checkAnklePlausibility(FrameData frame, ValidationReport report, String fileName, int frameNum, double second, Set<Joints> activeErrors) {
        Joints[] ankles = {Joints.ANKLE_ANGLE_R, Joints.ANKLE_ANGLE_L};
        for (Joints ankle : ankles) {
            Double angle = frame.jointAngle().get(ankle);

            // CRITICAL FIX: null UND NaN abfangen
            if (angle != null && !Double.isNaN(angle)) {
                // Biomechanical bounds: Plantarflexion beyond -60° or dorsiflexion beyond 35°
                boolean isOutOfBounds = (angle < -60.0 || angle > 35.0);

                if (isOutOfBounds) {
                    if (!activeErrors.contains(ankle)) {
                        report.addWarning(
                                String.format("Anatomically implausible ankle angle detected at [%s]: %.2f° (Error cascade sequence initialized)", ankle.name(), angle),
                                fileName, frameNum, second
                        );
                        activeErrors.add(ankle);
                    }
                } else {
                    activeErrors.remove(ankle);
                }
            }
        }
    }

}