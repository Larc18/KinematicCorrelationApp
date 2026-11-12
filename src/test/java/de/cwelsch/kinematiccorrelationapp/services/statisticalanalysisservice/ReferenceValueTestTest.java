package de.cwelsch.kinematiccorrelationapp.services.statisticalanalysisservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ReferenceValueTest (Referenzwerte aus scipy.stats)")
class ReferenceValueTestTest {

    private static final double[] STUDY_RMSE = {
            7.154637195540986, 10.529185843244095, 7.237072217586819, 6.965220853007838,
            9.252511274435172, 7.618901008704599, 9.625366960062017, 8.735363988671761,
            13.187606545578289, 17.518210133347168, 12.535000294661886, 6.559398692876835,
            6.246703050172103, 10.32398083258166
    };

    @Test
    @DisplayName("Should reproduce scipy for n >= 12 and select Wilcoxon when normality is rejected")
    void testStudySample_MatchesScipy() {
        ReferenceValueTest.Result result = ReferenceValueTest.test(STUDY_RMSE, 8.0);

        assertEquals(0.874986154882845, result.shapiroW(), 1e-4);
        assertEquals(0.04933062523472178, result.shapiroP(), 1e-3);
        assertEquals(1.82107238525086, result.t(), 1e-10);
        assertEquals(0.9541593048459389, result.tP(), 1e-8);
        assertEquals(76.0, result.wilcoxonV());
        assertEquals(0.932373046875, result.wilcoxonP(), 1e-10);
        assertFalse(result.normalityAssumed());
        assertEquals(result.wilcoxonP(), result.selectedP());
        assertFalse(result.nullHypothesisRejected());
    }

    @Test
    @DisplayName("Should reproduce the scipy Shapiro-Wilk result for 4 <= n <= 11")
    void testShapiro_SmallSamples() {
        ReferenceValueTest.Result ten = ReferenceValueTest.test(new double[]{1, 2, 2, 3, 5, 8, 8, 9, 12, 20}, 8.0);
        ReferenceValueTest.Result five = ReferenceValueTest.test(new double[]{1, 2, 4, 7, 11}, 8.0);

        assertEquals(0.8816298325512228, ten.shapiroW(), 1e-4);
        assertEquals(0.13623213890411245, ten.shapiroP(), 1e-3);
        assertEquals(0.9344338593254921, five.shapiroW(), 1e-4);
        assertEquals(0.6269016431627364, five.shapiroP(), 1e-3);
    }

    @Test
    @DisplayName("Should select the t-test and reject H0 for a normal sample clearly below the reference")
    void testSelectsTTest_WhenNormal() {
        ReferenceValueTest.Result result = ReferenceValueTest.test(new double[]{3.1, 3.9, 4.2, 4.6, 5.0, 5.3, 5.9, 6.8}, 8.0);

        assertTrue(result.normalityAssumed());
        assertEquals(result.tP(), result.selectedP());
        assertTrue(result.nullHypothesisRejected());
    }

    @Test
    @DisplayName("Should report undefined statistics as NaN instead of failing on constant or tiny samples")
    void testUndefinedStatistics_AreNaN() {
        assertTrue(Double.isNaN(ReferenceValueTest.test(new double[]{5.0, 5.0, 5.0}, 8.0).shapiroW()));
        assertTrue(Double.isNaN(ReferenceValueTest.test(new double[]{6.0, 4.0}, 8.0).shapiroP()));
        assertTrue(Double.isNaN(ReferenceValueTest.test(new double[]{8.0, 8.0, 8.0}, 8.0).wilcoxonP()));
    }
}
