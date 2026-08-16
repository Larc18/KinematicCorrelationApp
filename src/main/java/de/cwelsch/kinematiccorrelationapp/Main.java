package de.cwelsch.kinematiccorrelationapp;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;

/**
 * Entry point of the JavaFX application. Loads the main view and shows the primary stage.
 */
public class Main extends Application {

    // dimensions of the standard window
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;

    /**
     * Loads {@code main-view.fxml}, builds the scene and shows the primary application window.
     *
     * @param stage the primary stage provided by the JavaFX runtime
     * @throws IOException if the FXML file cannot be found or fails to load
     */
    @Override
    public void start(Stage stage) throws IOException {
        URL fxmlLocation = Main.class.getResource("main-view.fxml");
        if (fxmlLocation == null) {
            throw new IOException("FXML file 'main-view.fxml' could not be found within the classpath.");
        }

        FXMLLoader fxmlLoader = new FXMLLoader(fxmlLocation);

        Scene scene = new Scene(fxmlLoader.load(), WINDOW_WIDTH, WINDOW_HEIGHT);

        // configuration of window
        stage.setTitle("Kinematic Correlation Application");
        stage.setScene(scene);

        stage.setMinWidth(800);
        stage.setMinHeight(600);

        stage.show();
    }

    /**
     * Launches the JavaFX application.
     *
     * @param args command line arguments, forwarded to the JavaFX launcher
     */
    public static void main(String[] args) {
        launch(args);
    }
}