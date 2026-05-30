package edu.ankara.audiometer.gui;

import edu.ankara.audiometer.i18n.I18nManager;
import edu.ankara.audiometer.model.enums.ConnectionStatus;
import edu.ankara.audiometer.serial.SerialService;
import edu.ankara.audiometer.app.ServiceRegistry;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

public class ConnectionPanelController {

    @FXML private Label titleLabel;
    @FXML private Label portLabel;
    @FXML private Label baudLabel;
    @FXML private ComboBox<String> portCombo;
    @FXML private ComboBox<Integer> baudCombo;
    @FXML private Button connectBtn;
    @FXML private HBox statusContainer;
    @FXML private Label connectedTextLabel;

    private final SerialService serialService = ServiceRegistry.serialService;

    @FXML
    public void initialize() {
        I18nManager i18n = I18nManager.getInstance();

        // Bind Labels to i18n
        i18n.bind(titleLabel, "nav.connection");
        i18n.bind(portLabel, "field.port");
        i18n.bind(baudLabel, "field.baudRate");
        i18n.bind(connectedTextLabel, "status.connected");

        // Set combo data
        portCombo.setItems(FXCollections.observableArrayList(serialService.listAvailablePorts()));
        portCombo.setEditable(true); // Sanal/özel port adları elle yazılabilsin (ör. /dev/ttys004)

        baudCombo.setItems(FXCollections.observableArrayList(9600, 19200, 38400, 115200));
        baudCombo.setEditable(true); // Seçilen değeri görmek için editable + converter gerekli
        baudCombo.setConverter(new javafx.util.StringConverter<Integer>() {
            @Override public String toString(Integer v)   { return v == null ? "" : String.valueOf(v); }
            @Override public Integer fromString(String s) {
                try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 9600; }
            }
        });

        if (!portCombo.getItems().isEmpty()) {
            portCombo.getSelectionModel().selectFirst();
        }
        baudCombo.getSelectionModel().select(0);

        // Register status listener
        serialService.addOnConnectionStatusChanged(status -> {
            Platform.runLater(() -> updateUiForStatus(status));
        });
        
        connectBtn.setOnAction(e -> {
            if (serialService.isConnected()) {
                serialService.disconnect();
            } else {
                try {
                    serialService.connect(portCombo.getValue(), baudCombo.getValue());
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });
        
        updateUiForStatus(ConnectionStatus.DISCONNECTED);
    }
    
    private void updateUiForStatus(ConnectionStatus status) {
        I18nManager i18n = I18nManager.getInstance();
        boolean connected = (status == ConnectionStatus.CONNECTED);
        boolean connecting = (status == ConnectionStatus.CONNECTING);
        
        portCombo.setDisable(connected || connecting);
        baudCombo.setDisable(connected || connecting);
        connectBtn.setDisable(connecting);
        
        if (connecting) {
            connectBtn.textProperty().bind(I18nManager.getInstance().createStringBinding("btn.connecting"));
            connectBtn.getStyleClass().remove("btn-primary");
            connectBtn.getStyleClass().remove("btn-danger");
            if (!connectBtn.getStyleClass().contains("btn-secondary")) {
                connectBtn.getStyleClass().add("btn-secondary");
            }
        } else if (connected) {
            connectBtn.textProperty().bind(i18n.createStringBinding("btn.disconnect"));
            connectBtn.getStyleClass().remove("btn-primary");
            connectBtn.getStyleClass().remove("btn-secondary");
            if (!connectBtn.getStyleClass().contains("btn-danger")) {
                connectBtn.getStyleClass().add("btn-danger");
            }
            statusContainer.setVisible(true);
            statusContainer.setManaged(true);
        } else {
            connectBtn.textProperty().bind(i18n.createStringBinding("btn.connect"));
            connectBtn.getStyleClass().remove("btn-danger");
            connectBtn.getStyleClass().remove("btn-secondary");
            if (!connectBtn.getStyleClass().contains("btn-primary")) {
                connectBtn.getStyleClass().add("btn-primary");
            }
            statusContainer.setVisible(false);
            statusContainer.setManaged(false);
        }
    }
    
    // Simple helper to force string binding update, since service connected is not a JavaFX Property
    // We override the binding logic inside updateUiForStatus instead.
}
