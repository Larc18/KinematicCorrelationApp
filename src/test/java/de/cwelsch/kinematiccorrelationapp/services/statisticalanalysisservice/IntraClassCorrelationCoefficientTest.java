package de.cwelsch.kinematiccorrelationapp.services.statisticalanalysisservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("IntraClassCorrelationCoefficient ICC(3,1) Test Suite")
class IntraClassCorrelationCoefficientTest {

    @Test
    @DisplayName("Should return 1.0 for perfectly identical measurement series")
    void testPerfectAgreement_ReturnsOne() {
        double[] a = {10.0, 20.0, 30.0, 40.0, 50.0};
        double[] b = {10.0, 20.0, 30.0, 40.0, 50.0};

        assertEquals(1.0, IntraClassCorrelationCoefficient.calculateICC31(a, b), 1e-9);
    }

    @Test
    @DisplayName("Should return -1.0 for perfectly mirrored (inverse) measurement series")
    void testPerfectDisagreement_ReturnsMinusOne() {
        // Symmetrisch um denselben Mittelwert gespiegelt -> theoretisches Minimum von ICC(3,1).
        // Von Hand nachgerechnet: BMS=0, EMS=2 -> ICC=(0-2)/(0+2)=-1.0
        double[] a = {1.0, 2.0, 3.0};
        double[] b = {3.0, 2.0, 1.0};

        assertEquals(-1.0, IntraClassCorrelationCoefficient.calculateICC31(a, b), 1e-9);
    }

    @Test
    @DisplayName("Should reproduce a manually verified reference ICC value")
    void testKnownReferenceValue() {
        // Von Hand nach Shrout & Fleiss (1979) Modell 3 nachgerechnet: exaktes Ergebnis 0.9
        double[] a = {1.0, 2.0, 3.0};
        double[] b = {1.0, 2.0, 4.0};

        assertEquals(0.9, IntraClassCorrelationCoefficient.calculateICC31(a, b), 1e-9);
    }

    @Test
    @DisplayName("Should ignore a constant systematic offset between both systems (consistency model)")
    void testSystematicOffset_DoesNotReducePerfectConsistency() {
        // ICC(3,1) ist ein Konsistenz-, kein Absolut-Übereinstimmungsmaß: Eine konstante
        // Verschiebung zwischen beiden Systemen darf den Wert nicht senken, solange die
        // Punkt-für-Punkt-Variation perfekt korreliert (empirisch relevant, da OpenCap/Theia
        // durchaus systematische Nullachsen-Offsets aufweisen können).
        double[] a = {1.0, 2.0, 3.0, 4.0, 5.0};
        double[] b = {11.0, 12.0, 13.0, 14.0, 15.0};

        assertEquals(1.0, IntraClassCorrelationCoefficient.calculateICC31(a, b), 1e-9);
    }

    @Test
    @DisplayName("Should return NaN for null input arrays")
    void testNullInputs_ReturnNaN() {
        double[] a = {1.0, 2.0, 3.0};

        assertTrue(Double.isNaN(IntraClassCorrelationCoefficient.calculateICC31(null, a)));
        assertTrue(Double.isNaN(IntraClassCorrelationCoefficient.calculateICC31(a, null)));
        assertTrue(Double.isNaN(IntraClassCorrelationCoefficient.calculateICC31(null, null)));
    }

    @Test
    @DisplayName("Should return NaN when both series have mismatched lengths")
    void testMismatchedLengths_ReturnsNaN() {
        double[] a = {1.0, 2.0, 3.0};
        double[] b = {1.0, 2.0};

        assertTrue(Double.isNaN(IntraClassCorrelationCoefficient.calculateICC31(a, b)));
    }

    @Test
    @DisplayName("Should return NaN when fewer than 2 matched samples are provided")
    void testTooFewSamples_ReturnsNaN() {
        double[] a = {5.0};
        double[] b = {5.0};

        assertTrue(Double.isNaN(IntraClassCorrelationCoefficient.calculateICC31(a, b)));
        assertTrue(Double.isNaN(IntraClassCorrelationCoefficient.calculateICC31(new double[0], new double[0])));
    }

    @Test
    @DisplayName("Should return NaN for degenerate zero-variance data instead of falsely reporting perfect agreement")
    void testDegenerateZeroVariance_ReturnsNaN() {
        // Jeder Proband liefert auf beiden Systemen jeweils einen konstanten Wert, aber die
        // beiden Systeme unterscheiden sich konstant voneinander (5 vs. 10): Es gibt keinerlei
        // messbare Varianz, an der sich eine Übereinstimmung beurteilen ließe. Siehe
        // NUMERICAL_UNDERFLOW_THRESHOLD-Guard in IntraClassCorrelationCoefficient.
        double[] a = {5.0, 5.0, 5.0, 5.0};
        double[] b = {10.0, 10.0, 10.0, 10.0};

        assertTrue(Double.isNaN(IntraClassCorrelationCoefficient.calculateICC31(a, b)));
    }

    @Test
    @DisplayName("Should keep the result bounded within [-1.0, 1.0] for noisy real-world-like data")
    void testResult_IsBoundedWithinValidRange() {
        double[] a = {12.3, 45.6, 7.8, 33.1, 20.0, 5.5, 60.2};
        double[] b = {14.1, 40.2, 9.9, 30.0, 25.5, 4.0, 58.0};

        double icc = IntraClassCorrelationCoefficient.calculateICC31(a, b);

        assertFalse(Double.isNaN(icc));
        assertTrue(icc >= -1.0 && icc <= 1.0);
    }

    @Test
    @DisplayName("Should decrease monotonically as measurement error grows, given real between-subject variation")
    void testIncreasingMeasurementError_DecreasesIcc() {
        // WICHTIG: Nur wenn System A eine echte Zwischen-Probanden-Streuung liefert, kann sich
        // wachsendes Messrauschen überhaupt in einem sinkenden ICC niederschlagen. Ist diese
        // Streuung dagegen null (siehe testDegenerateZeroVariance_ReturnsNaN und die
        // konstant-vs-konstant-Fälle), gibt es kein "echtes Signal", gegen das Rauschen verglichen
        // werden könnte - der ICC landet dann unabhängig von der Fehlergröße bei 0.0, nicht bei
        // einem graduell abgestuften Wert.
        double[] trueValues = {0.0, 20.0, 40.0, 60.0};

        // Kleiner, symmetrischer Messfehler relativ zur echten Streuung (Spannweite 60)
        double[] measuredSmallError = addElementWise(trueValues, new double[]{1.0, -1.0, 1.0, -1.0});
        // Deutlich größerer, ebenfalls symmetrischer Messfehler
        double[] measuredLargeError = addElementWise(trueValues, new double[]{15.0, -15.0, 15.0, -15.0});

        double iccSmallError = IntraClassCorrelationCoefficient.calculateICC31(trueValues, measuredSmallError);
        double iccLargeError = IntraClassCorrelationCoefficient.calculateICC31(trueValues, measuredLargeError);

        assertTrue(iccSmallError > iccLargeError,
                "Größerer Messfehler muss zu einem niedrigeren ICC führen: klein=" + iccSmallError + ", groß=" + iccLargeError);
        assertTrue(iccSmallError > 0.9, "Kleiner Fehler relativ zur echten Streuung sollte nahezu perfekte Übereinstimmung ergeben");
        assertTrue(iccLargeError < 0.85, "Großer Fehler relativ zur echten Streuung muss den ICC spürbar senken");
    }

    private double[] addElementWise(double[] a, double[] b) {
        double[] result = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = a[i] + b[i];
        }
        return result;
    }
}
