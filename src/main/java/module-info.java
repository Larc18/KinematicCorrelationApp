module de.cwelsch.kinematiccorrelationapp {
    requires javafx.controls;
    requires javafx.fxml;
    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires org.kordamp.bootstrapfx.core;
    requires java.desktop;
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.dataformat.yaml;
    requires commons.math3;

    opens de.cwelsch.kinematiccorrelationapp to javafx.fxml;
    exports de.cwelsch.kinematiccorrelationapp;
    exports de.cwelsch.kinematiccorrelationapp.ui;
    opens de.cwelsch.kinematiccorrelationapp.ui to javafx.fxml;
    exports de.cwelsch.kinematiccorrelationapp.services.statisticalanalysisservice;
    opens de.cwelsch.kinematiccorrelationapp.services.statisticalanalysisservice to javafx.fxml;
    exports de.cwelsch.kinematiccorrelationapp.services.validationservice;
    opens de.cwelsch.kinematiccorrelationapp.services.validationservice to javafx.fxml;
    exports de.cwelsch.kinematiccorrelationapp.services.importservice;
    opens de.cwelsch.kinematiccorrelationapp.services.importservice to javafx.fxml;
    opens de.cwelsch.kinematiccorrelationapp.model.participant to com.fasterxml.jackson.databind;
    opens de.cwelsch.kinematiccorrelationapp.model to com.fasterxml.jackson.databind;
}