package de.cwelsch.kinematiccorrelationapp.services.timenormalizationservice.strategy;

import de.cwelsch.kinematiccorrelationapp.model.FrameData;
import de.cwelsch.kinematiccorrelationapp.model.Joints;

import java.util.List;

/**
 * Refactored CMJ Window Strategy using peak-centered relative ROM thresholds.
 * Evaluates the full jump cycle from initiation (countermovement onset)
 * through takeoff/flight to landing stabilization based purely on joint kinematics,
 * ensuring robust 1:1 synchronization between OpenCap and Theia.
 * <p>
 * Primary signal: KNEE_ANGLE_R/L (available from both systems). If no usable knee
 * data exists at all, falls back to the vertical pelvis translation PELVIS_TY
 * (OpenCap-specific) before finally giving up and using the whole trial as window.
 */
public class CMJStrategy implements MovementStrategy {

    private static final double ROM_ONSET_RATIO = 0.05; // 5% Schwellenwert der Beuge-Amplitude
    private static final double MIN_PELVIS_ROM_METERS = 0.03; // Mindestamplitude für einen echten Sprung (Meter, nicht Grad!)
    // CRITICAL FIX: War vorher ein hartes "+20 Frames" — bei OpenCap (60Hz) 0,333s, bei Theia
    // (85Hz) nur 0,235s. Das widerspricht der in TimeNormalizationService dokumentierten
    // Framerate-Unabhängigkeit: Beide Systeme sollen für dieselbe Wartezeit vor der Landungssuche
    // denselben Zeitraum überspringen, nicht dieselbe Frame-Anzahl. Referenzwert 20/85s beibehalten
    // (der ursprünglich beabsichtigte Wert bei Theias 85Hz), aber jetzt pro Trial in Frames umgerechnet.
    private static final double LANDING_SEARCH_BUFFER_SECONDS = 20.0 / 85.0;

    /**
     * Detects the countermovement-jump window, preferring knee-angle-based detection
     * (countermovement dip through landing stabilization) and falling back to vertical pelvis
     * translation (OpenCap-only) if no usable knee data is available, and finally to the entire
     * trial if neither signal yields a plausible window.
     *
     * @param frames the sequential tracking data points collected during the trial recording
     * @return the detected movement window, or a fallback window covering the entire trial
     */
    @Override
    public MovementWindow findMovementWindow(List<FrameData> frames) {
        if (frames == null || frames.size() < 10) {
            return fallback(frames);
        }

        MovementWindow kneeWindow = findWindowViaKnee(frames);
        if (!kneeWindow.fallbackUsed()) {
            return kneeWindow;
        }

        // OpenCap-Ersatzpfad: Keine brauchbaren Kniewinkel vorhanden -> versuche die
        // vertikale Beckentranslation (PELVIS_TY) als Ersatzsignal, bevor endgültig
        // auf den kompletten Trial zurückgefallen wird.
        MovementWindow pelvisWindow = findWindowViaPelvis(frames);
        if (!pelvisWindow.fallbackUsed()) {
            return pelvisWindow;
        }

        return fallback(frames);
    }

    /**
     * Theia-Pfad: Fenster-Erkennung über KNEE_ANGLE_R/L (bevorzugt rechtes Knie).
     */
    private MovementWindow findWindowViaKnee(List<FrameData> frames) {
        // 1. Suche nach dem ersten Beuge-Peak (Countermovement-Tiefpunkt vor dem Abstoß)
        int countermovementPeakFrame = -1;
        double maxCountermovementKnee = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < frames.size(); i++) {
            Double kneeR = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_R);
            Double kneeL = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_L);
            Double currentVal = (kneeR != null && !Double.isNaN(kneeR)) ? kneeR : kneeL;

            if (currentVal != null && !Double.isNaN(currentVal) && currentVal > maxCountermovementKnee) {
                maxCountermovementKnee = currentVal;
                countermovementPeakFrame = i;
            }
        }

        if (countermovementPeakFrame == -1 || maxCountermovementKnee < 15.0) {
            return fallback(frames);
        }

        // 2. Bestimme die Stand-Baseline (Erste 5% der Frames)
        int baselineRange = Math.max(3, (int) (frames.size() * 0.05));
        double baselineAngle = 0.0;
        int validBaselineCount = 0;

        for (int i = 0; i < baselineRange; i++) {
            Double val = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_R);
            if (val != null && !Double.isNaN(val)) {
                baselineAngle += val;
                validBaselineCount++;
            }
        }
        baselineAngle = validBaselineCount > 0 ? baselineAngle / validBaselineCount : 0.0;

        // 3. Bewegungsamplitude (ROM) und 5%-Schwellenwert
        double totalROM = maxCountermovementKnee - baselineAngle;
        if (totalROM < 10.0) {
            return fallback(frames);
        }
        double thresholdAngle = baselineAngle + (totalROM * ROM_ONSET_RATIO);

        // 4. Startpunkt (Rückwärtssuche vor dem Countermovement-Peak)
        int startFrame = 0;
        for (int i = countermovementPeakFrame; i >= 0; i--) {
            Double val = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_R);
            if (val != null && !Double.isNaN(val) && val <= thresholdAngle) {
                startFrame = i;
                break;
            }
        }

        // 5. Suche nach dem zweiten Beuge-Peak (Landung nach der Flugphase)
        int landingPeakFrame = countermovementPeakFrame;
        double maxLandingKnee = Double.NEGATIVE_INFINITY;

        // Suche ab LANDING_SEARCH_BUFFER_SECONDS nach dem Countermovement-Peak nach dem Landungspeak
        int searchStart = frameOffsetForBuffer(frames, countermovementPeakFrame);
        for (int i = searchStart; i < frames.size(); i++) {
            Double kneeR = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_R);
            Double kneeL = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_L);
            Double currentVal = (kneeR != null && !Double.isNaN(kneeR)) ? kneeR : kneeL;

            if (currentVal != null && !Double.isNaN(currentVal) && currentVal > maxLandingKnee) {
                maxLandingKnee = currentVal;
                landingPeakFrame = i;
            }
        }

        // 6. Endpunkt (Vorwärtssuche nach der Lande-Stabilisierung)
        int endFrame = frames.size() - 1;
        for (int i = landingPeakFrame; i < frames.size(); i++) {
            Double val = frames.get(i).jointAngle().get(Joints.KNEE_ANGLE_R);
            if (val != null && !Double.isNaN(val) && val <= thresholdAngle) {
                endFrame = i;
                break;
            }
        }

        if (startFrame >= endFrame) {
            return fallback(frames);
        }

        return new MovementWindow(
                frames.get(startFrame).second(),
                frames.get(endFrame).second(),
                false
        );
    }

    /**
     * OpenCap-Ersatzpfad: Fenster-Erkennung über die vertikale Beckentranslation PELVIS_TY.
     * Arbeitet mit einer "Beugetiefe" (Baseline - aktuelle Höhe), damit dieselbe Peak-/
     * Schwellenwert-Logik wie beim Kniewinkel greift: Kauern und Landen senken das Becken
     * unter die Baseline (positive Tiefe), die Flugphase hebt es darüber (negative Tiefe)
     * und wird dadurch automatisch von der Peak-Suche ignoriert.
     * <p>
     * Die erste signifikante Kauer-Exkursion (nicht das globale Tiefen-Maximum!) markiert
     * das Countermovement, da eine spätere Landung durchaus tiefer einsinken kann als die
     * ursprüngliche Ausholbewegung.
     */
    private MovementWindow findWindowViaPelvis(List<FrameData> frames) {
        boolean hasPelvisY = frames.stream()
                .anyMatch(f -> f.jointAngle().get(Joints.PELVIS_TY) != null);
        if (!hasPelvisY) {
            return fallback(frames);
        }

        // 1. Stand-Baseline bestimmen (Erste 5% der Aufzeichnung)
        int baselineRange = Math.max(3, (int) (frames.size() * 0.05));
        double baselineY = 0.0;
        int validBaselineCount = 0;

        for (int i = 0; i < baselineRange; i++) {
            Double val = frames.get(i).jointAngle().get(Joints.PELVIS_TY);
            if (val != null && !Double.isNaN(val)) {
                baselineY += val;
                validBaselineCount++;
            }
        }
        if (validBaselineCount == 0) {
            return fallback(frames);
        }
        baselineY /= validBaselineCount;

        // 2. Globale Amplitude ermitteln, um eine Rauschgrenze zu definieren, unterhalb
        // derer eine Abweichung von der Baseline nicht als eigenständige Bewegungsphase zählt.
        double globalMaxAbsDeviation = 0.0;
        for (FrameData frame : frames) {
            Double val = frame.jointAngle().get(Joints.PELVIS_TY);
            if (val != null && !Double.isNaN(val)) {
                globalMaxAbsDeviation = Math.max(globalMaxAbsDeviation, Math.abs(val - baselineY));
            }
        }
        if (globalMaxAbsDeviation < MIN_PELVIS_ROM_METERS) {
            return fallback(frames);
        }
        double noiseFloor = globalMaxAbsDeviation * ROM_ONSET_RATIO;

        // 3. Erste signifikante Kauer-Exkursion suchen (Countermovement-Tiefpunkt)
        int countermovementPeakFrame = -1;
        double maxCrouchDepth = 0.0;
        boolean inExcursion = false;

        for (int i = 0; i < frames.size(); i++) {
            Double val = frames.get(i).jointAngle().get(Joints.PELVIS_TY);
            if (val == null || Double.isNaN(val)) continue;
            double depth = baselineY - val; // positiv = unterhalb der Baseline (Kauern)

            if (!inExcursion) {
                if (depth > noiseFloor) {
                    inExcursion = true;
                    maxCrouchDepth = depth;
                    countermovementPeakFrame = i;
                }
            } else {
                if (depth > maxCrouchDepth) {
                    maxCrouchDepth = depth;
                    countermovementPeakFrame = i;
                }
                if (depth <= noiseFloor) {
                    break; // Exkursion beendet, Peak steht fest
                }
            }
        }

        if (countermovementPeakFrame == -1 || maxCrouchDepth < MIN_PELVIS_ROM_METERS) {
            return fallback(frames);
        }

        double thresholdDepth = maxCrouchDepth * ROM_ONSET_RATIO;

        // 4. Startpunkt (Rückwärtssuche vor dem Countermovement-Peak)
        int startFrame = 0;
        for (int i = countermovementPeakFrame; i >= 0; i--) {
            Double val = frames.get(i).jointAngle().get(Joints.PELVIS_TY);
            if (val != null && !Double.isNaN(val) && (baselineY - val) <= thresholdDepth) {
                startFrame = i;
                break;
            }
        }

        // 5. Suche nach der Landung (zweite, spätere Kauer-Exkursion nach der Flugphase).
        // Die Flugphase liefert negative "Tiefen" (Becken über der Baseline) und wird von
        // dieser Maximum-Suche automatisch übersprungen.
        int landingPeakFrame = countermovementPeakFrame;
        double maxLandingDepth = Double.NEGATIVE_INFINITY;

        int searchStart = Math.min(countermovementPeakFrame + 20, frames.size() - 1);
        for (int i = searchStart; i < frames.size(); i++) {
            Double val = frames.get(i).jointAngle().get(Joints.PELVIS_TY);
            if (val == null || Double.isNaN(val)) continue;
            double depth = baselineY - val;
            if (depth > maxLandingDepth) {
                maxLandingDepth = depth;
                landingPeakFrame = i;
            }
        }

        // 6. Endpunkt (Vorwärtssuche nach der Lande-Stabilisierung)
        int endFrame = frames.size() - 1;
        for (int i = landingPeakFrame; i < frames.size(); i++) {
            Double val = frames.get(i).jointAngle().get(Joints.PELVIS_TY);
            if (val != null && !Double.isNaN(val) && (baselineY - val) <= thresholdDepth) {
                endFrame = i;
                break;
            }
        }

        if (startFrame >= endFrame) {
            return fallback(frames);
        }

        return new MovementWindow(
                frames.get(startFrame).second(),
                frames.get(endFrame).second(),
                false
        );
    }

    private MovementWindow fallback(List<FrameData> frames) {
        if (frames == null || frames.isEmpty()) return new MovementWindow(0.0, 0.0, true);
        return new MovementWindow(frames.get(0).second(), frames.get(frames.size() - 1).second(), true);
    }

    /**
     * Wandelt LANDING_SEARCH_BUFFER_SECONDS anhand der tatsächlichen Framerate DIESES Trials in
     * eine Frame-Anzahl um, statt eine feste Frame-Anzahl (die je nach System unterschiedlich viel
     * Zeit bedeutet) zu verwenden.
     */
    private int frameOffsetForBuffer(List<FrameData> frames, int fromIndex) {
        double dt = (frames.size() > 1) ? (frames.get(1).second() - frames.get(0).second()) : (1.0 / 85.0);
        if (dt <= 0) dt = 1.0 / 85.0;
        int bufferFrames = Math.max(1, (int) Math.round(LANDING_SEARCH_BUFFER_SECONDS / dt));
        return Math.min(fromIndex + bufferFrames, frames.size() - 1);
    }
}
