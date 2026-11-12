package de.cwelsch.kinematiccorrelationapp.services.statisticalanalysisservice;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.distribution.TDistribution;
import org.apache.commons.math3.util.FastMath;

import java.util.Arrays;

/**
 * Einseitige Prüfung einer Stichprobe gegen einen festen Referenzwert (H0: mu >= Referenz vs.
 * H1: mu < Referenz). Die Normalverteilung wird mit dem Shapiro-Wilk-Test geprüft; ist sie nicht
 * verletzt, gilt der einseitige Einstichproben-t-Test, andernfalls der exakte
 * Wilcoxon-Vorzeichen-Rang-Test.
 */
public final class ReferenceValueTest {

    public static final double ALPHA = 0.05;

    private static final NormalDistribution STANDARD_NORMAL = new NormalDistribution();

    private ReferenceValueTest() {
    }

    /**
     * Ergebnis der Prüfung. Kennwerte, die für die Stichprobe nicht berechenbar sind, sind NaN.
     *
     * @param n          Stichprobengröße
     * @param shapiroW   Shapiro-Wilk-Teststatistik W
     * @param shapiroP   p-Wert des Shapiro-Wilk-Tests
     * @param t          t-Statistik gegen den Referenzwert
     * @param df         Freiheitsgrade des t-Tests
     * @param tP         einseitiger p-Wert des t-Tests
     * @param wilcoxonV  Summe der positiven Ränge
     * @param wilcoxonZ  z-Wert der Normalapproximation von V
     * @param wilcoxonP  exakter einseitiger p-Wert des Wilcoxon-Tests
     * @param wilcoxonR  Effektstärke r = |z| / sqrt(n)
     */
    public record Result(int n, double shapiroW, double shapiroP,
                         double t, int df, double tP,
                         double wilcoxonV, double wilcoxonZ, double wilcoxonP, double wilcoxonR) {

        /** Der Shapiro-Wilk-Test ist nicht signifikant, der t-Test ist also der vorgesehene Test. */
        public boolean normalityAssumed() {
            return shapiroP >= ALPHA;
        }

        /** p-Wert des nach Shapiro-Wilk vorgesehenen Tests. */
        public double selectedP() {
            return normalityAssumed() ? tP : wilcoxonP;
        }

        public boolean nullHypothesisRejected() {
            return selectedP() < ALPHA;
        }
    }

    public static Result test(double[] values, double reference) {
        double[] w = shapiroWilk(values);
        double[] t = oneSampleT(values, reference);
        double[] v = wilcoxonSignedRank(values, reference);
        return new Result(values.length, w[0], w[1], t[0], values.length - 1, t[1], v[0], v[1], v[2], v[3]);
    }

    /** Liefert {t, p} für H1: mu < Referenz. */
    private static double[] oneSampleT(double[] values, double reference) {
        int n = values.length;
        if (n < 2) return new double[]{Double.NaN, Double.NaN};
        double mean = Arrays.stream(values).average().orElse(Double.NaN);
        double sumSq = Arrays.stream(values).map(v -> (v - mean) * (v - mean)).sum();
        if (sumSq == 0.0) return new double[]{Double.NaN, Double.NaN};

        double t = (mean - reference) / Math.sqrt(sumSq / (n - 1) / n);
        return new double[]{t, new TDistribution(n - 1).cumulativeProbability(t)};
    }

    /**
     * Liefert {V, z, p, r} für H1: Median < Referenz. Differenzen von exakt 0 entfallen, bei
     * gleichen Beträgen werden Durchschnittsränge vergeben. Der p-Wert kommt aus der exakten
     * Verteilung von V; die Ränge werden dafür verdoppelt, damit halbe Ränge ganzzahlig bleiben.
     */
    private static double[] wilcoxonSignedRank(double[] values, double reference) {
        double[] diffs = Arrays.stream(values).map(v -> v - reference).filter(d -> d != 0.0).toArray();
        int n = diffs.length;
        if (n == 0) return new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN};

        Integer[] order = new Integer[n];
        Arrays.setAll(order, i -> i);
        Arrays.sort(order, (a, b) -> Double.compare(Math.abs(diffs[a]), Math.abs(diffs[b])));

        int[] doubledRanks = new int[n];
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && Math.abs(diffs[order[j + 1]]) == Math.abs(diffs[order[i]])) j++;
            for (int k = i; k <= j; k++) doubledRanks[order[k]] = i + j + 2;
            i = j + 1;
        }

        int doubledV = 0;
        int doubledTotal = 0;
        for (int i = 0; i < n; i++) {
            doubledTotal += doubledRanks[i];
            if (diffs[i] > 0) doubledV += doubledRanks[i];
        }

        double[] probabilities = new double[doubledTotal + 1];
        probabilities[0] = 1.0;
        for (int rank : doubledRanks) {
            for (int s = doubledTotal; s >= 0; s--) {
                probabilities[s] = 0.5 * probabilities[s] + (s >= rank ? 0.5 * probabilities[s - rank] : 0.0);
            }
        }
        double p = Arrays.stream(probabilities, 0, doubledV + 1).sum();

        double v = doubledV / 2.0;
        double z = (v - n * (n + 1) / 4.0) / Math.sqrt(n * (n + 1) * (2.0 * n + 1) / 24.0);
        return new double[]{v, z, Math.min(1.0, p), Math.abs(z) / Math.sqrt(n)};
    }

    /** Liefert {W, p} nach Royston (1995, Algorithmus AS R94); gültig für n >= 3. */
    private static double[] shapiroWilk(double[] values) {
        int n = values.length;
        double[] x = values.clone();
        Arrays.sort(x);
        if (n < 3 || x[0] == x[n - 1]) return new double[]{Double.NaN, Double.NaN};

        double[] a = shapiroWilkWeights(n);
        double mean = Arrays.stream(x).average().orElse(Double.NaN);
        double numerator = 0.0;
        double denominator = 0.0;
        for (int i = 0; i < n; i++) {
            numerator += a[i] * x[i];
            denominator += (x[i] - mean) * (x[i] - mean);
        }
        double w = Math.min(1.0, numerator * numerator / denominator);
        return new double[]{w, shapiroWilkP(w, n)};
    }

    private static double[] shapiroWilkWeights(int n) {
        double[] a = new double[n];
        if (n == 3) {
            a[0] = -Math.sqrt(0.5);
            a[2] = Math.sqrt(0.5);
            return a;
        }

        double[] m = new double[n];
        double mm = 0.0;
        for (int i = 0; i < n; i++) {
            m[i] = STANDARD_NORMAL.inverseCumulativeProbability((i + 1 - 0.375) / (n + 0.25));
            mm += m[i] * m[i];
        }

        double u = 1.0 / Math.sqrt(n);
        double last = polynomial(u, 0.0, 0.221157, -0.147981, -2.07119, 4.434685, -2.706056) + m[n - 1] / Math.sqrt(mm);
        a[n - 1] = last;
        a[0] = -last;

        int first;
        double phi;
        if (n > 5) {
            double secondLast = polynomial(u, 0.0, 0.042981, -0.293762, -1.752461, 5.682633, -3.582633) + m[n - 2] / Math.sqrt(mm);
            a[n - 2] = secondLast;
            a[1] = -secondLast;
            first = 2;
            phi = (mm - 2 * m[n - 1] * m[n - 1] - 2 * m[n - 2] * m[n - 2])
                    / (1 - 2 * last * last - 2 * secondLast * secondLast);
        } else {
            first = 1;
            phi = (mm - 2 * m[n - 1] * m[n - 1]) / (1 - 2 * last * last);
        }
        for (int i = first; i < n - first; i++) a[i] = m[i] / Math.sqrt(phi);
        return a;
    }

    private static double shapiroWilkP(double w, int n) {
        if (n == 3) {
            double p = 6.0 / Math.PI * (FastMath.asin(Math.sqrt(w)) - FastMath.asin(Math.sqrt(0.75)));
            return Math.max(0.0, Math.min(1.0, p));
        }

        double y = Math.log(1.0 - w);
        double mu;
        double sigma;
        if (n <= 11) {
            double gamma = polynomial(n, -2.273, 0.459);
            if (y >= gamma) return 0.0;
            y = -Math.log(gamma - y);
            mu = polynomial(n, 0.544, -0.39978, 0.025054, -6.714e-4);
            sigma = Math.exp(polynomial(n, 1.3822, -0.77857, 0.062767, -0.0020322));
        } else {
            double logN = Math.log(n);
            mu = polynomial(logN, -1.5861, -0.31082, -0.083751, 0.0038915);
            sigma = Math.exp(polynomial(logN, -0.4803, -0.082676, 0.0030302));
        }
        return 1.0 - STANDARD_NORMAL.cumulativeProbability((y - mu) / sigma);
    }

    /** Wertet ein Polynom mit aufsteigend sortierten Koeffizienten aus. */
    private static double polynomial(double x, double... coefficients) {
        double result = 0.0;
        for (int i = coefficients.length - 1; i >= 0; i--) result = result * x + coefficients[i];
        return result;
    }
}
