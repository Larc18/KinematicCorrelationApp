package de.cwelsch.kinematiccorrelationapp.model.participant;

import com.fasterxml.jackson.annotation.JsonProperty;


/**
 * A list of the two biological genders.
 */
public enum Gender {
    @JsonProperty("Man")
    MALE,
    @JsonProperty("Woman")
    FEMALE;
}
