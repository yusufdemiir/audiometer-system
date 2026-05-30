package edu.ankara.audiometer.gui;

import edu.ankara.audiometer.i18n.I18nManager;
import edu.ankara.audiometer.app.ServiceRegistry;
import edu.ankara.audiometer.model.Threshold;
import edu.ankara.audiometer.model.enums.Ear;
import edu.ankara.audiometer.model.enums.Frequency;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.embed.swing.SwingFXUtils;
import javafx.stage.FileChooser;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Standart klinik odyogram paneli.
 *
 * <p>JavaFX {@link Canvas} üzerine ISO 8253-1 standardına uygun odyogram grafiği çizer.
 * Sağ kulak: kırmızı "O" sembolü, Sol kulak: mavi "X" sembolü.
 * Eşik verileri dışarıdan {@link #addThreshold(Threshold)} çağrısıyla eklenir
 * ve grafik otomatik güncellenir.</p>
 */
public class AudiogramPanelController {

    // ------------------------------------------------------------------ //
    //  FXML bileşenleri                                                   //
    // ------------------------------------------------------------------ //
    @FXML private Label  titleLabel;
    @FXML private Button exportPngBtn;
    @FXML private Button exportPdfBtn;
    @FXML private Canvas audiogramCanvas;
    @FXML private Pane   canvasContainer;

    // ------------------------------------------------------------------ //
    //  Odyogram sabitleri                                                  //
    // ------------------------------------------------------------------ //
    /** Klinik odyogramda gösterilen standart frekanslar (Hz). */
    private static final int[] FREQUENCIES = {125, 250, 500, 1000, 2000, 4000, 8000};

    /** Y ekseninin başlangıcı (dB HL). */
    private static final int DB_MIN  = -10;
    /** Y ekseninin sonu (dB HL). */
    private static final int DB_MAX  = 120;
    /** Y eksenindeki adım aralığı (dB). */
    private static final int DB_STEP = 10;

    /** Sağ kulak rengi: kırmızı (ISO standardı). */
    private static final Color RIGHT_EAR_COLOR = Color.rgb(220, 38, 38);   // #DC2626
    /** Sol kulak rengi: mavi (ISO standardı). */
    private static final Color LEFT_EAR_COLOR  = Color.rgb(37, 99, 235);   // #2563EB

    // ------------------------------------------------------------------ //
    //  Grafik kenar boşlukları                                            //
    // ------------------------------------------------------------------ //
    private static final double MARGIN_LEFT   = 60;
    private static final double MARGIN_RIGHT  = 30;
    private static final double MARGIN_TOP    = 40;
    private static final double MARGIN_BOTTOM = 50;

    // ------------------------------------------------------------------ //
    //  Veri – ServiceRegistry.thresholds'dan okunur                      //
    // ------------------------------------------------------------------ //
    // Yerel liste yok: tüm eşikler ServiceRegistry.thresholds'da tutuluyor.

    // ================================================================== //
    //  FXML initialize                                                    //
    // ================================================================== //

    @FXML
    public void initialize() {
        I18nManager i18n = I18nManager.getInstance();

        i18n.bind(titleLabel, "audiogram.title");
        exportPngBtn.textProperty().bind(i18n.createStringBinding("btn.exportPNG"));
        exportPdfBtn.textProperty().bind(i18n.createStringBinding("btn.exportPDF"));

        // Canvas boyutu container'a bağlanır (responsive)
        canvasContainer.widthProperty().addListener((obs, o, w) -> {
            audiogramCanvas.setWidth(w.doubleValue());
            redraw();
        });
        canvasContainer.heightProperty().addListener((obs, o, h) -> {
            audiogramCanvas.setHeight(h.doubleValue());
            redraw();
        });

        exportPngBtn.setOnAction(e -> exportAsPng());
        exportPdfBtn.setOnAction(e -> exportAsPdf());

        // ServiceRegistry.thresholds listesi değiştiğinde grafiği güncelle
        ServiceRegistry.thresholds.addListener(
                (javafx.collections.ListChangeListener<Threshold>) change -> redraw());

        // İlk çizim
        redraw();
    }

    // ================================================================== //
    //  Dışarıya açık API                                                  //
    // ================================================================== //

    /**
     * Yeni bir eşik ölçümü ServiceRegistry.thresholds'a ekler.
     * ListChangeListener otomatik olarak grafiği günceller.
     * TestPanelController yerine doğrudan ServiceRegistry.thresholds.add() da kullanılabilir.
     *
     * @param threshold Eklenmesi gereken eşik noktası
     */
    public void addThreshold(Threshold threshold) {
        // Aynı frekans + kulak kombinasyonu varsa güncelle
        ServiceRegistry.thresholds.removeIf(t -> t.frequency() == threshold.frequency()
                                                  && t.ear()    == threshold.ear());
        ServiceRegistry.thresholds.add(threshold);
        // Not: redraw() çağrısına gerek yok — ListChangeListener tetikler.
    }

    /**
     * Tüm eşik verilerini temizler; ListChangeListener grafiği sıfırlar.
     */
    public void clearThresholds() {
        ServiceRegistry.thresholds.clear();
    }

    // ================================================================== //
    //  Çizim motoru                                                       //
    // ================================================================== //

    /** Canvas'ı tamamen yeniden çizer. */
    private void redraw() {
        double w = audiogramCanvas.getWidth();
        double h = audiogramCanvas.getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = audiogramCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        drawBackground(gc, w, h);
        drawGrid(gc, w, h);
        drawAxesLabels(gc, w, h);
        drawThresholds(gc, w, h);
    }

    // ------------------------------------------------------------------ //
    //  1. Arka plan                                                       //
    // ------------------------------------------------------------------ //

    private void drawBackground(GraphicsContext gc, double w, double h) {
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, w, h);
    }

    // ------------------------------------------------------------------ //
    //  2. Izgara (grid)                                                   //
    // ------------------------------------------------------------------ //

    private void drawGrid(GraphicsContext gc, double w, double h) {
        double plotW = w - MARGIN_LEFT - MARGIN_RIGHT;
        double plotH = h - MARGIN_TOP  - MARGIN_BOTTOM;

        int dbRange   = DB_MAX - DB_MIN;                     // 130 dB
        int dbLines   = dbRange / DB_STEP;                   // 13 çizgi
        int freqCount = FREQUENCIES.length;                  // 7 sütun

        // --- Yatay çizgiler (dB seviyeleri) ---
        for (int i = 0; i <= dbLines; i++) {
            int db = DB_MIN + i * DB_STEP;
            double y = MARGIN_TOP + (double) i / dbLines * plotH;

            if (db == 0) {
                // 0 dB HL çizgisi kalın ve koyu
                gc.setStroke(Color.rgb(55, 65, 81, 0.7));   // gray-700
                gc.setLineWidth(1.5);
            } else {
                gc.setStroke(Color.rgb(209, 213, 219, 0.8)); // gray-300
                gc.setLineWidth(0.8);
            }
            gc.strokeLine(MARGIN_LEFT, y, w - MARGIN_RIGHT, y);
        }

        // --- Dikey çizgiler (frekanslar) ---
        gc.setStroke(Color.rgb(209, 213, 219, 0.8));
        gc.setLineWidth(0.8);
        for (int i = 0; i < freqCount; i++) {
            double x = MARGIN_LEFT + (double) i / (freqCount - 1) * plotW;
            gc.strokeLine(x, MARGIN_TOP, x, h - MARGIN_BOTTOM);
        }

        // --- Dış çerçeve ---
        gc.setStroke(Color.rgb(107, 114, 128));  // gray-500
        gc.setLineWidth(1.5);
        gc.strokeRect(MARGIN_LEFT, MARGIN_TOP, plotW, plotH);
    }

    // ------------------------------------------------------------------ //
    //  3. Eksen etiketleri                                                //
    // ------------------------------------------------------------------ //

    private void drawAxesLabels(GraphicsContext gc, double w, double h) {
        double plotW = w - MARGIN_LEFT - MARGIN_RIGHT;
        double plotH = h - MARGIN_TOP  - MARGIN_BOTTOM;

        int dbRange   = DB_MAX - DB_MIN;
        int dbLines   = dbRange / DB_STEP;
        int freqCount = FREQUENCIES.length;

        gc.setFill(Color.rgb(55, 65, 81));       // gray-700
        gc.setFont(Font.font("Inter", FontWeight.NORMAL, 11));

        // --- dB etiketleri (sol) ---
        gc.setTextAlign(TextAlignment.RIGHT);
        for (int i = 0; i <= dbLines; i++) {
            int db = DB_MIN + i * DB_STEP;
            double y = MARGIN_TOP + (double) i / dbLines * plotH;
            gc.fillText(String.valueOf(db), MARGIN_LEFT - 6, y + 4);
        }

        // --- Frekans etiketleri (üst) ---
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFont(Font.font("Inter", FontWeight.NORMAL, 11));
        for (int i = 0; i < freqCount; i++) {
            double x = MARGIN_LEFT + (double) i / (freqCount - 1) * plotW;
            int freq = FREQUENCIES[i];
            String label = freq >= 1000 ? (freq / 1000) + "k" : String.valueOf(freq);
            gc.fillText(label, x, MARGIN_TOP - 8);
        }

        // --- Y eksen başlığı ---
        gc.save();
        gc.setFont(Font.font("Inter", FontWeight.BOLD, 11));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.translate(14, MARGIN_TOP + plotH / 2);
        gc.rotate(-90);
        gc.fillText("dB HL", 0, 0);
        gc.restore();

        // --- X eksen başlığı ---
        gc.setFont(Font.font("Inter", FontWeight.BOLD, 11));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("Hz", MARGIN_LEFT + plotW / 2, h - 8);
    }

    // ------------------------------------------------------------------ //
    //  4. Eşik noktaları                                                  //
    // ------------------------------------------------------------------ //

    private void drawThresholds(GraphicsContext gc, double w, double h) {
        if (ServiceRegistry.thresholds.isEmpty()) return;

        double plotW = w - MARGIN_LEFT - MARGIN_RIGHT;
        double plotH = h - MARGIN_TOP  - MARGIN_BOTTOM;
        int dbRange   = DB_MAX - DB_MIN;
        int freqCount = FREQUENCIES.length;

        // Sağ kulakta çizgiler, sol kulakta kesikli çizgiler
        List<double[]> rightPoints = new ArrayList<>();
        List<double[]> leftPoints  = new ArrayList<>();

        for (Threshold t : ServiceRegistry.thresholds) {
            double x = freqX(t.frequency(), plotW, freqCount);
            double y = dbY(t.dbHL(), plotH, dbRange);

            if (x < 0) continue; // Bilinmeyen frekans

            if (t.ear() == Ear.RIGHT) {
                rightPoints.add(new double[]{x, y});
                drawSymbolO(gc, x, y, RIGHT_EAR_COLOR);
            } else {
                leftPoints.add(new double[]{x, y});
                drawSymbolX(gc, x, y, LEFT_EAR_COLOR);
            }
        }

        // Sağ kulak bağlantı çizgisi (düz, kırmızı)
        drawConnectionLine(gc, rightPoints, RIGHT_EAR_COLOR, false);
        // Sol kulak bağlantı çizgisi (kesikli, mavi)
        drawConnectionLine(gc, leftPoints, LEFT_EAR_COLOR, true);
    }

    /**
     * ISO standardı sağ kulak sembolü: dolu daire (O).
     */
    private void drawSymbolO(GraphicsContext gc, double cx, double cy, Color color) {
        double r = 7;
        gc.setStroke(color);
        gc.setLineWidth(2);
        gc.setFill(Color.TRANSPARENT);
        gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
    }

    /**
     * ISO standardı sol kulak sembolü: çarpı işareti (X).
     */
    private void drawSymbolX(GraphicsContext gc, double cx, double cy, Color color) {
        double r = 7;
        gc.setStroke(color);
        gc.setLineWidth(2);
        gc.strokeLine(cx - r, cy - r, cx + r, cy + r);
        gc.strokeLine(cx + r, cy - r, cx - r, cy + r);
    }

    /**
     * Ardışık eşik noktalarını birleştiren çizgiyi çizer.
     *
     * @param dashed {@code true} ise kesikli çizgi (sol kulak)
     */
    private void drawConnectionLine(GraphicsContext gc, List<double[]> points,
                                    Color color, boolean dashed) {
        if (points.size() < 2) return;

        // Frekansa göre sırala (x koordinatına göre)
        points.sort((a, b) -> Double.compare(a[0], b[0]));

        gc.setStroke(color);
        gc.setLineWidth(1.5);
        if (dashed) {
            gc.setLineDashes(6, 4);
        } else {
            gc.setLineDashes(0);
        }

        gc.beginPath();
        gc.moveTo(points.get(0)[0], points.get(0)[1]);
        for (int i = 1; i < points.size(); i++) {
            gc.lineTo(points.get(i)[0], points.get(i)[1]);
        }
        gc.stroke();
        gc.setLineDashes(0); // sıfırla
    }

    // ================================================================== //
    //  Koordinat dönüşüm yardımcıları                                    //
    // ================================================================== //

    /**
     * Frekans değerinden canvas X koordinatı hesaplar.
     *
     * @return Canvas X koordinatı; frekans tanımlı değilse -1
     */
    private double freqX(Frequency frequency, double plotW, int freqCount) {
        int freqHz = frequency.getValue();
        for (int i = 0; i < FREQUENCIES.length; i++) {
            if (FREQUENCIES[i] == freqHz) {
                return MARGIN_LEFT + (double) i / (freqCount - 1) * plotW;
            }
        }
        return -1;
    }

    /**
     * dB HL değerinden canvas Y koordinatı hesaplar.
     * Üst → -10 dB HL, Alt → 120 dB HL (klinik standart).
     */
    private double dbY(int dbHL, double plotH, int dbRange) {
        double ratio = (double)(dbHL - DB_MIN) / dbRange;
        return MARGIN_TOP + ratio * plotH;
    }

    // ================================================================== //
    //  Dışa Aktarma                                                       //
    // ================================================================== //

    private void exportAsPng() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Odyogramı PNG Olarak Kaydet");
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PNG Görseli", "*.png"));
        fc.setInitialFileName("audiogram.png");

        File file = fc.showSaveDialog(audiogramCanvas.getScene().getWindow());
        if (file == null) return;

        WritableImage snapshot = audiogramCanvas.snapshot(null, null);
        try {
            ImageIO.write(SwingFXUtils.fromFXImage(snapshot, null), "png", file);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private void exportAsPdf() {
        // PDF dışa aktarma: ilerideki aşamada Apache PDFBox ile entegre edilecek.
        // pom.xml'de pdfbox 3.0.0 zaten tanımlı.
        System.out.println("[TODO] PDF dışa aktarma – Apache PDFBox entegrasyonu bekleniyor.");
    }
}
