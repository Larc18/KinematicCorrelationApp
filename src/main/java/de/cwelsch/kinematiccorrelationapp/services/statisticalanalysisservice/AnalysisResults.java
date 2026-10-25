package de.cwelsch.kinematiccorrelationapp.services.statisticalanalysisservice;

import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Movement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain data container encapsulating the aggregated statistical analysis metrics.
 * Stores 101-point arrays of Intraclass Correlation Coefficients (ICC), Root Mean
 * Square Errors (RMSE), and Normalized Root Mean Square Errors (NRMSE) mapped uniquely
 * to specific movement and joint configurations.
 * <p>
 * Zusätzlich werden der systematische Versatz (Bias = Mittelwert von OpenCap − Theia über die
 * 101 Punkte) und der um diesen Versatz bereinigte RMSE (zentrierter RMSE) gehalten. Beide werden
 * pro Proband aus genau einem Kurvenpaar berechnet und danach gemittelt — und daher als Skalare
 * und nicht als 101-Punkte-Kurven abgelegt, da ein konstanter Versatz keine Zeitachse hat.
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

    // Skalare Versatz-Kennwerte je Movement/Joint: mittlerer Bias (signierter Versatz
    // OpenCap − Theia) und mittlerer zentrierter RMSE (RMSE nach Abzug des konstanten Versatzes),
    // jeweils über die Probanden gemittelt. Bewusst Skalare statt 101-Punkte-Kurven: Bias und
    // zentrierter RMSE entstehen pro Proband aus dessen kompletter Kurve über alle 101 Punkte.
    private final Map<MovementJointKey, Double> biasResults = new HashMap<>();
    private final Map<MovementJointKey, Double> rmseCenteredResults = new HashMap<>();

    // Ein RMSE-Wert PRO PROBAND (Schlüssel: Participant.getId()), gemittelt über alle Movement/
    // Joint-Kombinationen. Grundlage für einen Einstichproben-t-Test der Probanden-RMSE gegen
    // einen fixen Referenzwert (z.B. 8°) — im Gegensatz zu den obigen Maps, die punktweise über
    // die Kohorte gemittelte 101-Punkte-Kurven je Movement/Joint enthalten.
    private Map<String, Double> perParticipantMeanRmse = new HashMap<>();

    // Analog zu perParticipantMeanRmse: EIN Bias-Wert bzw. EIN zentrierter RMSE-Wert PRO PROBAND
    // (Schlüssel: Participant.getId()), gemittelt über alle Movement/Joint-Kombinationen.
    private Map<String, Double> perParticipantMeanBias = new HashMap<>();
    private Map<String, Double> perParticipantMeanRmseCentered = new HashMap<>();

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
     * Registriert die skalaren Versatz-Kennwerte für eine Movement/Joint-Kombination.
     *
     * @param movement         Die aktive Bewegungsform (z.B. SQUAT, COUNTERMOVEMENT_JUMP).
     * @param joint            Der betrachtete anatomische Gelenkwinkel.
     * @param meanBias         Mittlerer systematischer Versatz (OpenCap − Theia) in Grad bzw. Metern.
     *                         Positiv bedeutet: OpenCap liegt systematisch höher als Theia.
     * @param meanRmseCentered Mittlerer RMSE nach Abzug des konstanten Versatzes (Grad bzw. Meter).
     */
    public void setBiasResultsForJoint(Movement movement, Joints joint, double meanBias, double meanRmseCentered) {
        MovementJointKey key = new MovementJointKey(movement, joint);
        biasResults.put(key, meanBias);
        rmseCenteredResults.put(key, meanRmseCentered);
    }

    /**
     * Liefert den über die Probanden gemittelten systematischen Versatz (Bias) einer
     * Movement/Joint-Kombination.
     *
     * @return Der Bias in Grad bzw. Metern, oder null wenn für den Schlüssel kein Ergebnis vorliegt.
     */
    public Double getBiasForJoint(Movement movement, Joints joint) {
        return biasResults.get(new MovementJointKey(movement, joint));
    }

    /**
     * Liefert den über die Probanden gemittelten zentrierten RMSE (ohne konstanten Versatz) einer
     * Movement/Joint-Kombination.
     *
     * @return Der zentrierte RMSE in Grad bzw. Metern, oder null wenn kein Ergebnis vorliegt.
     */
    public Double getRmseCenteredForJoint(Movement movement, Joints joint) {
        return rmseCenteredResults.get(new MovementJointKey(movement, joint));
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

    /**
     * Returns all registered Bias values (OpenCap − Theia) per movement and joint.
     * Useful for formatting downstream system exports or populating charting UIs.
     */
    public Map<MovementJointKey, Double> getAllBiasResults() {
        return biasResults;
    }

    /**
     * Returns all registered centered RMSE values (RMSE without the constant offset)
     * per movement and joint.
     * Useful for formatting downstream system exports or populating charting UIs.
     */
    public Map<MovementJointKey, Double> getAllRmseCenteredResults() {
        return rmseCenteredResults;
    }

    /**
     * Registriert für jeden Probanden (Schlüssel: Participant.getId()) genau einen RMSE-Wert,
     * gemittelt über alle Movement/Joint-Kombinationen, in denen dieser Proband Daten hat.
     *
     * @param perParticipantMeanRmse Map von Subject-ID auf gemittelten RMSE-Wert.
     */
    public void setPerParticipantMeanRmse(Map<String, Double> perParticipantMeanRmse) {
        this.perParticipantMeanRmse = perParticipantMeanRmse;
    }

    /**
     * Liefert für jeden Probanden (Schlüssel: Participant.getId()) genau einen RMSE-Wert,
     * gemittelt über alle Movement/Joint-Kombinationen. Geeignet als Stichprobe für einen
     * Einstichproben-t-Test gegen einen fixen Referenzwert (z.B. H0: mu >= 8° vs. H1: mu < 8°).
     */
    public Map<String, Double> getPerParticipantMeanRmse() {
        return perParticipantMeanRmse;
    }

    /**
     * Liefert dieselben Werte wie {@link #getPerParticipantMeanRmse()}, aber als reine Werteliste
     * (ohne Subject-ID) — direkt verwendbar als Eingabestichprobe für einen t-Test (z.B. scipy
     * ttest_1samp oder R's t.test(mu = 8, alternative = "less")).
     */
    public List<Double> getPerParticipantMeanRmseValues() {
        return new ArrayList<>(perParticipantMeanRmse.values());
    }

    /**
     * Registriert für jeden Probanden (Schlüssel: Participant.getId()) genau einen Bias-Wert und
     * genau einen zentrierten RMSE-Wert, jeweils gemittelt über alle Movement/Joint-Kombinationen,
     * in denen dieser Proband Daten hat.
     *
     * @param perParticipantMeanBias         Map von Subject-ID auf gemittelten Bias (OpenCap − Theia).
     * @param perParticipantMeanRmseCentered Map von Subject-ID auf gemittelten zentrierten RMSE.
     */
    public void setPerParticipantMeanBias(Map<String, Double> perParticipantMeanBias,
                                          Map<String, Double> perParticipantMeanRmseCentered) {
        this.perParticipantMeanBias = perParticipantMeanBias;
        this.perParticipantMeanRmseCentered = perParticipantMeanRmseCentered;
    }

    /**
     * Liefert für jeden Probanden (Schlüssel: Participant.getId()) genau einen Bias-Wert
     * (signierter Versatz OpenCap − Theia), gemittelt über alle Movement/Joint-Kombinationen.
     * Geeignet als Stichprobe für einen Einstichproben-t-Test gegen mu = 0 (Frage: existiert ein
     * systematischer Versatz zwischen den beiden Systemen?).
     */
    public Map<String, Double> getPerParticipantMeanBias() {
        return perParticipantMeanBias;
    }

    /**
     * Liefert für jeden Probanden genau einen zentrierten RMSE-Wert (RMSE ohne den konstanten
     * Versatz), gemittelt über alle Movement/Joint-Kombinationen. Zeigt, wie groß der Fehler nach
     * einer rein rechnerischen Offset-Korrektur noch wäre.
     */
    public Map<String, Double> getPerParticipantMeanRmseCentered() {
        return perParticipantMeanRmseCentered;
    }

    /**
     * Liefert dieselben Werte wie {@link #getPerParticipantMeanBias()}, aber als reine Werteliste
     * (ohne Subject-ID) — direkt verwendbar als Eingabestichprobe für einen t-Test gegen mu = 0.
     */
    public List<Double> getPerParticipantMeanBiasValues() {
        return new ArrayList<>(perParticipantMeanBias.values());
    }

    /**
     * Liefert dieselben Werte wie {@link #getPerParticipantMeanRmseCentered()}, aber als reine
     * Werteliste (ohne Subject-ID) — direkt verwendbar als Eingabestichprobe für einen t-Test.
     */
    public List<Double> getPerParticipantMeanRmseCenteredValues() {
        return new ArrayList<>(perParticipantMeanRmseCentered.values());
    }
}