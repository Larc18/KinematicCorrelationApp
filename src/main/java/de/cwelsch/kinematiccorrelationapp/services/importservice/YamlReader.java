package de.cwelsch.kinematiccorrelationapp.services.importservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import de.cwelsch.kinematiccorrelationapp.model.participant.Participant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility service responsible for deserializing participant profile data
 * and demographics from structured YAML session metadata configuration files.
 */
public class YamlReader {

    // Reuse the mapper instance as a constant to avoid the heavy overhead of recreating it on every read
    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    /**
     * Reads a YAML metadata file and deserializes it into a Participant domain model.
     *
     * @param fullFilePath The absolute filesystem path string pointing to the target file.
     * @return A populated Participant entity instance, or null if deserialization fails.
     */
    public Participant readParticipant(String fullFilePath) {
        if (fullFilePath == null || fullFilePath.trim().isEmpty()) {
            System.err.println("[Error] Provided file path string is null or empty.");
            return null;
        }

        Path targetPath = Path.of(fullFilePath);

        if (!Files.exists(targetPath)) {
            System.err.printf("[Error] Specified metadata file layout does not exist: %s%n", fullFilePath);
            return null;
        }

        try {
            // Using nio Files.newInputStream handles file streams safely under the hood
            return MAPPER.readValue(Files.newInputStream(targetPath), Participant.class);
        } catch (IOException e) {
            System.err.printf("[Exception] Failed to deserialize YAML session metadata: %s%n", fullFilePath);
            e.printStackTrace();
            return null;
        }
    }
}