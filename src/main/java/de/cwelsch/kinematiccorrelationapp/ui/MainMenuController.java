package de.cwelsch.kinematiccorrelationapp.ui;

import de.cwelsch.kinematiccorrelationapp.Main;
import de.cwelsch.kinematiccorrelationapp.ProcessOrchestrator;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Background;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

/**
 * Presenter controller driving the primary MainMenu FXML view layer dashboard.
 * Manages native filesystem drag-and-drop ingestion events, dynamic preview tab allocations,
 * and handles worker thread offloading for pipeline orchestration processes.
 */
public class MainMenuController {

    @FXML public VBox root;
    @FXML public TabPane tabPane;
    @FXML public VBox folderDropOff;
    @FXML public Button beginAnalysisButton;

    private String folderURL;

    /**
     * Determines dragboard contents suitability and establishes standard file accept hooks.
     */
    @FXML
    public void handleDragOver(DragEvent event) {
        if (event.getDragboard().hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
        }
        event.consume();
    }

    /**
     * Captures dropped operating system directory targets and maps paths to local configurations.
     */
    @FXML
    public void handleDragDropped(DragEvent event) {
        var db = event.getDragboard();
        boolean success = false;

        if (db.hasFiles()) {
            File droppedFile = db.getFiles().get(0);

            if (event.getSource() == folderDropOff && droppedFile.isDirectory()) {
                // getAbsolutePath() unifies native formatting targets without 'file:' URI prefixes
                this.folderURL = droppedFile.getAbsolutePath();
                success = true;

                // Visual drop feedback coloring state change
                this.folderDropOff.setBackground(Background.fill(Color.web("#adadad")));

                // Safely update file label description text elements directly inside the target layout node
                if (!this.folderDropOff.getChildren().isEmpty() && this.folderDropOff.getChildren().get(0) instanceof Label label) {
                    String folderName = Paths.get(this.folderURL).getFileName().toString();
                    label.setText(folderName);
                }

                System.out.println("Ingested target workspace directory: " + this.folderURL);
            }
        }

        event.setDropCompleted(success);
        event.consume();
    }

    /**
     * Loads a view layout instance dynamically inside a new dashboard tab wrapper for Theia data tracking.
     */
    @FXML
    public void theiaMarkerlessPreview(ActionEvent actionEvent) throws IOException {
        Tab newTab = new Tab();
        newTab.setText("TheiaMarkerless Preview");

        FXMLLoader loader = new FXMLLoader(Main.class.getResource("preview.fxml"));
        Parent previewNode = loader.load();
        newTab.setContent(previewNode);

        this.tabPane.getTabs().add(newTab);
    }

    /**
     * Loads a view layout instance dynamically inside a new dashboard tab wrapper for OpenCap data tracking.
     */
    @FXML
    public void openCapPreview(ActionEvent actionEvent) throws IOException {
        Tab newTab = new Tab();
        newTab.setText("OpenCap Preview");

        FXMLLoader loader = new FXMLLoader(Main.class.getResource("preview.fxml"));
        Parent previewNode = loader.load();
        newTab.setContent(previewNode);

        this.tabPane.getTabs().add(newTab);
    }

    /**
     * Boots up execution processes. Offloads expensive statistical and disk IO calculations onto a background
     * worker thread loop to keep the primary FX Application UI rendering cycle fully active and fluid.
     */
    @FXML
    public void beginAnalysis(ActionEvent actionEvent) {
        if (this.folderURL == null || this.folderURL.trim().isEmpty()) {
            System.err.println("[Guard] Aborting calculation trigger: Target data path workspace URL configuration is unset.");
            return;
        }

        // Offloading pipeline tracking computation onto a separate background worker thread
        Thread backgroundWorker = new Thread(() -> {
            try {
                ProcessOrchestrator orchestrator = new ProcessOrchestrator(this.folderURL);
                orchestrator.start();

                // Safely delegate interface state resets back onto the main platform UI execution loop thread
                Platform.runLater(() -> System.out.println("Processing task run finished. UI synchronization successfully locked."));

            } catch (IOException e) {
                Platform.runLater(() -> System.err.println("[Exception] Pipeline background operation failure: " + e.getMessage()));
                e.printStackTrace();
            }
        });

        backgroundWorker.setDaemon(true); // Daemon threads close instantly if the window application terminates
        backgroundWorker.start();
    }
}