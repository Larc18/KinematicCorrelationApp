package de.cwelsch.kinematiccorrelationapp.services.importservice;

/**
 * Berechnungsvariante für die Theia-Knie- und Sprunggelenkwinkel.
 */
public enum AngleMethod {
    /** Relative Segment-Rotation mit XYZ-Cardan-Zerlegung, analog zur Hüfte (Standard). */
    CARDAN,
    /** Früherer 3D-Vektorwinkel aus den Gelenkzentren, nur noch als Vergleichsvariante. */
    VECTOR
}
