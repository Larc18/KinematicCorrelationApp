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

    // Fixierte anatomische Reihenfolge: Proximal nach Distal (Becken/Rumpf -> Hüfte -> Knie -> Sprunggelenk)
    private static final List<Joints> JOINT_ORDER = List.of(
            Joints.PELVIS_TILT, Joints.PELVIS_LIST, Joints.PELVIS_ROTATION,
            Joints.LUMBAR_ROTATION,
            Joints.HIP_FLEXION_R, Joints.HIP_FLEXION_L,
            Joints.KNEE_ANGLE_R, Joints.KNEE_ANGLE_L,
            Joints.ANKLE_ANGLE_R, Joints.ANKLE_ANGLE_L
    );

    public HtmlChartExportService(String targetFolderURL) {
        this.targetFolderURL = targetFolderURL;
    }

    public void exportAggregatedDashboard(List<TrialSession> trialSessions, AnalysisResults statisticalResults) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path exportPath = Path.of(targetFolderURL, "biomechanical_dashboard_" + timestamp + ".html");

        System.out.println("========================================");
        System.out.println("Starting Aggregated HtmlChartExportService");
        System.out.println("Export destination: " + exportPath.toAbsolutePath());

        Map<Movement, Map<Joints, List<double[]>>> openCapAggregates = new HashMap<>();
        Map<Movement, Map<Joints, List<double[]>>> theiaAggregates   = new HashMap<>();

        for (TrialSession session : trialSessions) {
            for (Trial trial : session.getTrials()) {
                if (trial == null || trial.getMovement() == null) continue;
                Movement mv = trial.getMovement();

                for (Joints joint : Joints.values()) {
                    double[] normalized101 = trial.getNormalizedAngles(joint);
                    if (normalized101 == null || normalized101.length < 101) continue;

                    if (trial.isOpenCap()) {
                        openCapAggregates
                                .computeIfAbsent(mv, k -> new HashMap<>())
                                .computeIfAbsent(joint, k -> new ArrayList<>())
                                .add(normalized101);
                    } else {
                        theiaAggregates
                                .computeIfAbsent(mv, k -> new HashMap<>())
                                .computeIfAbsent(joint, k -> new ArrayList<>())
                                .add(normalized101);
                    }
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

            // ================= STAGE 1: TABELLE (STRICKTE REIHENFOLGE + NRMSE) =================
            writer.write("""
                        <h2>1. Mittlere statistische Gütekriterien (Gesamtbewegung)</h2>
                        <table>
                            <thead>
                                <tr>
                                    <th>Bewegungsform</th>
                                    <th>Anatomischer Gelenkwinkel</th>
                                    <th>ICC (Mittelwert)</th>
                                    <th>RMSE (Mittelwert)</th>
                                    <th>NRMSE (Mittelwert)</th>
                                    <th>Validitätsstufe</th>
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

                    double[] iccArray   = statisticalResults.getIccForJoint(mv, joint);
                    double[] rmseArray  = statisticalResults.getRmseForJoint(mv, joint);
                    double[] nrmseArray = statisticalResults.getNrmseForJoint(mv, joint);

                    if (iccArray == null || rmseArray == null) continue;

                    double aggregateIcc   = Arrays.stream(iccArray).filter(v -> !Double.isNaN(v)).summaryStatistics().getAverage();
                    double aggregateRmse  = Arrays.stream(rmseArray).filter(v -> !Double.isNaN(v)).summaryStatistics().getAverage();
                    double aggregateNrmse = (nrmseArray != null) ? Arrays.stream(nrmseArray).filter(v -> !Double.isNaN(v)).summaryStatistics().getAverage() : 0.0;

                    String badge;
                    if      (aggregateIcc >= 0.75) badge = "<span class=\"badge badge-good\">Exzellent</span>";
                    else if (aggregateIcc >= 0.40) badge = "<span class=\"badge badge-acceptable\">Moderat</span>";
                    else                           badge = "<span class=\"badge badge-poor\">Mangelhaft</span>";

                    writer.write(String.format("""
                                    <tr>
                                        <td><strong>%s</strong></td>
                                        <td>%s</td>
                                        <td>%.4f</td>
                                        <td>%.2f°</td>
                                        <td>%.1f%%</td>
                                        <td>%s</td>
                                    </tr>
                        """, mv.name(), joint.getDisplayName(), aggregateIcc, aggregateRmse, aggregateNrmse, badge));
                }
            }
            writer.write("</tbody></table>");

            // ================= STAGE 2: TRAJEKTORIEN (STRIKTE REIHENFOLGE) =================
            writer.write("<h2>2. Parallele Gelenkwinkel-Trajektorien im inter-systemischen Vergleich</h2>");

            // Äußere Grafiken-Schleife über die fixierte Bewegungsreihenfolge
            for (Movement mv : MOVEMENT_ORDER) {
                if (!openCapAggregates.containsKey(mv)) continue;
                Map<Joints, List<double[]>> jointMap = openCapAggregates.get(mv);

                // Innere Grafiken-Schleife über die Gelenkreihenfolge
                for (Joints joint : JOINT_ORDER) {
                    if (!jointMap.containsKey(joint)) continue;

                    // Dynamische Filterzuweisung für die Visualisierungsgraphen
                    Set<Joints> relevantJoints = switch (mv) {
                        case SQUAT, COUNTERMOVEMENT_JUMP,
                             LATERAL_LUNGE_LEFT, LATERAL_LUNGE_RIGHT -> Set.of(Joints.KNEE_ANGLE_R, Joints.KNEE_ANGLE_L);
                        case BECKEN_RUMPF_ROTATION                   -> Set.of(Joints.LUMBAR_ROTATION);
                    };

                    if (!relevantJoints.contains(joint)) continue;

                    List<double[]> ocTrials = jointMap.get(joint);
                    List<double[]> thTrials = theiaAggregates.containsKey(mv) ? theiaAggregates.get(mv).get(joint) : null;

                    if (ocTrials == null || ocTrials.isEmpty()) continue;

                    double[] meanOC  = calculateMeanVector(ocTrials);
                    double[] sdOC    = calculateSdVector(ocTrials, meanOC);
                    double[] upperOC = addVectors(meanOC, sdOC);
                    double[] lowerOC = subtractVectors(meanOC, sdOC);

                    double[] meanTH, sdTH, upperTH, lowerTH;
                    if (thTrials != null && !thTrials.isEmpty()) {
                        meanTH  = calculateMeanVector(thTrials);
                        sdTH    = calculateSdVector(thTrials, meanTH);
                        upperTH = addVectors(meanTH, sdTH);
                        lowerTH = subtractVectors(meanTH, sdTH);
                    } else {
                        meanTH = meanOC; sdTH = new double[101];
                        upperTH = meanOC; lowerTH = meanOC;
                    }

                    int nOC = ocTrials.size();
                    int nTH = (thTrials != null) ? thTrials.size() : 0;

                    String chartId = "chart_" + mv.name().toLowerCase() + "_" + joint.name().toLowerCase();

                    writer.write("<div class=\"chart-section\">");
                    writer.write("<h3 style='margin-top:0; border-bottom:1px solid #f1f2f6; padding-bottom:8px;'>"
                            + mv.name() + " &mdash; " + joint.getDisplayName() + "</h3>");
                    writer.write("<div class=\"chart-container\"><canvas id=\"" + chartId + "\"></canvas></div>");
                    writer.write("<div class=\"chart-meta\">OpenCap: n=" + nOC + " Trials &nbsp;|&nbsp; Theia: n=" + nTH
                            + " Trials &nbsp;|&nbsp; Schattierung: ±1 Standardabweichung</div>");
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

    private double[] calculateMeanVector(List<double[]> vectors) {
        double[] mean = new double[101];
        for (int i = 0; i < 101; i++) {
            double sum = 0; int count = 0;
            for (double[] vec : vectors) {
                if (i < vec.length && !Double.isNaN(vec[i])) { sum += vec[i]; count++; }
            }
            mean[i] = count > 0 ? sum / count : 0.0;
        }
        return mean;
    }

    private double[] calculateSdVector(List<double[]> vectors, double[] mean) {
        double[] sd = new double[101];
        for (int i = 0; i < 101; i++) {
            double sumSq = 0; int count = 0;
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