package de.cwelsch.kinematiccorrelationapp.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Trial is of one movement by one participant. It consists of a frame data list, the movement, and normalized joint angles.
 */
public class Trial {

    private final List<FrameData> frameDataList;
    private final String sourceFileName;
    private Movement movement;
    private final boolean isOpenCap;
    private final Map<Joints, double[]> normalizedJointAngles = new HashMap<>();

    public Trial(List<FrameData> frameDataList, String sourceFileName, boolean isOpenCap) {
        this.frameDataList = new ArrayList<>(frameDataList);
        this.sourceFileName = sourceFileName;
        this.isOpenCap = isOpenCap;
        this.movement = detectMovement(sourceFileName);
    }

    /**
     * Ordnet den Dateinamen anhand von Schlüsselwörtern dem bestehenden Movement-Enum zu.
     * Unterstützt Kurzformen (SQT, CMJ, BRR, LLL, LLR) unabhängig von Groß-/Kleinschreibung.
     */
    private Movement detectMovement(String fileName) {
        if (fileName == null) return null;

        String lower = fileName.toLowerCase().trim();

        if (lower.contains("squat") || lower.contains("kniebeuge") || lower.contains("sqt")) {
            return Movement.SQUAT;
        }
        if (lower.contains("countermovementjump") || lower.contains("cmj")) {
            return Movement.COUNTERMOVEMENT_JUMP;
        }
        if (lower.contains("lunge_left") || lower.contains("lungeleft") || lower.contains("ausfallschritt_links") || lower.contains("lll") || lower.contains("links")) {
            return Movement.LATERAL_LUNGE_LEFT;
        }
        if (lower.contains("lunge_right") || lower.contains("lungeright") || lower.contains("ausfallschritt_rechts") || lower.contains("llr") || lower.contains("rechts")) {
            return Movement.LATERAL_LUNGE_RIGHT;
        }
        if (lower.contains("becken_rumpf_rotation") || lower.contains("brr") || lower.contains("rotation")) {
            return Movement.BECKEN_RUMPF_ROTATION;
        }

        return null;
    }

    /**
     * Wird vom TrialSessionBuilder gesetzt, wenn der Bewegungsname
     * aus dem Ordnernamen stammt statt aus dem Dateinamen.
     * Erkennt Ordnerstrukturen wie "01_SQT" und "05_BRR" fehlerfrei.
     */
    public void setMovementName(String movementName) {
        if (movementName == null) return;

        String lower = movementName.toLowerCase().trim();

        if (lower.contains("squat") || lower.contains("kniebeuge") || lower.contains("sqt")) {
            this.movement = Movement.SQUAT;
            return;
        }
        if (lower.contains("cmj") || lower.contains("countermovementjump")) {
            this.movement = Movement.COUNTERMOVEMENT_JUMP;
            return;
        }
        if (lower.contains("lunge_left") || lower.contains("lungeleft") || lower.contains("links") || lower.contains("lll")) {
            this.movement = Movement.LATERAL_LUNGE_LEFT;
            return;
        }
        if (lower.contains("lunge_right") || lower.contains("lungeright") || lower.contains("rechts") || lower.contains("llr")) {
            this.movement = Movement.LATERAL_LUNGE_RIGHT;
            return;
        }
        if (lower.contains("brr") || lower.contains("becken") || lower.contains("rotation")) {
            this.movement = Movement.BECKEN_RUMPF_ROTATION;
            return;
        }

        System.err.println("Unbekannter Bewegungsname: " + movementName + " – Movement bleibt auf altem Wert.");
    }

    public List<FrameData> getFrameDataList() {
        return frameDataList;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public Movement getMovement() {
        return movement;
    }

    public void setNormalizedAngles(Joints joint, double[] angles101Points) {
        this.normalizedJointAngles.put(joint, angles101Points);
    }

    public double[] getNormalizedAngles(Joints joint) {
        return this.normalizedJointAngles.get(joint);
    }

    public Map<Joints, double[]> getNormalizedJointAngles() {
        return normalizedJointAngles;
    }

    public boolean isOpenCap() { return isOpenCap; }

    @Override
    public String toString() {
        return String.format("Trial { file='%s', movement=%s, frames=%d, normalizedJoints=%d }",
                sourceFileName,
                movement != null ? movement.name() : "unbekannt",
                frameDataList.size(),
                normalizedJointAngles.size());
    }
}