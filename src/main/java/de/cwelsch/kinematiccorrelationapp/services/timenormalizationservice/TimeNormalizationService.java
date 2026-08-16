package de.cwelsch.kinematiccorrelationapp.services.timenormalizationservice;

import de.cwelsch.kinematiccorrelationapp.model.FrameData;
import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Trial;
import de.cwelsch.kinematiccorrelationapp.model.TrialSession;
import de.cwelsch.kinematiccorrelationapp.services.timenormalizationservice.strategy.*;
import org.apache.commons.math3.analysis.interpolation.SplineInterpolator;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service responsible for harmonizing time-series data across different sampling architectures.
 * Isolates active movement windows, linearizes local tracking signal drops, and normalizes
 * coordinate matrices using cubic spline interpolation onto a standardized 101-point (0% - 100%) scale.
 * <p>
 * Frame-rate independence: Strategies return movement boundaries in seconds, not frame indices.
 * Each trial then maps those timestamps to its own frame indices, ensuring that 50% of the
 * normalized curve represents the same movement phase regardless of system frame rate (60fps vs 85fps).
 */
public class TimeNormalizationService {

    private static final int NORMALIZED_POINTS = 101;
    private static final double MISSING_THRESHOLD_RATIO = 0.15;

    /**
     * Pairs each OpenCap trial with its matching Theia trial (by file name prefix and sequence order),
     * selects the appropriate {@link MovementStrategy} per movement, and normalizes both trials of
     * each pair onto the shared 101-point (0%-100%) timeline.
     *
     * @param trialSession the session whose trials are paired and normalized in place
     */
    public void normalizeTrial(TrialSession trialSession) {
        System.out.println("========================================");
        System.out.println("Starting TimeNormalizationService (0% - 100%)...");
        System.out.println("========================================");

        if (trialSession == null || trialSession.getTrials() == null || trialSession.getTrials().isEmpty()) {
            System.err.println("[Error] TimeNormalizationService: No active trials found available for normalization.");
            return;
        }

        List<Trial> trials = trialSession.getTrials();

        Map<String, List<Trial>> openCapMovementMap = new HashMap<>();
        Map<String, List<Trial>> theiaMovementMap = new HashMap<>();

        for (Trial trial : trials) {
            if (trial == null || trial.getSourceFileName() == null) continue;

            String fileName = java.nio.file.Paths.get(trial.getSourceFileName())
                    .getFileName().toString().toLowerCase().trim();
            String prefix = fileName.split("_")[0];

            if (trial.isOpenCap()) {
                openCapMovementMap.computeIfAbsent(prefix, k -> new ArrayList<>()).add(trial);
            } else {
                theiaMovementMap.computeIfAbsent(prefix, k -> new ArrayList<>()).add(trial);
            }
        }

        openCapMovementMap.values().forEach(list -> list.sort(
                Comparator.comparing(t -> java.nio.file.Paths.get(t.getSourceFileName()).getFileName())));
        theiaMovementMap.values().forEach(list -> list.sort(
                Comparator.comparing(t -> java.nio.file.Paths.get(t.getSourceFileName()).getFileName())));

        System.out.printf("[Debug] OpenCap Prefixes: %d, Theia Prefixes: %d.%n",
                openCapMovementMap.size(), theiaMovementMap.size());

        Set<String> allPrefixes = new HashSet<>();
        allPrefixes.addAll(openCapMovementMap.keySet());
        allPrefixes.addAll(theiaMovementMap.keySet());

        for (String prefix : allPrefixes) {
            List<Trial> ocList = openCapMovementMap.getOrDefault(prefix, Collections.emptyList());
            List<Trial> thList = theiaMovementMap.getOrDefault(prefix, Collections.emptyList());

            int maxPairs = Math.min(ocList.size(), thList.size());

            for (int i = 0; i < maxPairs; i++) {
                Trial openCapTrial = ocList.get(i);
                Trial theiaTrial = thList.get(i);

                // Sicherheitsprüfung: Die Zuordnung erfolgt über den Dateinamens-Präfix, nicht
                // über das bereits bekannte Movement. Bei einer Präfix-Kollision zwischen zwei
                // unterschiedlichen Bewegungsordnern würden sonst falsche Trials gegeneinander
                // normalisiert und verglichen werden, ohne dass ein Fehler sichtbar wird.
                if (openCapTrial.getMovement() != theiaTrial.getMovement()) {
                    System.err.printf("  [Skip] Movement mismatch for prefix '%s': OpenCap '%s' (%s) vs Theia '%s' (%s) — skipping pair.%n",
                            prefix,
                            java.nio.file.Paths.get(openCapTrial.getSourceFileName()).getFileName(), openCapTrial.getMovement(),
                            java.nio.file.Paths.get(theiaTrial.getSourceFileName()).getFileName(), theiaTrial.getMovement());
                    continue;
                }

                System.out.printf("Harmonizing pair: [%s] & [%s]%n",
                        java.nio.file.Paths.get(openCapTrial.getSourceFileName()).getFileName(),
                        java.nio.file.Paths.get(theiaTrial.getSourceFileName()).getFileName());

                // CRITICAL FIX: Ein "plain" switch wirft eine NullPointerException, sobald der
                // Selektor null ist. Da unerkannte Datei-/Ordnernamen zu movement == null führen
                // können, muss dieser Fall explizit über "case null" abgefangen werden.
                MovementStrategy strategy = switch (openCapTrial.getMovement()) {
                    case null -> {
                        System.err.printf("  [Warning] Trial '%s' has no recognized Movement — falling back to DefaultStrategy.%n",
                                java.nio.file.Paths.get(openCapTrial.getSourceFileName()).getFileName());
                        yield new DefaultStrategy();
                    }
                    case SQUAT -> new SquatStrategy();
                    case COUNTERMOVEMENT_JUMP -> new CMJStrategy();
                    case LATERAL_LUNGE_LEFT -> new LLLStrategy();
                    case LATERAL_LUNGE_RIGHT -> new LLRStrategy();
                    case BECKEN_RUMPF_ROTATION -> new BBRStrategy();
                };

                // Jeder Trial bestimmt sein Fenster selbst (in Sekunden),
                // sodass die Fenstererkennung unabhängig von der Framerate korrekt arbeitet.
                // 50% der normierten Kurve entspricht damit für beide Systeme derselben Bewegungsphase.
                normalizeSingleTrial(openCapTrial, strategy);
                normalizeSingleTrial(theiaTrial, strategy);
            }
        }

        System.out.println("Time Normalization processing completed successfully.");
    }

    /**
     * Determines the movement window in seconds via the strategy, maps those timestamps
     * to the trial's own frame indices, and normalizes all joint tracks via spline interpolation.
     */
    private void normalizeSingleTrial(Trial trial, MovementStrategy strategy) {
        List<FrameData> frames = trial.getFrameDataList();
        if (frames == null || frames.isEmpty()) return;

        // CRITICAL FIX (a): Trials mit kritischen Validierungsfehlern (siehe TechnicalValidationService)
        // werden von der Normalisierung und damit von der gesamten Statistik/dem Export ausgeschlossen.
        if (trial.isExcluded()) {
            System.err.printf("  [Skip] '%s' excluded from analysis (Reason: %s).%n",
                    trial.getSourceFileName(), trial.getExclusionReason());
            return;
        }

        // Fenster in Sekunden — unabhängig von der Framerate des jeweiligen Systems
        MovementWindow window = strategy.findMovementWindow(frames);
        double startSec = window.startSeconds();
        double endSec = window.endSeconds();

        // CRITICAL FIX (b): Kennzeichnet, ob die Fensterhysterese des jeweiligen Movements
        // fehlgeschlagen ist und stattdessen der komplette Trial als Fenster verwendet wurde.
        // Downstream (CSV-/HTML-Export) wird dies sichtbar gemacht statt es zu verschweigen.
        trial.setWindowFallbackUsed(window.fallbackUsed());

        int startIdx = findFrameForTime(frames, startSec);
        int endIdx = findFrameForTime(frames, endSec);

        // Defensive Boundary-Clips
        startIdx = Math.max(0, Math.min(startIdx, frames.size() - 1));
        endIdx = Math.max(startIdx, Math.min(endIdx, frames.size() - 1));

        int windowLength = endIdx - startIdx + 1;

        System.out.printf("  [%s] Window: %.3fs–%.3fs → frames %d–%d (%d frames)%n",
                java.nio.file.Paths.get(trial.getSourceFileName()).getFileName(),
                startSec, endSec, startIdx, endIdx, windowLength);

        if (windowLength < 4) {
            System.err.printf("  [Skip] '%s': window too short after mapping (%d frames).%n",
                    trial.getSourceFileName(), windowLength);
            return;
        }

        // Nur der fachliche Analyseumfang (Hüfte/Knie/Sprunggelenk) wird normalisiert —
        // andere Gelenke (Becken, LWS etc.) werden nur roh für die Fenstererkennung
        // der jeweiligen MovementStrategy gebraucht, siehe Joints.ANALYSIS_SCOPE.
        for (Joints joint : Joints.ANALYSIS_SCOPE) {
            if (!frames.get(startIdx).jointAngle().containsKey(joint)) continue;

            double[] cutAngles = new double[windowLength];
            int missingCount = 0;

            for (int f = 0; f < windowLength; f++) {
                Double angle = frames.get(startIdx + f).jointAngle().get(joint);
                if (angle != null && !Double.isNaN(angle)) {
                    cutAngles[f] = angle;
                } else {
                    missingCount++;
                    cutAngles[f] = Double.NaN;
                }
            }

            double missingRatio = (double) missingCount / windowLength;
            if (missingRatio > MISSING_THRESHOLD_RATIO) {
                System.err.printf("  [Drop] Joint [%s] in '%s': %.1f%% missing data.%n",
                        joint.name(), trial.getSourceFileName(), missingRatio * 100);
                continue;
            }

            fillMissingLinear(cutAngles);
            trial.setNormalizedAngles(joint, performSplineInterpolation(cutAngles));
        }
    }

    /**
     * Finds the frame index whose timestamp is closest to the target time in seconds.
     *
     * <p>Package-private (rather than private) so it can be unit-tested directly.
     *
     * @param frames        the sequential tracking data points to search
     * @param targetSeconds the target timestamp, in seconds
     * @return the index of the frame whose timestamp is closest to {@code targetSeconds};
     * on an exact tie, the earlier (lower-index) frame wins
     */
    int findFrameForTime(List<FrameData> frames, double targetSeconds) {
        int bestIdx = 0;
        double bestDelta = Double.MAX_VALUE;

        for (int i = 0; i < frames.size(); i++) {
            double delta = Math.abs(frames.get(i).second() - targetSeconds);
            if (delta < bestDelta) {
                bestDelta = delta;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    /**
     * Patches missing signal gaps via bidirectional linear interpolation. Interior gaps (surrounded
     * by valid values on both sides) are linearly interpolated; leading/trailing gaps (no valid value
     * on one side) are filled by holding the nearest valid value on the other side. Mutates {@code angles}
     * in place. An array without any valid value at all is left unchanged (still all NaN).
     *
     * <p>Package-private (rather than private) so it can be unit-tested directly.
     *
     * @param angles the angle track to patch in place; NaN marks a missing sample
     */
    void fillMissingLinear(double[] angles) {
        int n = angles.length;

        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(angles[i])) continue;

            int left = i - 1;
            while (left >= 0 && Double.isNaN(angles[left])) left--;

            int right = i + 1;
            while (right < n && Double.isNaN(angles[right])) right++;

            if (left >= 0 && right < n) {
                double slope = (angles[right] - angles[left]) / (right - left);
                angles[i] = angles[left] + slope * (i - left);
            } else if (left >= 0) {
                angles[i] = angles[left];
            }
        }

        for (int i = n - 1; i >= 0; i--) {
            if (!Double.isNaN(angles[i])) continue;

            int right = i + 1;
            while (right < n && Double.isNaN(angles[right])) right++;
            if (right < n) angles[i] = angles[right];
        }
    }

    /**
     * Interpolates a raw angle array onto an equispaced 101-point grid (0–100%) using a natural cubic
     * spline. The input samples are treated as equidistant across the 0–100% range, with the last
     * sample pinned to exactly 100% to avoid floating-point drift.
     *
     * <p>Package-private (rather than private) so it can be unit-tested directly.
     *
     * @param cutAngles the raw angle samples for the detected movement window (at least 3 points)
     * @return a 101-point array with the interpolated values at 0%, 1%, ..., 100%
     */
    double[] performSplineInterpolation(double[] cutAngles) {
        int n = cutAngles.length;

        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = (100.0 / (n - 1)) * i;
        }
        x[n - 1] = 100.0; // Floating-point boundary fix

        SplineInterpolator interpolator = new SplineInterpolator();
        PolynomialSplineFunction spline = interpolator.interpolate(x, cutAngles);

        double[] normalized = new double[NORMALIZED_POINTS];
        for (int i = 0; i < NORMALIZED_POINTS; i++) {
            double queryPoint = Math.min((100.0 / (NORMALIZED_POINTS - 1)) * i, x[n - 1]);
            normalized[i] = spline.value(queryPoint);
        }

        return normalized;
    }
}