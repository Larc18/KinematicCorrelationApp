package de.cwelsch.kinematiccorrelationapp.services.validationservice;

import de.cwelsch.kinematiccorrelationapp.model.FrameData;
import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Trial;
import de.cwelsch.kinematiccorrelationapp.model.TrialSession;

import java.util.List;
import java.util.Set;

/**
 * Concrete validation service responsible for technical data integrity verification.
 * Audits kinematic file sets for tracking gaps, continuous frame sequence indexing,
 * and confirms the mathematical presence and numerical sanity of lower-extremity priority metrics.
 */
public class TechnicalValidationService implements ValidationService<TrialSession> {

    // Deckt sich bewusst mit Joints.ANALYSIS_SCOPE (Hüfte/Knie/Sprunggelenk) — nur Winkel,
    // die tatsächlich in Statistik/Export landen, werden auch kritisch validiert.
    private static final Set<Joints> CRITICAL_JOINTS = Set.copyOf(Joints.ANALYSIS_SCOPE);

    /**
     * Audits every trial in the session for structural frame-sequence gaps and missing or NaN
     * values on the critical (analysis-scope) joints, marking trials with critical failures as excluded.
     *
     * @param trialSession the session whose trials are validated
     * @return a report listing the technical errors and warnings found
     */
    @Override
    public ValidationReport validate(TrialSession trialSession) {
        System.out.println("Starting TechnicalValidationService...");
        ValidationReport report = new ValidationReport();

        if (trialSession == null || trialSession.getTrials() == null || trialSession.getTrials().isEmpty()) {
            report.addError("Technical Failure: The target TrialSession contains no data subsets or tracking channels.");
            return report;
        }

        List<Trial> trials = trialSession.getTrials();

        for (int i = 0; i < trials.size(); i++) {
            Trial trial = trials.get(i);
            if (trial == null) {
                continue;
            }

            String fileName = trial.getSourceFileName() != null ? trial.getSourceFileName() : "Trial_Index_" + i;
            List<FrameData> frames = trial.getFrameDataList();

            if (frames.isEmpty()) {
                report.addError(
                        "Critical Failure: Data file tracking layer extracted but contains empty frame metrics.",
                        fileName, -1, 0.0
                );
                // CRITICAL FIX (a): Trials mit kritischen Fehlern werden ab sofort aus der
                // weiteren Normalisierung/Statistik ausgeschlossen statt nur geloggt zu werden.
                trial.markExcluded("Empty frame metrics");
                continue;
            }

            // Hole den realen Startframe der Datei als dynamische Baseline
            int expectedFrameNumber = frames.get(0).frame();
            double expectedTime = frames.get(0).second();

            // Angenommene Framerate dynamisch aus den ersten zwei Frames ermitteln (~60Hz -> ~0.0166s)
            double deltaTimeBaseline = (frames.size() > 1) ? (frames.get(1).second() - frames.get(0).second()) : 0.0166;

            for (FrameData frame : frames) {

                // Check 1: Strukturelle Frame-Lücke detektieren
                if (frame.frame() != expectedFrameNumber) {
                    int missingFrames = frame.frame() - expectedFrameNumber;

                    if (missingFrames > 0) {
                        report.addError(
                                String.format("Critical Timeline Gap: Detected missing sequence gap of %d frame(s).", missingFrames),
                                fileName, expectedFrameNumber, expectedTime
                        );
                        trial.markExcluded(String.format("Timeline gap of %d frame(s) at frame %d", missingFrames, expectedFrameNumber));
                    } else {
                        report.addError(
                                "Critical Structural Anomaly: Out-of-order frame index or duplicate frame detected.",
                                fileName, frame.frame(), frame.second()
                        );
                        trial.markExcluded(String.format("Out-of-order/duplicate frame at frame %d", frame.frame()));
                    }
                    // Synchronisiere Erwartungshorizont wieder auf den aktuellen realen Frame
                    expectedFrameNumber = frame.frame();
                }

                // Check 2: Gelenkwinkel auf Vorhandensein UND numerische Gültigkeit (NaN) prüfen
                for (Joints criticalJoint : CRITICAL_JOINTS) {
                    Double angle = frame.jointAngle().get(criticalJoint);

                    // CRITICAL FIX: fängt jetzt null UND NaN-Werte aus den MoCap-Systemen ab
                    if (angle == null || Double.isNaN(angle)) {
                        report.addWarning(
                                "Mandatory coordinate tracking cell for [" + criticalJoint.name() + "] is missing or invalid (NaN).",
                                fileName, frame.frame(), frame.second()
                        );
                    }
                }

                // Erwartungswerte für den nächsten Schleifendurchlauf inkrementieren
                expectedFrameNumber++;
                expectedTime += deltaTimeBaseline;
            }
        }

        System.out.println("Technical Validation complete. Errors found: " + report.getErrors().size() + ", Warnings: " + report.getWarnings().size());
        return report;
    }
}