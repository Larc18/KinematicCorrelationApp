package de.cwelsch.kinematiccorrelationapp.services.exportservice;

import de.cwelsch.kinematiccorrelationapp.model.Trial;
import de.cwelsch.kinematiccorrelationapp.model.TrialSession;
import de.cwelsch.kinematiccorrelationapp.model.Joints;
import de.cwelsch.kinematiccorrelationapp.model.Movement;
import de.cwelsch.kinematiccorrelationapp.services.statisticalanalysisservice.AnalysisResults;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Advanced Biomechanical Export Service.
 * Generates a structured validation dashboard with standard deviation bands and NRMSE integration.
 * Guarantees a strict pedagogical ordering for movements and joints across text and charts.
 */
public class HtmlChartExportService {

    private final String targetFolderURL;

    // Fixierte Reihenfolge für die Bewegungsformen gemäß sportwissenschaftlichem Protokoll
    private static final List<Movement> MOVEMENT_ORDER = List.of(
            Movement.SQUAT,
            Movement.COUNTERMOVEMENT_JUMP,
            Movement.LATERAL_LUNGE_LEFT,
            Movement.LATERAL_LUNGE_RIGHT,
            Movement.BECKEN_RUMPF_ROTATION
    );

    // Fachlicher Analyseumfang der Studie: nur Hüfte/Knie/Sprunggelenk, siehe Joints.ANALYSIS_SCOPE.
    private static final List<Joints> JOINT_ORDER = Joints.ANALYSIS_SCOPE;

    // Klinischer Referenzwert für den geplanten Einstichproben-t-Test der Pro-Proband-RMSE
    // (H0: mu >= 8° vs. H1: mu < 8°). Dient hier nur der informativen Einordnung der Werte im
    // Export, die eigentliche Testberechnung erfolgt extern (R/Python).
    private static final double REFERENCE_THRESHOLD_DEGREES = 8.0;

    public HtmlChartExportService(String targetFolderURL) {
        this.targetFolderURL = targetFolderURL;
    }

    /**
     * Generates a self-contained HTML dashboard summarizing the aggregated ICC/RMSE/NRMSE quality metrics
     * and per-movement, per-joint trajectory charts (mean ±1 SD) comparing OpenCap and Theia.
     *
     * @param trialSessions      the participant sessions whose trials are aggregated for the charts
     * @param statisticalResults the previously computed ICC/RMSE/NRMSE results per movement and joint
     * @throws IOException if the HTML file cannot be written
     */
    public void exportAggregatedDashboard(List<TrialSession> trialSessions, AnalysisResults statisticalResults) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path exportPath = Path.of(targetFolderURL, "biomechanical_dashboard_" + timestamp + ".html");

        System.out.println("========================================");
        System.out.println("Starting Aggregated HtmlChartExportService");
        System.out.println("Export destination: " + exportPath.toAbsolutePath());

        Map<Movement, Map<Joints, List<double[]>>> openCapAggregates = new HashMap<>();
        Map<Movement, Map<Joints, List<double[]>>> theiaAggregates = new HashMap<>();

        // (b) Zählt pro Movement/Joint/System mit, wie viele der beitragenden Trials keine echte
        // Bewegungsfenster-Erkennung erreicht haben und stattdessen auf den kompletten Trial
        // zurückgefallen sind (int[]{totalTrials, fallbackTrials}).
        Map<Movement, Map<Joints, int[]>> openCapFallbackCounts = new HashMap<>();
        Map<Movement, Map<Joints, int[]>> theiaFallbackCounts = new HashMap<>();

        for (TrialSession session : trialSessions) {
            for (Trial trial : session.getTrials()) {
                if (trial == null || trial.getMovement() == null) continue;
                Movement mv = trial.getMovement();

                for (Joints joint : Joints.ANALYSIS_SCOPE) {
                    double[] normalized101 = trial.getNormalizedAngles(joint);
                    if (normalized101 == null || normalized101.length < 101) continue;

                    Map<Movement, Map<Joints, List<double[]>>> aggregates = trial.isOpenCap() ? openCapAggregates : theiaAggregates;
                    Map<Movement, Map<Joints, int[]>> fallbackCounts = trial.isOpenCap() ? openCapFallbackCounts : theiaFallbackCounts;

                    aggregates
                            .computeIfAbsent(mv, k -> new HashMap<>())
                            .computeIfAbsent(joint, k -> new ArrayList<>())
                            .add(normalized101);

                    int[] counts = fallbackCounts
                            .computeIfAbsent(mv, k -> new HashMap<>())
                            .computeIfAbsent(joint, k -> new int[2]);
                    counts[0]++;
                    if (trial.isWindowFallbackUsed()) counts[1]++;
                }
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(exportPath)) {
            writer.write("""
                    <!DOCTYPE html>
                    <html lang="de">
                    <head>
                        <meta charset="UTF-8">
                        <title>Validierungs-Dashboard: OpenCap vs. Theia</title>
                        <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
                        <style>
                            body { font-family: 'Segoe UI', Arial, sans-serif; margin: 40px; background-color: #f4f6f9; color: #2c3e50; }
                            .container { max-width: 1200px; margin: auto; }
                            h1 { color: #2c3e50; margin-bottom: 5px; }
                            .subtitle { color: #7f8c8d; font-size: 0.95em; margin-bottom: 30px; }
                            table { width: 100%; border-collapse: collapse; background: white; border-radius: 8px; overflow: hidden; box-shadow: 0 4px 6px rgba(0,0,0,0.05); margin-bottom: 40px; }
                            th, td { padding: 15px 20px; text-align: left; }
                            th { background-color: #34495e; color: white; font-weight: 600; text-transform: uppercase; font-size: 0.85em; }
                            tr:nth-child(even) { background-color: #f8f9fa; }
                            tr:hover { background-color: #f1f2f6; }
                            .badge { padding: 4px 8px; border-radius: 4px; font-weight: bold; font-size: 0.9em; }
                            .badge-good { background-color: #2ecc71; color: white; }
                            .badge-acceptable { background-color: #f1c40f; color: #2c3e50; }
                            .badge-poor { background-color: #e74c3c; color: white; }
                            .chart-section { background: white; padding: 25px; border-radius: 8px; box-shadow: 0 4px 6px rgba(0,0,0,0.05); margin-bottom: 40px; }
                            .chart-container { height: 400px; position: relative; margin-top: 15px; }
                            .chart-meta { font-size: 0.82em; color: #95a5a6; margin-top: 6px; }
                        </style>
                    </head>
                    <body>
                        <div class="container">
                            <h1>Biomechanischer Systemvergleich (Aggregierte Analyse)</h1>
                    """);

            String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
            writer.write("<div class=\"subtitle\">Validierungsstudie: OpenCap vs. Laborreferenz (Theia) | Berichtsstand: "
                    + dateStr + " | Geladene Sessions: " + trialSessions.size() + "</div>");

            // ================= STAGE 1: TABELLE (STRICKTE REIHENFOLGE + NRMSE + BIAS) =================
            writer.write("""
                            <h2>1. Mittlere statistische Gütekriterien (Gesamtbewegung)</h2>
                            <div class="chart-meta" style="margin-bottom:15px;">
                                Bias = mittlere signierte Differenz (OpenCap &minus; Theia) über den normierten
                                Bewegungszyklus, über die Probanden gemittelt. Ein positiver Bias bedeutet:
                                OpenCap liegt systematisch höher als Theia. Der zentrierte RMSE ist der RMSE
                                nach Abzug dieses konstanten Versatzes
                                (RMSE<sub>zentriert</sub>² = RMSE² &minus; Bias²) und beschreibt daher nur noch
                                die Abweichung im Kurvenverlauf.
                            </div>
                            <table>
                                <thead>
                                    <tr>
                                        <th>Bewegungsform</th>
                                        <th>Anatomischer Gelenkwinkel</th>
                                        <th>ICC (Mittelwert)</th>
                                        <th>RMSE (Mittelwert)</th>
                                        <th>Bias (Mittelwert)</th>
                                        <th>RMSE zentriert (Mittelwert)</th>
                                        <th>NRMSE (Mittelwert)</th>
                                        <th>Validitätsstufe</th>
                                        <th>Fenster-Fallback</th>
                                    </tr>
                                </thead>
                                <tbody>
                    """);

            // Äußere Schleife über die fixierte Bewegungsreihenfolge
            for (Movement mv : MOVEMENT_ORDER) {
                if (!openCapAggregates.containsKey(mv)) continue;

                // Innere Schleife über die proximal-distale Gelenkreihenfolge
                for (Joints joint : JOINT_ORDER) {
                    if (!openCapAggregates.get(mv).containsKey(joint)) continue;

                    double[] iccArray = statisticalResults.getIccForJoint(mv, joint);
                    double[] rmseArray = statisticalResults.getRmseForJoint(mv, joint);
                    double[] nrmseArray = statisticalResults.getNrmseForJoint(mv, joint);

                    if (iccArray == null || rmseArray == null) continue;

                    DoubleSummaryStatistics iccStats = Arrays.stream(iccArray).filter(v -> !Double.isNaN(v)).summaryStatistics();
                    DoubleSummaryStatistics rmseStats = Arrays.stream(rmseArray).filter(v -> !Double.isNaN(v)).summaryStatistics();

                    // CRITICAL FIX: Ein leerer Stream (alle 101 Punkte NaN) lieferte vorher
                    // getAverage()==0.0 -> täuschte fälschlich ein perfektes RMSE (0°) und ein
                    // katastrophales ICC (0.0) vor, obwohl schlicht keine Daten vorlagen.
                    if (iccStats.getCount() == 0 || rmseStats.getCount() == 0) continue;

                    double aggregateIcc = iccStats.getAverage();
                    double aggregateRmse = rmseStats.getAverage();
                    double aggregateNrmse = (nrmseArray != null) ? Arrays.stream(nrmseArray).filter(v -> !Double.isNaN(v)).summaryStatistics().getAverage() : 0.0;

                    // Bias/zentrierter RMSE sind Skalare (pro Proband über die Zeitachse gebildet,
                    // dann über die Kohorte gemittelt) und können fehlen, wenn für diese
                    // Movement/Joint-Kombination kein gematchtes OpenCap/Theia-Kurvenpaar vorlag.
                    String biasText = formatSignedDegrees(statisticalResults.getBiasForJoint(mv, joint));
                    String rmseCenteredText = formatDegrees(statisticalResults.getRmseCenteredForJoint(mv, joint));

                    String badge;
                    if (aggregateIcc >= 0.75) badge = "<span class=\"badge badge-good\">Exzellent</span>";
                    else if (aggregateIcc >= 0.40) badge = "<span class=\"badge badge-acceptable\">Moderat</span>";
                    else badge = "<span class=\"badge badge-poor\">Mangelhaft</span>";

                    // (b) Fenster-Fallback-Quote über beide Systeme hinweg sichtbar machen, statt
                    // sie stillschweigend in der Aggregation verschwinden zu lassen.
                    int[] ocCounts = openCapFallbackCounts.getOrDefault(mv, Map.of()).getOrDefault(joint, new int[2]);
                    int[] thCounts = theiaFallbackCounts.getOrDefault(mv, Map.of()).getOrDefault(joint, new int[2]);
                    int fallbackTrials = ocCounts[1] + thCounts[1];
                    int totalTrials = ocCounts[0] + thCounts[0];
                    String fallbackBadge = fallbackTrials == 0
                            ? "<span class=\"badge badge-good\">0/" + totalTrials + "</span>"
                            : "<span class=\"badge badge-poor\">" + fallbackTrials + "/" + totalTrials + "</span>";

                    writer.write(String.format(Locale.GERMAN, """
                                        <tr>
                                            <td><strong>%s</strong></td>
                                            <td>%s</td>
                                            <td>%.4f</td>
                                            <td>%.2f°</td>
                                            <td>%s</td>
                                            <td>%s</td>
                                            <td>%.1f%%</td>
                                            <td>%s</td>
                                            <td>%s</td>
                                        </tr>
                            """, mv.name(), joint.getDisplayName(), aggregateIcc, aggregateRmse,
                            biasText, rmseCenteredText, aggregateNrmse, badge, fallbackBadge));
                }
            }
            writer.write("</tbody></table>");

            // ================= STAGE 2: PRO-PROBAND RMSE/BIAS (BASIS FÜR EINSTICHPROBEN-T-TEST) =================
            writePerParticipantRmseSection(writer,
                    statisticalResults.getPerParticipantMeanRmse(),
                    statisticalResults.getPerParticipantMeanBias(),
                    statisticalResults.getPerParticipantMeanRmseCentered());

            // ================= STAGE 3: TRAJEKTORIEN (STRIKTE REIHENFOLGE) =================
            writer.write("<h2>3. Parallele Gelenkwinkel-Trajektorien im inter-systemischen Vergleich</h2>");

            // Äußere Grafiken-Schleife über die fixierte Bewegungsreihenfolge
            for (Movement mv : MOVEMENT_ORDER) {
                if (!openCapAggregates.containsKey(mv)) continue;
                Map<Joints, List<double[]>> jointMap = openCapAggregates.get(mv);

                // Innere Grafiken-Schleife über die Gelenkreihenfolge (bereits auf Hüfte/Knie/
                // Sprunggelenk begrenzt durch JOINT_ORDER = Joints.ANALYSIS_SCOPE — eine
                // zusätzliche Pro-Movement-Filterung ist daher nicht mehr nötig)
                for (Joints joint : JOINT_ORDER) {
                    if (!jointMap.containsKey(joint)) continue;

                    List<double[]> ocTrials = jointMap.get(joint);
                    List<double[]> thTrials = theiaAggregates.containsKey(mv) ? theiaAggregates.get(mv).get(joint) : null;

                    if (ocTrials == null || ocTrials.isEmpty()) continue;

                    double[] meanOC = calculateMeanVector(ocTrials);
                    double[] sdOC = calculateSdVector(ocTrials, meanOC);
                    double[] upperOC = addVectors(meanOC, sdOC);
                    double[] lowerOC = subtractVectors(meanOC, sdOC);

                    double[] meanTH, sdTH, upperTH, lowerTH;
                    if (thTrials != null && !thTrials.isEmpty()) {
                        meanTH = calculateMeanVector(thTrials);
                        sdTH = calculateSdVector(thTrials, meanTH);
                        upperTH = addVectors(meanTH, sdTH);
                        lowerTH = subtractVectors(meanTH, sdTH);
                    } else {
                        meanTH = meanOC;
                        sdTH = new double[101];
                        upperTH = meanOC;
                        lowerTH = meanOC;
                    }

                    int nOC = ocTrials.size();
                    int nTH = (thTrials != null) ? thTrials.size() : 0;

                    // (b) Fensterfallback-Quote je System, direkt neben der Trial-Anzahl sichtbar.
                    int[] ocFallback = openCapFallbackCounts.getOrDefault(mv, Map.of()).getOrDefault(joint, new int[2]);
                    int[] thFallback = theiaFallbackCounts.getOrDefault(mv, Map.of()).getOrDefault(joint, new int[2]);

                    String chartId = "chart_" + mv.name().toLowerCase() + "_" + joint.name().toLowerCase();

                    writer.write("<div class=\"chart-section\">");
                    writer.write("<h3 style='margin-top:0; border-bottom:1px solid #f1f2f6; padding-bottom:8px;'>"
                            + mv.name() + " &mdash; " + joint.getDisplayName() + "</h3>");
                    writer.write("<div class=\"chart-container\"><canvas id=\"" + chartId + "\"></canvas></div>");
                    writer.write("<div class=\"chart-meta\">OpenCap: n=" + nOC + " Trials (Fallback: " + ocFallback[1] + "/" + ocFallback[0]
                            + ") &nbsp;|&nbsp; Theia: n=" + nTH + " Trials (Fallback: " + thFallback[1] + "/" + thFallback[0]
                            + ") &nbsp;|&nbsp; Schattierung: ±1 Standardabweichung</div>");
                    writer.write("</div>");

                    writer.write(String.format("""
                                    <script>
                                    (function() {
                                        const labels = Array.from({length: 101}, (_, i) => i + '%%');
                                        new Chart(document.getElementById('%s').getContext('2d'), {
                                            type: 'line',
                                            data: {
                                                labels: labels,
                                                datasets: [
                                                    {
                                                        label: 'OpenCap +1SD',
                                                        data: %s,
                                                        borderColor: 'transparent',
                                                        backgroundColor: 'rgba(52,152,219,0.12)',
                                                        pointRadius: 0,
                                                        fill: '+1',
                                                        tension: 0.3
                                                    },
                                                    {
                                                        label: 'OpenCap -1SD',
                                                        data: %s,
                                                        borderColor: 'transparent',
                                                        backgroundColor: 'rgba(52,152,219,0.12)',
                                                        pointRadius: 0,
                                                        fill: false,
                                                        tension: 0.3
                                                    },
                                                    {
                                                        label: 'OpenCap (Mittelwert)',
                                                        data: %s,
                                                        borderColor: '#3498db',
                                                        backgroundColor: 'transparent',
                                                        borderWidth: 3,
                                                        pointRadius: 0,
                                                        fill: false,
                                                        tension: 0.3
                                                    },
                                                    {
                                                        label: 'Theia +1SD',
                                                        data: %s,
                                                        borderColor: 'transparent',
                                                        backgroundColor: 'rgba(231,76,60,0.10)',
                                                        pointRadius: 0,
                                                        fill: '+1',
                                                        tension: 0.3
                                                    },
                                                    {
                                                        label: 'Theia -1SD',
                                                        data: %s,
                                                        borderColor: 'transparent',
                                                        backgroundColor: 'rgba(231,76,60,0.10)',
                                                        pointRadius: 0,
                                                        fill: false,
                                                        tension: 0.3
                                                    },
                                                    {
                                                        label: 'Theia Markerless (Mittelwert)',
                                                        data: %s,
                                                        borderColor: '#e74c3c',
                                                        backgroundColor: 'transparent',
                                                        borderWidth: 3,
                                                        borderDash: [6,4],
                                                        pointRadius: 0,
                                                        fill: false,
                                                        tension: 0.3
                                                    }
                                                ]
                                            },
                                            options: {
                                                responsive: true,
                                                maintainAspectRatio: false,
                                                plugins: {
                                                    legend: {
                                                        labels: {
                                                            filter: function(item) {
                                                                return !item.text.includes('1SD');
                                                            },
                                                            boxWidth: 20,
                                                            font: { size: 12 }
                                                        },
                                                        position: 'top'
                                                    }
                                                },
                                                scales: {
                                                    x: { title: { display: true, text: 'Normierter Bewegungszyklus [%%]' } },
                                                    y: { title: { display: true, text: 'Gelenkwinkel [Grad °]' },
                                                         ticks: { callback: function(v) { return v + '°'; } } }
                                                }
                                            }
                                        });
                                    })();
                                    </script>
                                    """,
                            chartId,
                            Arrays.toString(upperOC), Arrays.toString(lowerOC), Arrays.toString(meanOC),
                            Arrays.toString(upperTH), Arrays.toString(lowerTH), Arrays.toString(meanTH)
                    ));
                }
            }

            writer.write("</div></body></html>");
            System.out.println("Export completed successfully.");
            System.out.println("========================================");
        }
    }

    /**
     * Schreibt eine eigenständige Sektion mit genau EINEM RMSE-, Bias- und zentrierten RMSE-Wert
     * PRO PROBAND (jeweils gemittelt über alle Bewegungen/Gelenke), inklusive Zusammenfassung
     * (n, Mittelwert, SD) und kopierbaren Wertelisten. Das ist die Datengrundlage für
     * Einstichproben-t-Tests (RMSE gegen einen fixen Referenzwert, Bias gegen 0) — im Gegensatz zu
     * Tabelle 1, die punktweise über die Kohorte gemittelte 101-Punkte-Kurven je Movement/Joint zeigt.
     */
    private void writePerParticipantRmseSection(BufferedWriter writer,
                                                Map<String, Double> perParticipantMeanRmse,
                                                Map<String, Double> perParticipantMeanBias,
                                                Map<String, Double> perParticipantMeanRmseCentered) throws IOException {
        if (perParticipantMeanRmse == null || perParticipantMeanRmse.isEmpty()) {
            return;
        }

        Map<String, Double> biasByParticipant = (perParticipantMeanBias != null) ? perParticipantMeanBias : Map.of();
        Map<String, Double> rmseCenteredByParticipant = (perParticipantMeanRmseCentered != null) ? perParticipantMeanRmseCentered : Map.of();

        List<String> sortedParticipantIds = new ArrayList<>(perParticipantMeanRmse.keySet());
        Collections.sort(sortedParticipantIds);

        DoubleSummaryStatistics stats = perParticipantMeanRmse.values().stream()
                .mapToDouble(Double::doubleValue)
                .summaryStatistics();
        int n = (int) stats.getCount();
        double mean = stats.getAverage();
        double variance = perParticipantMeanRmse.values().stream()
                .mapToDouble(v -> (v - mean) * (v - mean))
                .sum() / Math.max(1, n - 1);
        double sd = Math.sqrt(variance);
        long belowThreshold = perParticipantMeanRmse.values().stream()
                .filter(v -> v < REFERENCE_THRESHOLD_DEGREES)
                .count();

        writer.write("""
                        <h2>2. Pro-Proband RMSE und Bias (Basis für Einstichproben-t-Test)</h2>
                        <div class="chart-meta" style="margin-bottom:15px;">
                            Je ein RMSE-, Bias- und zentrierter RMSE-Wert pro Proband, gemittelt über alle
                            Bewegungen und Gelenke &mdash; geeignet als unabhängige Stichprobe für einen
                            Einstichproben-t-Test gegen einen fixen Referenzwert (z.B. H0: &mu; &ge; 8&deg;
                            vs. H1: &mu; &lt; 8&deg;) bzw. für einen Test des Bias gegen &mu; = 0
                            (Frage: besteht ein systematischer Versatz zwischen den Systemen?).
                        </div>
                """);

        writer.write(String.format(Locale.GERMAN, """
                        <table>
                            <thead><tr><th>Kennzahl</th><th>Wert</th></tr></thead>
                            <tbody>
                                <tr><td>Anzahl Probanden (n)</td><td>%d</td></tr>
                                <tr><td>Mittelwert RMSE</td><td>%.2f&deg;</td></tr>
                                <tr><td>Standardabweichung (SD) RMSE</td><td>%.2f&deg;</td></tr>
                                <tr><td>Probanden &lt; %.0f&deg;</td><td>%d von %d</td></tr>
                                <tr><td>Mittelwert Bias (OpenCap &minus; Theia)</td><td>%s</td></tr>
                                <tr><td>Standardabweichung (SD) Bias</td><td>%s</td></tr>
                                <tr><td>Mittelwert RMSE zentriert (ohne Versatz)</td><td>%s</td></tr>
                                <tr><td>Standardabweichung (SD) RMSE zentriert</td><td>%s</td></tr>
                            </tbody>
                        </table>
                """, n, mean, sd, REFERENCE_THRESHOLD_DEGREES, belowThreshold, n,
                formatSignedDegrees(meanOf(biasByParticipant)), formatDegrees(sdOf(biasByParticipant)),
                formatDegrees(meanOf(rmseCenteredByParticipant)), formatDegrees(sdOf(rmseCenteredByParticipant))));

        writer.write("""
                        <table>
                            <thead>
                                <tr>
                                    <th>Proband-ID</th>
                                    <th>Mittlerer RMSE (über alle Bewegungen/Gelenke)</th>
                                    <th>Mittlerer Bias (OpenCap &minus; Theia)</th>
                                    <th>Mittlerer RMSE zentriert (ohne Versatz)</th>
                                </tr>
                            </thead>
                            <tbody>
                """);
        for (String participantId : sortedParticipantIds) {
            double value = perParticipantMeanRmse.get(participantId);
            writer.write(String.format(Locale.GERMAN, """
                                <tr><td>%s</td><td>%.2f&deg;</td><td>%s</td><td>%s</td></tr>
                    """, escapeHtml(participantId), value,
                    formatSignedDegrees(biasByParticipant.get(participantId)),
                    formatDegrees(rmseCenteredByParticipant.get(participantId))));
        }
        writer.write("</tbody></table>");

        writer.write("<div class=\"chart-meta\">RMSE-Werteliste (kopierbar, Punkt als Dezimaltrennzeichen): "
                + escapeHtml(toCopyableValueList(sortedParticipantIds, perParticipantMeanRmse)) + "</div>");
        writer.write("<div class=\"chart-meta\">Bias-Werteliste (kopierbar, Punkt als Dezimaltrennzeichen): "
                + escapeHtml(toCopyableValueList(sortedParticipantIds, biasByParticipant)) + "</div>");
        writer.write("<div class=\"chart-meta\">Zentrierte RMSE-Werteliste (kopierbar, Punkt als Dezimaltrennzeichen): "
                + escapeHtml(toCopyableValueList(sortedParticipantIds, rmseCenteredByParticipant)) + "</div>");
    }

    /**
     * Baut eine kopierbare Werteliste in derselben Probandenreihenfolge wie die Tabelle, damit
     * RMSE-, Bias- und zentrierte RMSE-Liste zeilenweise zueinander passen. Fehlt für einen
     * Probanden ein Wert, steht an dieser Position "NA" statt eines stillschweigend verschobenen
     * Wertes (R/Python lesen "NA" als fehlenden Wert statt als 0).
     */
    private String toCopyableValueList(List<String> participantIds, Map<String, Double> valuesByParticipant) {
        return participantIds.stream()
                .map(id -> {
                    Double value = valuesByParticipant.get(id);
                    return (value == null || Double.isNaN(value)) ? "NA" : String.format(Locale.US, "%.4f", value);
                })
                .collect(Collectors.joining(", "));
    }

    /**
     * Mittelwert über die vorhandenen (nicht-NaN) Werte einer Pro-Proband-Map, oder NaN wenn keine
     * Werte vorliegen.
     */
    private double meanOf(Map<String, Double> valuesByParticipant) {
        return valuesByParticipant.values().stream()
                .filter(v -> v != null && !Double.isNaN(v))
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(Double.NaN);
    }

    /**
     * Stichproben-Standardabweichung (n-1) über die vorhandenen Werte einer Pro-Proband-Map,
     * oder NaN wenn weniger als zwei Werte vorliegen.
     */
    private double sdOf(Map<String, Double> valuesByParticipant) {
        List<Double> values = valuesByParticipant.values().stream()
                .filter(v -> v != null && !Double.isNaN(v))
                .toList();
        if (values.size() < 2) return Double.NaN;

        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        double sumSq = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum();
        return Math.sqrt(sumSq / (values.size() - 1));
    }

    /**
     * Formatiert einen Gradwert für die Tabellenausgabe. Fehlende Werte (null/NaN) werden als
     * Gedankenstrich dargestellt statt als 0&deg;, das fälschlich perfekte Übereinstimmung suggerieren würde.
     */
    private String formatDegrees(Double value) {
        if (value == null || Double.isNaN(value)) return "&ndash;";
        return String.format(Locale.GERMAN, "%.2f&deg;", value);
    }

    /**
     * Wie {@link #formatDegrees(Double)}, erzwingt aber ein explizites Vorzeichen. Beim Bias ist die
     * Richtung des Versatzes die eigentliche Information: + bedeutet OpenCap &gt; Theia.
     */
    private String formatSignedDegrees(Double value) {
        if (value == null || Double.isNaN(value)) return "&ndash;";
        return String.format(Locale.GERMAN, "%+.2f&deg;", value);
    }

    /**
     * Minimalistisches HTML-Escaping für aus Studiendaten stammende Freitextfelder (z.B.
     * Proband-IDs aus importierten JSON-Dateien), um versehentliches Markup-Einschleusen in den
     * generierten Export zu verhindern.
     */
    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private double[] calculateMeanVector(List<double[]> vectors) {
        double[] mean = new double[101];
        for (int i = 0; i < 101; i++) {
            double sum = 0;
            int count = 0;
            for (double[] vec : vectors) {
                if (i < vec.length && !Double.isNaN(vec[i])) {
                    sum += vec[i];
                    count++;
                }
            }
            mean[i] = count > 0 ? sum / count : 0.0;
        }
        return mean;
    }

    private double[] calculateSdVector(List<double[]> vectors, double[] mean) {
        double[] sd = new double[101];
        for (int i = 0; i < 101; i++) {
            double sumSq = 0;
            int count = 0;
            for (double[] vec : vectors) {
                if (i < vec.length && !Double.isNaN(vec[i])) {
                    double diff = vec[i] - mean[i];
                    sumSq += diff * diff;
                    count++;
                }
            }
            sd[i] = count > 1 ? Math.sqrt(sumSq / (count - 1)) : 0.0;
        }
        return sd;
    }

    private double[] addVectors(double[] a, double[] b) {
        double[] result = new double[a.length];
        for (int i = 0; i < a.length; i++) result[i] = a[i] + b[i];
        return result;
    }

    private double[] subtractVectors(double[] a, double[] b) {
        double[] result = new double[a.length];
        for (int i = 0; i < a.length; i++) result[i] = a[i] - b[i];
        return result;
    }
}