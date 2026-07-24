package de.cwelsch.kinematiccorrelationapp;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;

public class Main extends Application {

    // dimensions of the standard window
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;

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

    public static void main(String[] args) {
        launch(args);
    }
}