package de.cwelsch.kinematiccorrelationapp.services.statisticalanalysisservice;

import java.util.Arrays;

/**
 * Statistical engine computing the Intraclass Correlation Coefficient (ICC).
 * Implements Shrout and Fleiss (1979) Model 3, single-measure consistency formula: ICC(3,1).
 * Evaluates the absolute consistency of continuous variables across parallel systems.
 */
public class IntraClassCorrelationCoefficient {

    private static final double NUMERICAL_UNDERFLOW_THRESHOLD = 1e-9;

    /**
     * Calculates the ICC(3,1) consistency value for two matched sample distributions.
     *
     * @param targetA Continuous tracking series values from system A (e.g., OpenCap).
     * @param targetB Continuous tracking series values from system B (e.g., Theia).
     * @return The computed consistency coefficient constrained within [-1.0, 1.0],
     * or Double.NaN if the distributions are mismatched or empty.
     */
    public static double calculateICC31(double[] targetA, double[] targetB) {
        if (targetA == null || targetB == null
                || targetA.length != targetB.length
                || targetA.length < 2) {
            return Double.NaN;
        }

        if (Arrays.equals(targetA, targetB)) return 1.0;

        int n = targetA.length;

        // Zeilenmittelwerte und Gesamtmittelwert
        double grandMean = 0;
        double[] rowMeans = new double[n];
        for (int i = 0; i < n; i++) {
            rowMeans[i] = (targetA[i] + targetB[i]) / 2.0;
            grandMean += rowMeans[i];
        }
        grandMean /= n;

        // Spaltenmittelwerte (Rater-Means)
        double meanA = Arrays.stream(targetA).average().orElse(0);
        double meanB = Arrays.stream(targetB).average().orElse(0);

        // Sum of Squares
        double bms = 0; // Between subjects
        for (double rm : rowMeans) {
            bms += (rm - grandMean) * (rm - grandMean);
        }
        bms = (bms * 2) / (n - 1); // *2 weil k=2 Rater, df = n-1

        double ssRaters = n * ((meanA - grandMean) * (meanA - grandMean)
                + (meanB - grandMean) * (meanB - grandMean));

        double ssTotal = 0;
        for (int i = 0; i < n; i++) {
            ssTotal += (targetA[i] - grandMean) * (targetA[i] - grandMean)
                    + (targetB[i] - grandMean) * (targetB[i] - grandMean);
        }

        // EMS: Residual nach Abzug von Between-Subjects und Rater-Effekt
        double ssError = ssTotal - (bms * (n - 1)) - ssRaters;
        double ems = Math.max(0, ssError / (n - 1)); // df_error = (n-1)*(k-1) = n-1 bei k=2

        // ICC(3,1) = (BMS - EMS) / (BMS + EMS)  [k=2, daher kein (k-1)*EMS Zusatzterm]
        double denominator = bms + ems;
        // CRITICAL FIX: BMS+EMS ~ 0 means there is no variance at all in the data (e.g. every
        // subject produced an identical constant value on both systems). That is a degenerate,
        // statistically undefined case — it previously returned 1.0 ("perfect agreement"), which
        // silently overstated reliability. Arrays.equals() above already handles true identical
        // series, so anything reaching here has zero measurable variance to judge agreement by.
        if (denominator < NUMERICAL_UNDERFLOW_THRESHOLD) return Double.NaN;

        return Math.max(-1.0, Math.min(1.0, (bms - ems) / denominator));
    }
}