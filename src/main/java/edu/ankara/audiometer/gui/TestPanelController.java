package edu.ankara.audiometer.gui;

import edu.ankara.audiometer.app.ServiceRegistry;
import edu.ankara.audiometer.audiometry.ResponseProcessor;
import edu.ankara.audiometer.i18n.I18nManager;
import edu.ankara.audiometer.model.PatientResponse;
import edu.ankara.audiometer.model.enums.ConnectionStatus;
import edu.ankara.audiometer.model.enums.Ear;
import edu.ankara.audiometer.model.enums.Frequency;
import edu.ankara.audiometer.serial.SerialCommand;
import edu.ankara.audiometer.serial.SerialResponseParser;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.Map;

public class TestPanelController {
    
    @FXML private Label titleLabel;
    @FXML private Button btnLeftEar;
    @FXML private Button btnRightEar;
    @FXML private Label freqTitleLabel;
    @FXML private GridPane freqGrid;
    @FXML private Label intensityValueLabel;
    @FXML private Label hearingLevelLabel;
    @FXML private Slider intensitySlider;
    @FXML private Button playBtn;
    @FXML private HBox responseOverlay;
    @FXML private Label responseLabel;
    @FXML private Button nextBtn;
    @FXML private Button autoBtn;

    private Ear currentEar = Ear.RIGHT;
    private Frequency currentFreq = Frequency.HZ_1000;
    private final Map<Frequency, Button> freqButtons = new HashMap<>();
    private final SerialResponseParser responseParser = new SerialResponseParser();
    private final ResponseProcessor responseProcessor = new ResponseProcessor();

    @FXML
    public void initialize() {
        I18nManager i18n = I18nManager.getInstance();

        i18n.bind(titleLabel, "nav.testControl");
        i18n.bind(btnLeftEar, "ear.left");
        i18n.bind(btnRightEar, "ear.right");
        i18n.bind(freqTitleLabel, "audiogram.frequency");
        i18n.bind(hearingLevelLabel, "msg.hearingLevel");
        
        // Note: For button with graphic + text we don't bind directly to textProperty if we want to keep graphic, 
        // but textProperty().bind() will keep the graphic if it's set in FXML. Let's see if it drops the icon. 
        // We'll bind anyway because we need translation.
        playBtn.textProperty().bind(i18n.createStringBinding("btn.playTone"));
        
        i18n.bind(responseLabel, "msg.responseReceived");
        i18n.bind(nextBtn, "btn.next");
        i18n.bind(autoBtn, "btn.auto");

        setupEarButtons();
        setupFreqGrid();
        setupSlider();

        // Listen to connection
        playBtn.setDisable(true);
        ServiceRegistry.serialService.addOnConnectionStatusChanged(status -> {
            Platform.runLater(() -> {
                playBtn.setDisable(status != ConnectionStatus.CONNECTED);
            });
        });

        playBtn.setOnAction(e -> handlePlay());
        
        ServiceRegistry.serialService.addOnResponseReceived(rawResponse -> {
            Platform.runLater(() -> {
                responseParser.parse(rawResponse)
                        .toOptional()
                        .flatMap(message -> responseProcessor.toPatientResponse(
                                message,
                                currentEar,
                                currentFreq,
                                Integer.parseInt(intensityValueLabel.getText())
                        ))
                        .filter(PatientResponse::heard)
                        .ifPresent(response -> {
                            // Odyograma eşik noktasını kaydet
                            int db = Integer.parseInt(intensityValueLabel.getText());
                            ServiceRegistry.thresholds.add(
                                    new edu.ankara.audiometer.model.Threshold(currentFreq, db, currentEar));
                            showResponseOverlay();
                        });
            });
        });
        
        nextBtn.setOnAction(e -> {
            Frequency[] freqs = Frequency.values();
            for (int i = 0; i < freqs.length - 1; i++) {
                if (freqs[i] == currentFreq) {
                    setFreq(freqs[i+1]);
                    break;
                }
            }
        });

        autoBtn.setOnAction(e -> {
            System.out.println("Auto test placeholder");
            // TODO: Integrate HughsonWestlakeService here
        });
    }

    private void setupEarButtons() {
        btnLeftEar.setOnAction(e -> setEar(Ear.LEFT));
        btnRightEar.setOnAction(e -> setEar(Ear.RIGHT));
    }

    private void setEar(Ear ear) {
        this.currentEar = ear;
        if (ear == Ear.LEFT) {
            btnLeftEar.getStyleClass().add("ear-btn-left-selected");
            btnLeftEar.getStyleClass().remove("toggle-btn");
            btnRightEar.getStyleClass().remove("ear-btn-right-selected");
            if (!btnRightEar.getStyleClass().contains("toggle-btn")) btnRightEar.getStyleClass().add("toggle-btn");
        } else {
            btnRightEar.getStyleClass().add("ear-btn-right-selected");
            btnRightEar.getStyleClass().remove("toggle-btn");
            btnLeftEar.getStyleClass().remove("ear-btn-left-selected");
            if (!btnLeftEar.getStyleClass().contains("toggle-btn")) btnLeftEar.getStyleClass().add("toggle-btn");
        }
    }

    private void setupFreqGrid() {
        Frequency[] freqs = {
            Frequency.HZ_250, Frequency.HZ_500, Frequency.HZ_1000, 
            Frequency.HZ_2000, Frequency.HZ_4000, Frequency.HZ_8000
        };
        int col = 0, row = 0;
        for (Frequency f : freqs) {
            Button b = new Button();
            b.getStyleClass().add("freq-btn");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setText(f.getValue() >= 1000 ? (f.getValue()/1000) + "k" : String.valueOf(f.getValue()));
            b.setOnAction(e -> setFreq(f));
            freqButtons.put(f, b);
            freqGrid.add(b, col, row);
            
            col++;
            if (col > 2) {
                col = 0;
                row++;
            }
        }
        setFreq(Frequency.HZ_1000);
    }

    private void setFreq(Frequency f) {
        if (!freqButtons.containsKey(f)) return;
        this.currentFreq = f;
        for (Button b : freqButtons.values()) {
            b.getStyleClass().remove("freq-btn-selected");
        }
        freqButtons.get(f).getStyleClass().add("freq-btn-selected");
    }

    private void setupSlider() {
        intensitySlider.valueProperty().addListener((obs, oldV, newV) -> {
            int val = (int) Math.round(newV.doubleValue());
            int remainder = val % 5;
            int snapped = val;
            if (remainder >= 3) {
                snapped += (5 - remainder);
            } else {
                snapped -= remainder;
            }
            intensityValueLabel.setText(String.valueOf(snapped));
        });

        // Başlangıçta slider değerini zorla eşitle — listener sadece değişimde tetiklenir,
        // kullanıcı hiç dokunmasa handlePlay() içindeki parseInt("") patlar.
        int initVal = (int) Math.round(intensitySlider.getValue());
        intensityValueLabel.setText(String.valueOf(initVal));
    }

    private void handlePlay() {
        int db = Integer.parseInt(intensityValueLabel.getText());
        String cmd = SerialCommand.playTone(currentFreq.getValue(), db, currentEar.name());
        ServiceRegistry.serialService.sendCommand(cmd);
        
        playBtn.setDisable(true);
        
        PauseTransition pause = new PauseTransition(Duration.seconds(6));
        pause.setOnFinished(ev -> {
            if (ServiceRegistry.serialService.isConnected()) {
                playBtn.setDisable(false);
            }
        });
        pause.play();
    }
    
    private void showResponseOverlay() {
        responseOverlay.setVisible(true);
        playBtn.setDisable(true);
        
        PauseTransition pause = new PauseTransition(Duration.seconds(2));
        pause.setOnFinished(ev -> {
            responseOverlay.setVisible(false);
            if (ServiceRegistry.serialService.isConnected()) {
                playBtn.setDisable(false);
            }
        });
        pause.play();
    }
}
