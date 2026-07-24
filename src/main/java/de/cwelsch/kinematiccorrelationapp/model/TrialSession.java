package de.cwelsch.kinematiccorrelationapp.model;

import de.cwelsch.kinematiccorrelationapp.model.participant.Participant;

import java.util.ArrayList;
import java.util.List;

/**
 * TrialSession connects the participant who does a whole session of trials.
 */
public class TrialSession {

    private final Participant participant;
    private List<Trial> trials = new ArrayList<>();

    public TrialSession(Participant participant, List<Trial> trials) {
        this.participant = participant;
        this.trials = trials;
    }

    public Participant getParticipant() {
        return participant;
    }

    public List<Trial> getTrials() {
        return trials;
    }
}
