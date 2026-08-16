package de.cwelsch.kinematiccorrelationapp.model;

import java.util.List;

/**
 * All joints used in this program. Also, all given joint by OpenCap. All joints have a openCapKey and a theiaKey to find them in the imported datafiles. Every joint has a displayName to display it on the screen.
 */
public enum Joints {

    // --- Becken ---
    PELVIS_TILT("pelvis_tilt", "pelvis", "Beckenkippung (Tilt)"),
    PELVIS_LIST("pelvis_list", "pelvis", "Beckenneigung (List)"),
    PELVIS_ROTATION("pelvis_rotation", "pelvis", "Beckenrotation"),
    PELVIS_TX("pelvis_tx", "pelvis", "Becken Translation X"),
    PELVIS_TY("pelvis_ty", "pelvis", "Becken Translation Y"),
    PELVIS_TZ("pelvis_tz", "pelvis", "Becken Translation Z"),

    // --- Rumpf / LWS ---
    LUMBAR_EXTENSION("lumbar_extension", "abdomen", "LWS Extension/Flexion"),
    LUMBAR_BENDING("lumbar_bending", "abdomen", "LWS Seitneigung"),
    LUMBAR_ROTATION("lumbar_rotation", "abdomen", "LWS Rotation"),

    // --- Untere Extremität rechts ---
    HIP_FLEXION_R("hip_flexion_r", "r_thigh", "Rechte Hüfte Flexion/Extension"),
    HIP_ADDUCTION_R("hip_adduction_r", "r_thigh", "Rechte Hüfte Adduktion/Abduktion"),
    HIP_ROTATION_R("hip_rotation_r", "r_thigh", "Rechte Hüfte Rotation"),

    KNEE_ANGLE_R("knee_angle_r", "r_shank", "Rechtes Knie Flexion/Extension"),

    ANKLE_ANGLE_R("ankle_angle_r", "r_foot", "Rechtes Oberes Sprunggelenk"),
    SUBTALAR_ANGLE_R("subtalar_angle_r", "r_foot", "Rechtes Unteres Sprunggelenk"),
    MTP_ANGLE_R("mtp_angle_r", "r_toes", "Rechtes Zehengrundgelenk"),

    // --- Untere Extremität links ---
    HIP_FLEXION_L("hip_flexion_l", "l_thigh", "Linke Hüfte Flexion/Extension"),
    HIP_ADDUCTION_L("hip_adduction_l", "l_thigh", "Linke Hüfte Adduktion/Abduktion"),
    HIP_ROTATION_L("hip_rotation_l", "l_thigh", "Linke Hüfte Rotation"),

    KNEE_ANGLE_L("knee_angle_l", "l_shank", "Linkes Knie Flexion/Extension"),

    ANKLE_ANGLE_L("ankle_angle_l", "l_foot", "Linkes Oberes Sprunggelenk"),
    SUBTALAR_ANGLE_L("subtalar_angle_l", "l_foot", "Linkes Unteres Sprunggelenk"),
    MTP_ANGLE_L("mtp_angle_l", "l_toes", "Linkes Zehengrundgelenk"),

    // --- Obere Extremität rechts ---
    ARM_FLEX_R("arm_flex_r", "r_uarm", "Rechte Schulter Flexion/Extension"),
    ARM_ADDUCTION_R("arm_add_r", "r_uarm", "Rechte Schulter Adduktion/Abduktion"),
    ARM_ROTATION_R("arm_rot_r", "r_uarm", "Rechte Schulter Rotation"),

    ELBOW_FLEX_R("elbow_flex_r", "r_larm", "Rechter Ellbogen Flexion/Extension"),
    PRO_SUP_R("pro_sup_r", "r_hand", "Rechter Unterarm Pronation/Supination"),

    // --- Obere Extremität links ---
    ARM_FLEX_L("arm_flex_l", "l_uarm", "Linke Schulter Flexion/Extension"),
    ARM_ADDUCTION_L("arm_add_l", "l_uarm", "Linke Schulter Adduktion/Abduktion"),
    ARM_ROTATION_L("arm_rot_l", "l_uarm", "Linke Schulter Rotation"),

    ELBOW_FLEX_L("elbow_flex_l", "l_larm", "Linker Ellbogen Flexion/Extension"),
    PRO_SUP_L("pro_sup_l", "l_hand", "Linker Unterarm Pronation/Supination");


    // attributes
    private final String openCapKey;
    private final String theiaKey;
    private final String displayName;

    /**
     * Constructor for Joint
     *
     * @param openCapKey  key which is found in openCap data.
     * @param theiaKey    key which is found in theia data.
     * @param displayName String which is used to display on screens.
     */
    Joints(String openCapKey, String theiaKey, String displayName) {
        this.openCapKey = openCapKey;
        this.theiaKey = theiaKey;
        this.displayName = displayName;
    }

    // getter
    public String getOpenCapKey() {
        return openCapKey;
    }

    public String getTheiaKey() {
        return theiaKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Fachlicher Analyseumfang der Studie: nur Hüfte (Flexion/Extension), Knie und
     * Sprunggelenk werden validiert, normalisiert und exportiert. Andere Gelenke
     * (Becken, LWS, obere Extremität, sowie Hüft-Adduktion/-Rotation und unteres
     * Sprunggelenk/Zehen) werden zwar importiert — z.B. weil die BRR-Fenstererkennung
     * PELVIS_ROTATION/LUMBAR_ROTATION benötigt — tauchen aber nirgends im Ergebnis auf.
     * HIP_ADDUCTION_R/L und HIP_ROTATION_R/L werden seit der Umstellung auf die volle
     * XYZ-Cardan-Zerlegung (siehe TheiaMarkerlessParser.computeRelativeJointAngles)
     * zwar auf beiden Seiten berechnet, sind aber bewusst nicht Teil des Studienumfangs
     * geblieben (keine primäre Fragestellung, kleinere ROM, weniger literaturbasierte
     * Referenzwerte zur Plausibilisierung) — nicht weil sie technisch fehlen würden.
     */
    public static final List<Joints> ANALYSIS_SCOPE = List.of(
            HIP_FLEXION_R, HIP_FLEXION_L,
            KNEE_ANGLE_R, KNEE_ANGLE_L,
            ANKLE_ANGLE_R, ANKLE_ANGLE_L
    );
}