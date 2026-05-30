package edu.ankara.audiometer.serial;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import edu.ankara.audiometer.model.enums.ConnectionStatus;
import javafx.application.Platform;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Gerçek donanım UART implementasyonu.
 *
 * <p>Okuma hattı: HardwareBridge'den alınan kesme (interrupt) tabanlı
 * {@link SerialPortDataListener} + O(1) bellek ayak izli State Machine
 * ile "RESPONSE" bayt dizisini yakalar.</p>
 *
 * <p>Yazma hattı: HardwareBridge'den alınan {@code F:{frekans},I:{siddet}\n}
 * formatı kullanılır. YMH'nin {@link SerialCommand#playTone} metodundan
 * gelen PLAY;FREQ=…;DB=…;EAR=… dizgisi bu formata dönüştürülür.</p>
 *
 * <p>Mimari: YMH'nin {@link SerialService} arayüzü, {@link ConnectionStatus}
 * enum'ları ve listener tabanlı callback mimarisi tamamen korunmuştur.
 * Yalnızca UART okuma/yazma alt motoru değiştirilmiştir.</p>
 */
public class JSerialCommSerialService implements SerialService {

    private static final Logger LOG = Logger.getLogger(JSerialCommSerialService.class.getName());

    // ------------------------------------------------------------------ //
    //  State Machine sabitleri (HardwareBridge'den)                       //
    // ------------------------------------------------------------------ //
    /** Donanımın hasta butonu için gönderdiği beklenen cevap dizisi. */
    private static final byte[] TARGET_MESSAGE = "RESPONSE".getBytes();

    /**
     * O(1) State Machine göstergeci – kaç baytın eşleştiğini tutar.
     * volatile: listener thread ile diğer thread'ler arasında görünürlük.
     */
    private volatile int matchIndex = 0;

    // ------------------------------------------------------------------ //
    //  jSerialComm port referansı                                         //
    // ------------------------------------------------------------------ //
    /** Aktif seri port; bağlı değilken {@code null}. */
    private SerialPort activePort = null;

    // ------------------------------------------------------------------ //
    //  YMH Listener listeleri (thread-safe)                               //
    // ------------------------------------------------------------------ //
    private final List<Consumer<String>>           onResponseReceived      = new CopyOnWriteArrayList<>();
    private final List<Consumer<ConnectionStatus>> onConnectionStatusChanged = new CopyOnWriteArrayList<>();

    // ================================================================== //
    //  SerialService interface implementasyonu                            //
    // ================================================================== //

    /**
     * Sistemde mevcut seri portları listeler.
     *
     * @return Sistem port adlarının listesi (örneğin ["/dev/cu.usbserial-0001", "COM3"])
     */
    @Override
    public List<String> listAvailablePorts() {
        List<String> portNames = new ArrayList<>();
        for (SerialPort port : SerialPort.getCommPorts()) {
            portNames.add(port.getSystemPortName());
        }
        return portNames;
    }

    /**
     * Belirtilen porta bağlanır.
     *
     * <p>Bağlantı girişimleri sırasında {@link ConnectionStatus#CONNECTING},
     * başarıda {@link ConnectionStatus#CONNECTED},
     * hata durumunda {@link ConnectionStatus#ERROR} yayınlanır.</p>
     *
     * @param portName Bağlanılacak port adı (örneğin "COM3" ya da "/dev/ttyUSB0")
     * @param baudRate Baud hızı (örneğin 9600)
     * @throws Exception Port açılamazsa fırlatılır
     */
    @Override
    public void connect(String portName, int baudRate) throws Exception {
        if (isConnected()) {
            LOG.warning("Zaten bağlı bir port var: " + activePort.getSystemPortName());
            return;
        }

        fireConnectionStatus(ConnectionStatus.CONNECTING);

        // Sistem taramasını atla, verilen port adresine doğrudan (raw) bağlan
        SerialPort target = SerialPort.getCommPort(portName);

        target.setBaudRate(baudRate);
        target.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);

        if (!target.openPort()) {
            fireConnectionStatus(ConnectionStatus.ERROR);
            throw new Exception("Port açılamadı: " + portName
                    + " (Başka bir uygulama tarafından kullanılıyor olabilir)");
        }

        activePort = target;
        matchIndex  = 0; // State machine'i temizle

        // Kesme tabanlı dinlemeyi başlat (HardwareBridge mantığı)
        attachDataListener(activePort);

        LOG.info(portName + " başarıyla açıldı, dinleniyor...");
        fireConnectionStatus(ConnectionStatus.CONNECTED);
    }

    /**
     * Aktif port bağlantısını keser.
     *
     * <p>Listener kaldırılır, port kapatılır ve
     * {@link ConnectionStatus#DISCONNECTED} yayınlanır.</p>
     */
    @Override
    public void disconnect() {
        if (activePort == null) return;

        activePort.removeDataListener();
        activePort.closePort();
        activePort = null;
        matchIndex  = 0;

        LOG.info("Port bağlantısı kesildi.");
        fireConnectionStatus(ConnectionStatus.DISCONNECTED);
    }

    /**
     * Portun şu anda açık ve bağlı olup olmadığını döner.
     *
     * @return {@code true} ise bağlı
     */
    @Override
    public boolean isConnected() {
        return activePort != null && activePort.isOpen();
    }

    /**
     * YMH'nin {@code PLAY;FREQ=…;DB=…;EAR=…} komutunu alır ve
     * donanıma HardwareBridge formatında {@code F:{frekans},I:{siddet}\n} olarak gönderir.
     *
     * <p><b>Yazma Hattı (HardwareBridge mantığı):</b> Komut dizgisi ayrıştırılır,
     * EE uyumlu format oluşturulur ve {@code port.writeBytes()} ile
     * donanım seri hattına basılır.</p>
     *
     * @param command YMH'nin {@link SerialCommand#playTone} metodunun ürettiği komut dizgisi
     */
    @Override
    public void sendCommand(String command) {
        if (!isConnected()) {
            LOG.warning("Port kapalı, komut gönderilemedi: " + command);
            return;
        }

        // YMH formatı: PLAY;FREQ=1000;DB=40;EAR=RIGHT
        // HardwareBridge formatı: F:1000,I:40\n
        String hwCommand = convertToHardwareFormat(command);

        byte[] outgoingBuffer = hwCommand.getBytes();
        int bytesWritten = activePort.writeBytes(outgoingBuffer, outgoingBuffer.length);

        if (bytesWritten == -1) {
            LOG.severe("Donanıma yazma başarısız! Komut: " + hwCommand.trim());
        } else {
            LOG.fine("Donanıma gönderildi -> " + hwCommand.trim());
        }
    }

    /** YMH listener mimarisine callback ekler. */
    @Override
    public void addOnResponseReceived(Consumer<String> callback) {
        onResponseReceived.add(callback);
    }

    /** YMH listener mimarisine bağlantı durum callback'i ekler. */
    @Override
    public void addOnConnectionStatusChanged(Consumer<ConnectionStatus> callback) {
        onConnectionStatusChanged.add(callback);
    }

    // ================================================================== //
    //  Özel yardımcı metodlar                                            //
    // ================================================================== //

    /**
     * HardwareBridge'den alınan kesme (interrupt) tabanlı dinleyiciyi porta bağlar.
     *
     * <p><b>Okuma Hattı:</b> {@code LISTENING_EVENT_DATA_AVAILABLE} olayı
     * yalnızca yeni baytlar geldiğinde tetiklenir; polling yoktur.
     * Gelen baytlar O(1) bellek ayak izli State Machine ile işlenir.
     * "RESPONSE" dizisi tamamlandığında YMH'nin listener listesi
     * JavaFX thread'i üzerinden ({@link Platform#runLater}) tetiklenir.</p>
     *
     * @param port Dinlenecek açık seri port
     */
    private void attachDataListener(SerialPort port) {
        port.addDataListener(new SerialPortDataListener() {

            @Override
            public int getListeningEvents() {
                // Sadece yeni bayt geldiğinde uyan; CPU'yu polling ile meşgul etme
                return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
            }

            @Override
            public void serialEvent(SerialPortEvent event) {
                if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE) return;

                int available = port.bytesAvailable();
                if (available <= 0) return;

                // Ham baytları oku
                byte[] incomingData = new byte[available];
                port.readBytes(incomingData, incomingData.length);

                // O(1) State Machine: "RESPONSE" bayt dizisini yakala
                for (byte b : incomingData) {
                    if (b == TARGET_MESSAGE[matchIndex]) {
                        matchIndex++;

                        if (matchIndex == TARGET_MESSAGE.length) {
                            // "RESPONSE" dizisi tamamlandı → YMH listener mimarisini tetikle
                            LOG.info("[DONANIM] Hasta butona bastı – RESPONSE alındı.");
                            fireResponseReceived("RESPONSE");

                            matchIndex = 0; // Bir sonraki buton basışı için sıfırla
                        }

                    } else {
                        // Bayt dizisi bozuldu; bellek sızıntısını önlemek için sıfırla
                        matchIndex = (b == TARGET_MESSAGE[0]) ? 1 : 0;
                    }
                }
            }
        });
    }

    /**
     * YMH komut formatını EE firmware'inin beklediği HardwareBridge formatına çevirir.
     *
     * <pre>
     * Girdi  : PLAY;FREQ=1000;DB=40;EAR=RIGHT
     * Çıktı  : F:1000,I:40\n
     * </pre>
     *
     * <p>Frekans veya yoğunluk ayrıştırılamazsa ham komut doğrudan gönderilir
     * ve hata loglanır.</p>
     *
     * @param command YMH format komutu
     * @return EE firmware uyumlu HardwareBridge format komutu
     */
    private String convertToHardwareFormat(String command) {
        if (command == null || !command.startsWith(SerialCommand.PLAY_PREFIX)) {
            // Tanınmayan komut – olduğu gibi gönder
            LOG.warning("Tanınmayan komut formatı, ham gönderiliyor: " + command);
            return (command == null ? "" : command) + "\n";
        }

        try {
            // PLAY;FREQ=1000;DB=40;EAR=RIGHT
            String[] parts = command.split(";");
            int frequency = -1;
            int intensity = -1;

            for (String part : parts) {
                if (part.startsWith("FREQ=")) {
                    frequency = Integer.parseInt(part.substring(5).trim());
                } else if (part.startsWith("DB=")) {
                    intensity = Integer.parseInt(part.substring(3).trim());
                }
                // EAR bilgisi şu an EE firmware'inde desteklenmemekte; logla
            }

            if (frequency < 0 || intensity < 0) {
                throw new IllegalArgumentException("FREQ veya DB ayrıştırılamadı: " + command);
            }

            // HardwareBridge formatı: F:{frekans},I:{siddet}\n
            return "F:" + frequency + ",I:" + intensity + "\n";

        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Komut dönüştürme hatası, ham gönderiliyor: " + command, e);
            return command + "\n";
        }
    }

    /**
     * Bağlantı durumu değişikliğini tüm kayıtlı listener'lara JavaFX thread'i
     * üzerinden ({@link Platform#runLater}) iletir.
     */
    private void fireConnectionStatus(ConnectionStatus status) {
        runOnFxThread(() -> onConnectionStatusChanged.forEach(c -> c.accept(status)));
    }

    /**
     * "RESPONSE" alındığında tüm kayıtlı listener'ları JavaFX thread'i
     * üzerinden ({@link Platform#runLater}) tetikler.
     *
     * @param response Donanımdan alınan ham cevap dizgisi
     */
    private void fireResponseReceived(String response) {
        runOnFxThread(() -> onResponseReceived.forEach(c -> c.accept(response)));
    }

    /**
     * Verilen işlemi JavaFX Application Thread'i üzerinde çalıştırır.
     * Toolkit henüz başlatılmamışsa (birim testlerde) doğrudan çalıştırır.
     *
     * @param action Çalıştırılacak işlem
     */
    private void runOnFxThread(Runnable action) {
        try {
            Platform.runLater(action);
        } catch (IllegalStateException e) {
            // Toolkit başlatılmamış (örneğin birim testleri) – doğrudan çalıştır
            action.run();
        }
    }
}
