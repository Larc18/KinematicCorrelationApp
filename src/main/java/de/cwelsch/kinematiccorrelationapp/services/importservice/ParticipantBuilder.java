package de.cwelsch.kinematiccorrelationapp.services.importservice;

import de.cwelsch.kinematiccorrelationapp.model.participant.Participant;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Factory builder responsible for coordinating the deserialization
 * of participant data profiles from local session metadata storage.
 */
public class ParticipantBuilder {

    private final Path metaDataURL;

    /**
     * Instantiates a new ParticipantBuilder configuration profile.
     * * @param metaDataURL The filesystem path pointing to the sessionMetadata.yaml file.
     */
    public ParticipantBuilder(Path metaDataURL) {
        this.metaDataURL = metaDataURL;
    }

    /**
     * Executes the reading lifecycle and instantiates the Participant model entity.
     * * @return A fully populated Participant object entity, or null if instantiation fails.
     */
    public Participant build() {
        // Validation boundary check before passing the path downstream
        if (metaDataURL == null || !Files.exists(metaDataURL)) {
            System.err.println("[Error] Cannot build participant: Metadata path is invalid or target file does not exist.");
            return null;
        }

        YamlReader yamlReader = new YamlReader();

        // Using explicit absolute path transformation instead of raw String conversion
        String absolutePathString = metaDataURL.toAbsolutePath().toString();
        return yamlReader.readParticipant(absolutePathString);
    }
}