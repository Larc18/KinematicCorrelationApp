package de.cwelsch.kinematiccorrelationapp.model;

/**
 * Point3D is the mathematical base for calculating and visualizing points in a three-dimensional space.
 * Extended with vector algebra functionalities for biomechanical joint angle calculations.
 *
 * @author carlwelsch
 */
public class Point3D {

    private final double x;
    private final double y;
    private final double z;

    public Point3D(final double x, final double y, final double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * Subtracts another point from this point, creating a direction vector.
     */
    public Point3D subtract(final Point3D other) {
        return new Point3D(this.x - other.x, this.y - other.y, this.z - other.z);
    }

    /**
     * Adds another point to this point.
     */
    public Point3D add(final Point3D other) {
        return new Point3D(this.x + other.x, this.y + other.y, this.z + other.z);
    }

    /**
     * Calculates the dot product (Skalarprodukt) between this vector and another vector.
     */
    public double dotProduct(final Point3D other) {
        return (this.x * other.x) + (this.y * other.y) + (this.z * other.z);
    }

    /**
     * Calculates the magnitude (length) of this vector.
     */
    public double magnitude() {
        return Math.sqrt((this.x * this.x) + (this.y * this.y) + (this.z * this.z));
    }

    /**
     * Calculates the enclosed angle in degrees between two vectors sharing the same origin.
     * Guarantees safe mathematical boundaries to prevent NaN results during arccos extraction.
     */
    public static double calculateAngle(Point3D vector1, Point3D vector2) {
        double mag1 = vector1.magnitude();
        double mag2 = vector2.magnitude();

        if (mag1 == 0 || mag2 == 0) {
            return 0.0;
        }

        // cos(theta) = (u * v) / (|u| * |v|)
        double cosTheta = vector1.dotProduct(vector2) / (mag1 * mag2);

        // Numerischen Überlauf am Randbereich abfangen (z.B. 1.0000001 durch Rundung)
        cosTheta = Math.max(-1.0, Math.min(1.0, cosTheta));

        // Umrechnung von Bogenmaß (Radians) in Grad (Degrees)
        return Math.toDegrees(Math.acos(cosTheta));
    }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }

    @Override
    public String toString() {
        return String.format("X: %7.2f | Y: %7.2f | Z: %7.2f", x, y, z);
    }
}