package de.cwelsch.kinematiccorrelationapp.services.importservice;

import de.cwelsch.kinematiccorrelationapp.model.Trial;
import de.cwelsch.kinematiccorrelationapp.model.TrialSession;
import de.cwelsch.kinematiccorrelationapp.model.participant.Participant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Traverses the filesystem directory layout of the data collection root.
 * Parses participant metadata and aggregates multi-system motion trials
 * into validated TrialSession domain objects.
 * Updated to support trial-by-trial pair validation and JSON file extensions.
 */
public class TrialSessionBuilder {

    private final Path rootFolder;
    private final List<TrialSession> trialSessions = new ArrayList<>();

    /**
     * Scans {@code rootFolderPath} for participant directories and builds a {@link TrialSession}
     * for each participant that has at least one successfully paired movement trial.
     *
     * @param rootFolderPath the root folder containing one subdirectory per participant
     * @throws IOException if the folder structure cannot be read
     */
    public TrialSessionBuilder(String rootFolderPath) throws IOException {
        this.rootFolder = Path.of(rootFolderPath);

        System.out.println("========================================");
        System.out.println("Starting TrialSessionBuilder");

        long startTime = System.currentTimeMillis();

        // Read all participant directories, sorted alphabetically
        List<Path> participantDirs;
        try (Stream<Path> stream = Files.list(rootFolder)) {
            participantDirs = stream
                    .filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        }

        if (participantDirs.isEmpty()) {
            System.err.println("[Warning] No participant directories found in: " + rootFolder);
            return;
        }

        for (Path participantDir : participantDirs) {
            processParticipant(participantDir);
        }

        long duration = System.currentTimeMillis() - startTime;
        System.out.printf("%n========================================%n");
        System.out.println("TrialSessionBuilder FINISHED");
        System.out.printf("Participants processed: %d%n", trialSessions.size());
        System.out.printf("Total execution time: %d ms%n", duration);
        System.out.println("========================================");
    }

    /**
     * Processes an individual participant directory.
     */
    private void processParticipant(Path participantDir) throws IOException {
        String participantName = participantDir.getFileName().toString();
        System.out.printf("%n--- Processing Participant: %s ---%n", participantName);

        Path metaDataPath = participantDir.resolve("sessionMetadata.yaml");
        if (!Files.exists(metaDataPath)) {
            System.err.printf("[Skip] No 'sessionMetadata.yaml' found for: %s%n", participantName);
            return;
        }

        ParticipantBuilder participantBuilder = new ParticipantBuilder(metaDataPath);
        Participant participant = participantBuilder.build();

        List<Path> movementDirs;
        try (Stream<Path> stream = Files.list(participantDir)) {
            movementDirs = stream
                    .filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        }

        if (movementDirs.isEmpty()) {
            System.err.printf("[Warning] No movement subdirectories found for: %s%n", participantName);
            return;
        }

        List<Trial> allTrials = new ArrayList<>();

        for (Path movementDir : movementDirs) {
            processMovement(movementDir, allTrials);
        }

        if (!allTrials.isEmpty()) {
            trialSessions.add(new TrialSession(participant, allTrials));
            System.out.printf("-> TrialSession successfully instantiated for: %s (%d trials)%n",
                    participantName, allTrials.size());
        }
    }

    /**
     * Validates and processes a specific movement target folder.
     * Allows uneven file counts as long as at least one paired system execution sequence matches.
     */
    private void processMovement(Path movementDir, List<Trial> allTrials) throws IOException {
        String movementName = movementDir.getFileName().toString();

        Path openCapDir = movementDir.resolve("opencap");
        Path theiaDir = movementDir.resolve("theia");

        long openCapFileCount = countValidFiles(openCapDir);
        long theiaFileCount = countValidFiles(theiaDir);

        // Geändertes Guard-Kriterium: Blockiert nur, wenn ein System komplett leer ist (wie bei CMJ, LLL etc.)
        if (openCapFileCount == 0 || theiaFileCount == 0) {
            System.err.printf("  [Skip] Movement '%s' incomplete – OpenCap: %d file(s), Theia: %d file(s). No tracking pairs possible.%n",
                    movementName, openCapFileCount, theiaFileCount);
            return;
        }

        System.out.printf("  Active movement directory verified: %s (OpenCap: %d, Theia: %d)%n",
                movementName, openCapFileCount, theiaFileCount);

        // Pipeline-Import starten
        ImportService importService = new ImportService(movementDir.toString());
        List<Trial> movementTrials = importService.getTrialList();

        int pairedCount = 0;
        // Wir laufen im Reißverschlussverfahren paarweise durch die importierte Liste
        for (int i = 0; i < movementTrials.size() - 1; i += 2) {
            Trial ocTrial = movementTrials.get(i);
            Trial thTrial = movementTrials.get(i + 1);

            // Nur wenn für diesen spezifischen Index BEIDE Partner erfolgreich importiert wurden
            if (ocTrial != null && thTrial != null) {
                ocTrial.setMovementName(movementName);
                thTrial.setMovementName(movementName);
                allTrials.add(ocTrial);
                allTrials.add(thTrial);
                pairedCount++;
            }
        }

        if (pairedCount > 0) {
            System.out.printf("    -> Successfully synchronized %d parallel paired trial(s) inside '%s'.%n",
                    pairedCount, movementName);
        }
    }

    /**
     * Safely counts compatible data files inside a system-specific target subdirectory.
     * Extended to accept .json extensions alongside legacy .csv and .mot data streams.
     */
    private long countValidFiles(Path targetDir) throws IOException {
        if (!Files.exists(targetDir) || !Files.isDirectory(targetDir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(targetDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> {
                        String lowerName = p.getFileName().toString().toLowerCase();
                        return lowerName.endsWith(".csv") || lowerName.endsWith(".mot") || lowerName.endsWith(".json");
                    })
                    .count();
        }
    }

    public List<TrialSession> getTrialSessions() {
        return trialSessions;
    }
}