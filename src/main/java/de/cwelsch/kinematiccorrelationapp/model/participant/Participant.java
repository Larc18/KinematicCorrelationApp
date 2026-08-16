package de.cwelsch.kinematiccorrelationapp.model.participant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The class Participant holds the attributes of the person who did the test to use this information later on.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Participant {

    @JsonProperty("subjectID")
    private String id;

    @JsonProperty("gender_mf")
    private Gender gender;

    @JsonProperty("height_m")
    private double height;

    @JsonProperty("mass_kg")
    private double mass;

    public Participant() {
    }

    /**
     * Voll parametrisierter Konstruktor für die Erstellung im Code und in den Unit-Tests.
     */
    public Participant(String id, Gender gender, double height, double mass) {
        this.id = id;
        this.gender = gender;
        this.height = height;
        this.mass = mass;
    }

    public String getId() {
        return id;
    }

    public Gender getGender() {
        return gender;
    }

    public double getHeight() {
        return height;
    }

    public double getMass() {
        return mass;
    }

    @Override
    public String toString() {
        return "subjectId=" + id +
                ", gender=" + gender +
                ", heightM=" + height +
                ", massKg=" + mass;

    }
}
