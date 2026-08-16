package de.cwelsch.kinematiccorrelationapp;

import de.cwelsch.kinematiccorrelationapp.model.Trial;
import de.cwelsch.kinematiccorrelationapp.model.TrialSession;
import de.cwelsch.kinematiccorrelationapp.services.exportservice.CsvExportService;
import de.cwelsch.kinematiccorrelationapp.services.exportservice.ExportService;
import de.cwelsch.kinematiccorrelationapp.services.exportservice.HtmlChartExportService;
import de.cwelsch.kinematiccorrelationapp.services.statisticalanalysisservice.AnalysisResults;
import de.cwelsch.kinematiccorrelationapp.services.timenormalizationservice.TimeNormalizationService;
import de.cwelsch.kinematiccorrelationapp.services.validationservice.BiomechanicalValidationService;
import de.cwelsch.kinematiccorrelationapp.services.validationservice.TechnicalValidationService;
import de.cwelsch.kinematiccorrelationapp.services.validationservice.ValidationReport;
import de.cwelsch.kinematiccorrelationapp.services.importservice.TrialSessionBuilder;
import de.cwelsch.kinematiccorrelationapp.services.statisticalanalysisservice.StatisticalAnalysisService;

import java.io.IOException;
import java.util.List;

/**
 * Orchestrates the entire data processing pipeline: data import, multi-stage
 * validation, time-normalization, statistical analysis, and file export.
 */
public class ProcessOrchestrator {

    private final String folderURL;
    private List<TrialSession> trialSessions;

    private final TechnicalValidationService technicalValidationService;
    private final BiomechanicalValidationService biomechanicalValidationService;
    private final TimeNormalizationService timeNormalizationService;

    private StatisticalAnalysisService statisticalAnalysisService;
    //private ExportService exportService;
    private HtmlChartExportService htmlChartExportService;
    private CsvExportService csvExportService;

    /**
     * Creates a new orchestrator for the given data folder and initializes the stateless processing services.
     *
     * @param folderURL the path to the folder containing the raw trial data to process
     */
    public ProcessOrchestrator(String folderURL) {
        this.folderURL = folderURL;
        // Initialize stateless processing services once during construction
        this.technicalValidationService = new TechnicalValidationService();
        this.biomechanicalValidationService = new BiomechanicalValidationService();
        this.timeNormalizationService = new TimeNormalizationService();
    }

    /**
     * Executes the sequential stages of the kinematic data pipeline: import, technical and biomechanical
     * validation, time normalization, cross-subject statistical analysis, and export of the results.
     *
     * @throws IOException if file access or processing errors occur
     */
    public void start() throws IOException {
        System.out.println("========================================");
        System.out.println("Starting ProcessOrchestrator");
        System.out.println("========================================");

        // Stage 1: Data Import - Read all subject records
        TrialSessionBuilder trialSessionBuilder = new TrialSessionBuilder(folderURL);
        this.trialSessions = trialSessionBuilder.getTrialSessions();

        if (this.trialSessions == null || this.trialSessions.isEmpty()) {
            System.err.println("[Error] No trial sessions loaded. Aborting process.");
            return;
        }

        System.out.printf("Successfully loaded participants: %d%n", this.trialSessions.size());


        // Stage 2: Individual Subject Processing Pipeline
        int totalCriticalErrors = 0;
        int totalWarnings = 0;
        int totalExcludedTrials = 0;
        int totalFallbackTrials = 0;

        for (TrialSession session : this.trialSessions) {
            String participantId = session.getParticipant().getId();
            System.out.printf("%n--- Executing Pipeline for: %s ---%n", participantId);

            // Technical Validation (Format, limits, and sanity checks)
            System.out.println("  [1/3] Running Technical Validation...");
            ValidationReport technicalReport = this.technicalValidationService.validate(session);
            totalCriticalErrors += technicalReport.getErrors().size();
            totalWarnings += technicalReport.getWarnings().size();
            if (!technicalReport.isSuccessful()) {
                System.err.printf("  [Critical] Technical validation found %d error(s) for %s.%n",
                        technicalReport.getErrors().size(), participantId);
            }
            if (!technicalReport.getErrors().isEmpty() || !technicalReport.getWarnings().isEmpty()) {
                System.out.println(technicalReport);
            }

            // (a) Trials mit kritischen Validierungsfehlern wurden soeben von TechnicalValidationService
            // markiert (Trial.isExcluded()) und werden ab hier konsequent aus Normalisierung/Statistik/
            // Export ausgeschlossen, statt nur geloggt zu werden.
            List<Trial> excludedTrials = session.getTrials().stream()
                    .filter(t -> t != null && t.isExcluded())
                    .toList();
            if (!excludedTrials.isEmpty()) {
                totalExcludedTrials += excludedTrials.size();
                for (Trial excluded : excludedTrials) {
                    System.err.printf("  [Excluded] '%s' will NOT be included in statistics/export (Reason: %s).%n",
                            excluded.getSourceFileName(), excluded.getExclusionReason());
                }
            }

            // Time Normalization (Spline-interpolation onto a 101-point equidistant percentage scale)
            System.out.println("  [2/3] Executing Time Normalization (0% - 100%)...");
            this.timeNormalizationService.normalizeTrial(session);

            // (b) Zählt, wie viele (nicht ausgeschlossene) Trials keine echte Fensterdetektion
            // erreichten und stattdessen auf den kompletten Trial zurückgefallen sind.
            totalFallbackTrials += (int) session.getTrials().stream()
                    .filter(t -> t != null && !t.isExcluded() && t.isWindowFallbackUsed())
                    .count();

            // Biomechanical Validation (Anatomical angle constraints checking)
            System.out.println("  [3/3] Running Biomechanical Validation...");
            ValidationReport biomechanicalReport = this.biomechanicalValidationService.validate(session);
            totalCriticalErrors += biomechanicalReport.getErrors().size();
            totalWarnings += biomechanicalReport.getWarnings().size();
            if (!biomechanicalReport.getErrors().isEmpty() || !biomechanicalReport.getWarnings().isEmpty()) {
                System.out.println(biomechanicalReport);
            }
        }

        System.out.printf("%n[Validation Summary] %d critical error(s), %d warning(s) across %d participant(s).%n",
                totalCriticalErrors, totalWarnings, this.trialSessions.size());
        System.out.printf("[Validation Summary] %d trial(s) excluded from statistics/export due to critical errors.%n",
                totalExcludedTrials);
        System.out.printf("[Validation Summary] %d trial(s) used a fallback movement window (whole-trial, no reliable window detected).%n",
                totalFallbackTrials);


        // Stage 3: Cross-Subject Statistical Analysis
        // (ICC calculations require the multi-subject sample variance, hence processed outside the loop)
        System.out.println("%nStarting aggregate Statistical Analysis across all subjects...");
        this.statisticalAnalysisService = new StatisticalAnalysisService(this.trialSessions);
        AnalysisResults finalResults = this.statisticalAnalysisService.analyze();


        // Stage 4: Results Export
        System.out.println("Exporting processed metrics to filesystem...");
        this.htmlChartExportService = new HtmlChartExportService(folderURL);
        this.htmlChartExportService.exportAggregatedDashboard(this.trialSessions, finalResults);

        this.csvExportService = new CsvExportService(folderURL);
        this.csvExportService.exportAggregatedMetricsToCsv(this.trialSessions, finalResults);

        //this.exportService = new ExportService(finalResults, folderURL);
        //this.exportService.export();


        System.out.println("%n========================================");
        System.out.println("ProcessOrchestrator FINISHED SUCCESSFULLY");
        System.out.println("========================================");
    }
}