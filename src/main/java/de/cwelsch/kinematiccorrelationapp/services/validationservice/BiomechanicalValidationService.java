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
 * Evaluates motion capture frames for joint-range anomalies (e.g., knee hyperextension)
 * and handles signal tracking discontinuity indicators like high-frequency Gimbal locks and periodic angle flips.
 */
public class BiomechanicalValidationService implements ValidationService<TrialSession> {

    // Threshold catching abrupt frame-to-frame joint angular jumps exceeding realistic human motion constraints
    private static final double MAX_ANGULAR_VELOCITY_PER_FRAME = 35.0;
    private static final double ERROR_RELEASE_THRESHOLD = 5.0;

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
            Set<Joints> activeGimbalErrors = new HashSet<>();

            FrameData previousFrame = null;

            for (FrameData frame : frames) {
                int frameNum = frame.frame();
                double second = frame.second();

                // Check 1: Knee Range-of-Motion (ROM) plausibility analysis
                checkKneePlausibility(frame, report, fileName, frameNum, second, activeKneeErrors);

                // Check 2: Hip flexion/extension plane alignment integrity
                checkHipPlausibility(frame, report, fileName, frameNum, second, activeHipErrors);

                // Check 3: Euler-angle kinematic discontinuities (Gimbal Lock detection on upper extremities)
                if (previousFrame != null) {
                    checkGimbalLockOrSignFlip(frame, previousFrame, report, fileName, frameNum, second, activeGimbalErrors);
                }

                previousFrame = frame;
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

    private void checkGimbalLockOrSignFlip(FrameData current, FrameData previous, ValidationReport report, String fileName, int frameNum, double second, Set<Joints> activeErrors) {
        Joints[] upperJoints = {
                Joints.ARM_FLEX_R, Joints.ARM_FLEX_L,
                Joints.ARM_ROTATION_R, Joints.ARM_ROTATION_L,
                Joints.ARM_ADDUCTION_R, Joints.ARM_ADDUCTION_L,
                Joints.ELBOW_FLEX_R, Joints.ELBOW_FLEX_L,
                Joints.PRO_SUP_R, Joints.PRO_SUP_L
        };

        for (Joints joint : upperJoints) {
            Double currAngle = current.jointAngle().get(joint);
            Double prevAngle = previous.jointAngle().get(joint);

            if (currAngle != null && prevAngle != null && !Double.isNaN(currAngle) && !Double.isNaN(prevAngle)) {

                // CRITICAL FIX: Periodischen Winkelsprung (Wrapping um 360°) korrigieren
                double delta = currAngle - prevAngle;

                // Normalisiere die Winkeldifferenz auf das Intervall [-180, 180]
                while (delta > 180.0)  delta -= 360.0;
                while (delta < -180.0) delta += 360.0;
                delta = Math.abs(delta);

                if (delta > MAX_ANGULAR_VELOCITY_PER_FRAME) {
                    if (!activeErrors.contains(joint)) {
                        report.addWarning(
                                String.format("Potential Gimbal-Lock / coordinate system inversion detected at [%s] (Initial jump delta: %.2f°)", joint.name(), delta),
                                fileName, frameNum, second
                        );
                        activeErrors.add(joint);
                    }
                } else {
                    // Release the state lock once tracking noise stabilizes beneath constraints
                    if (delta < ERROR_RELEASE_THRESHOLD) {
                        activeErrors.remove(joint);
                    }
                }
            }
        }
    }
}