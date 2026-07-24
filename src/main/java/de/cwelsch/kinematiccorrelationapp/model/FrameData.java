package de.cwelsch.kinematiccorrelationapp.model;

import java.util.*;


/**
 * FrameData holds the information of a frame.
 *
 * @param frame count of the current frame.
 * @param second count of the current second.
 * @param jointCenters map of 3D-coordinates for every joint.
 * @param jointAngle map of angles for every joint.
 */
public record FrameData(int frame, double second, Map<Joints, Point3D> jointCenters, Map<Joints, Double> jointAngle) {

    /**
     * toString() creates a String with the information of the frame. Including join centers and joint angles.
     *
     * @return String with FrameData
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  [Frame %d | Zeit: %.4fs]\n", frame, second));

        /*
        // output of all joint centers
        sb.append("    -> Gelenkzentren:\n");
        if (jointCenters == null || jointCenters.isEmpty()) {
            sb.append("       (keine Daten)\n");
        } else {
            // build logic
        }
        */

        // output of joint angles
        sb.append("    -> Gelenkwinkel:\n");
        if (jointAngle == null || jointAngle.isEmpty()) {
            sb.append("       (keine Daten)\n");
        } else {
            Set<Joints> printedJoints = new HashSet<>();

            // structured output order of the joints
            Joints[] orderedJoints = {
                    // core & hip
                    Joints.PELVIS_TX, Joints.PELVIS_TY, Joints.PELVIS_TZ,
                    Joints.PELVIS_TILT, Joints.PELVIS_LIST, Joints.PELVIS_ROTATION,
                    Joints.LUMBAR_EXTENSION, Joints.LUMBAR_BENDING, Joints.LUMBAR_ROTATION,

                    // lower extremities (right)
                    Joints.HIP_FLEXION_R, Joints.HIP_ADDUCTION_R, Joints.HIP_ROTATION_R,
                    Joints.KNEE_ANGLE_R, Joints.ANKLE_ANGLE_R, Joints.SUBTALAR_ANGLE_R, Joints.MTP_ANGLE_R,

                    // lower extremities (left)
                    Joints.HIP_FLEXION_L, Joints.HIP_ADDUCTION_L, Joints.HIP_ROTATION_L,
                    Joints.KNEE_ANGLE_L, Joints.ANKLE_ANGLE_L, Joints.SUBTALAR_ANGLE_L, Joints.MTP_ANGLE_L,

                    // upper extremities (right)
                    Joints.ARM_FLEX_R, Joints.ARM_ADDUCTION_R, Joints.ARM_ROTATION_R,
                    Joints.ELBOW_FLEX_R, Joints.PRO_SUP_R,

                    // upper extremities (left)
                    Joints.ARM_FLEX_L, Joints.ARM_ADDUCTION_L, Joints.ARM_ROTATION_L,
                    Joints.ELBOW_FLEX_L, Joints.PRO_SUP_L
            };

            // print sorted joints
            for (Joints joint : orderedJoints) {
                if (jointAngle.containsKey(joint)) {
                    Double angle = jointAngle.get(joint);
                    if (angle != null) {
                        // uses displayName for better readability
                        sb.append(String.format("       * %-18s (%-20s) : %.2f°\n",
                                joint.name(), joint.getDisplayName(), angle));
                        printedJoints.add(joint);
                    }
                }
            }

            // saves every joint not printed
            List<Joints> remainingJoints = new ArrayList<>();
            for (Joints joint : jointAngle.keySet()) {
                if (!printedJoints.contains(joint)) {
                    remainingJoints.add(joint);
                }
            }

            if (!remainingJoints.isEmpty()) {
                remainingJoints.sort(Comparator.comparing(Enum::name));
                for (Joints joint : remainingJoints) {
                    Double angle = jointAngle.get(joint);
                    if (angle != null) {
                        sb.append(String.format("       * %-18s (Unsortiert)         : %.2f°\n", joint.name(), angle));
                    }
                }
            }
        }

        return sb.toString();
    }
}