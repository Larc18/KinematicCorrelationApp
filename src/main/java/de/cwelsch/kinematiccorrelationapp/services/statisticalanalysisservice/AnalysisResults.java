package de.cwelsch.kinematiccorrelationapp.services.statisticalanalysisservice;

import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Movement;

import java.util.HashMap;
import java.util.Map;

/**
 * Domain data container encapsulating the aggregated statistical analysis metrics.
 * Stores 101-point arrays of Intraclass Correlation Coefficients (ICC), Root Mean
 * Square Errors (RMSE), and Normalized Root Mean Square Errors (NRMSE) mapped uniquely
 * to specific movement and joint configurations.
 */
public class AnalysisResults {

    /**
     * Composite immutable key record linking a specific movement profile and joint signature.
     * Replaces simple joint mappings to partition statistical outputs across different motor tasks.
     * <p>
     * Note: Java records automatically generate optimal equals() and hashCode() methods natively.
     */
    public record MovementJointKey(Movement movement, Joints joint) {
    }

    private final Map<MovementJointKey, double[]> iccResults = new HashMap<>();
    private final Map<MovementJointKey, double[]> rmseResults = new HashMap<>();
    private final Map<MovementJointKey, double[]> nrmseResults = new HashMap<>();

    /**
     * Registers computed statistical metric arrays for a specific movement and joint matrix pair.
     *
     * @param movement   The active motor task (e.g., SQUAT, COUNTERMOVEMENT_JUMP).
     * @param joint      The target anatomical tracking joint.
     * @param iccArray   The calculated 101-point timeline array of ICC values.
     * @param rmseArray  The calculated 101-point timeline array of RMSE values (in degrees or meters).
     * @param nrmseArray The calculated 101-point timeline array of NRMSE values (in %).
     */
    public void setResultsForJoint(Movement movement, Joints joint, double[] iccArray, double[] rmseArray, double[] nrmseArray) {
        MovementJointKey key = new MovementJointKey(movement, joint);
        iccResults.put(key, iccArray);
        rmseResults.put(key, rmseArray);
        nrmseResults.put(key, nrmseArray);
    }

    /**
     * Retrieves the 101-point ICC array for a specific movement and joint profile.
     *
     * @return The double array of coefficients, or null if no results match the key.
     */
    public double[] getIccForJoint(Movement movement, Joints joint) {
        return iccResults.get(new MovementJointKey(movement, joint));
    }

    /**
     * Retrieves the 101-point RMSE array for a specific movement and joint profile.
     *
     * @return The double array of errors, or null if no results match the key.
     */
    public double[] getRmseForJoint(Movement movement, Joints joint) {
        return rmseResults.get(new MovementJointKey(movement, joint));
    }

    /**
     * Retrieves the 101-point NRMSE array for a specific movement and joint profile.
     *
     * @return The double array of normalized errors (in %), or null if no results match the key.
     */
    public double[] getNrmseForJoint(Movement movement, Joints joint) {
        return nrmseResults.get(new MovementJointKey(movement, joint));
    }

    /**
     * Returns all registered Intraclass Correlation Coefficient (ICC) data maps.
     * Useful for formatting downstream system exports or populating charting UIs.
     */
    public Map<MovementJointKey, double[]> getAllIccResults() {
        return iccResults;
    }

    /**
     * Returns all registered Root Mean Square Error (RMSE) data maps.
     * Useful for formatting downstream system exports or populating charting UIs.
     */
    public Map<MovementJointKey, double[]> getAllRmseResults() {
        return rmseResults;
    }

    /**
     * Returns all registered Normalized Root Mean Square Error (NRMSE) data maps.
     * Useful for formatting downstream system exports or populating charting UIs.
     */
    public Map<MovementJointKey, double[]> getAllNrmseResults() {
        return nrmseResults;
    }
}