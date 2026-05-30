package edu.ankara.audiometer.app;

import edu.ankara.audiometer.audiometry.HughsonWestlakeService;
import edu.ankara.audiometer.audiometry.stub.StubHughsonWestlakeService;
import edu.ankara.audiometer.model.Threshold;
import edu.ankara.audiometer.serial.JSerialCommSerialService;
import edu.ankara.audiometer.serial.SerialService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class ServiceRegistry {
    private ServiceRegistry() {
    }

    /** Gerçek jSerialComm donanım katmanı (HardwareBridge motoru). */
    public static final SerialService serialService = new JSerialCommSerialService();
    public static final HughsonWestlakeService hwService = new StubHughsonWestlakeService();

    /**
     * Paylaşılan eşik veri yolu.
     *
     * <p>TestPanelController her RESPONSE sonrası buraya yazar;
     * AudiogramPanelController bu listeyi dinleyerek grafiği günceller.
     * Doğrudan controller referansı olmadan MVC mimarisi korunur.</p>
     */
    public static final ObservableList<Threshold> thresholds =
            FXCollections.observableArrayList();
}
