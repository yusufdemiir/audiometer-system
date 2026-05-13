import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.Map;
import java.util.TreeMap;

public class AudiogramPlotter extends Canvas {

    private final double margin = 50;
    
    // Test frekans aralığı
    private final int[] frequencies = {250, 500, 1000, 2000, 4000, 8000};
    private final int minFreq = 250;
    private final int maxFreq = 8000;
    
    // Desibel aralığı 
    private final int minDB = -10;
    private final int maxDB = 120;

    private Map<Integer, Integer> rightEarData = new TreeMap<>();
    private Map<Integer, Integer> leftEarData = new TreeMap<>();

    public AudiogramPlotter(double width, double height) {
        super(width, height);
        drawBaseAudiogram();
    }

    // sağ kulak plot
    public void addRightEarThreshold(int frequency, int db) {
        rightEarData.put(frequency, db);
        refreshPlot();
    }

    //sol kulak plot
    public void addLeftEarThreshold(int frequency, int db) {
        leftEarData.put(frequency, db);
        refreshPlot();
    }

    //temizle ve çiz
    private void refreshPlot() {
        GraphicsContext gc = getGraphicsContext2D();
        gc.clearRect(0, 0, getWidth(), getHeight());
        
        drawBaseAudiogram();
        // Sağ red
        plotData(gc, rightEarData, Color.RED, "O");
        // Sol blue
        plotData(gc, leftEarData, Color.BLUE, "X");
    }

    //odyogram temelleri çizme
    private void drawBaseAudiogram() {
        GraphicsContext gc = getGraphicsContext2D();
        gc.setStroke(Color.LIGHTGRAY);
        gc.setLineWidth(1);
        gc.setFont(Font.font("Arial", 12));
        gc.setFill(Color.BLACK);

        double width = getWidth();
        double height = getHeight();

        // y ekseni desibel aşağı doğru artar
        for (int db = minDB; db <= maxDB; db += 10) {
            double y = getMapY(db);
            gc.strokeLine(margin, y, width - margin, y);
            gc.fillText(db + " dB", margin - 40, y + 4);
        }

        // x ekseni frekans - Logaritmik/Oktav bazlı dağılım
        for (int freq : frequencies) {
            double x = getMapX(freq);
            gc.strokeLine(x, margin, x, height - margin);
            gc.fillText(freq + " Hz", x - 15, height - margin + 20);
        }
        
        // Eksen sınırları
        gc.setStroke(Color.BLACK);
        gc.strokeRect(margin, margin, width - 2 * margin, height - 2 * margin);
    }
    
    //frekansı logaritmik x koordinatına çevir
    private double getMapX(int frequency) {
        double width = getWidth();
        double logMin = Math.log(minFreq) / Math.log(2);
        double logMax = Math.log(maxFreq) / Math.log(2);
        double logCur = Math.log(frequency) / Math.log(2);
        return margin + (width - 2 * margin) * ((logCur - logMin) / (logMax - logMin));
    }

    //desibeli ters lineer y koordinatına çevir
    private double getMapY(int db) {
        double height = getHeight();
        return margin + (height - 2 * margin) * ((double) (db - minDB) / (maxDB - minDB));
    }

    //veriyi grafiğe işle
    private void plotData(GraphicsContext gc, Map<Integer, Integer> data, Color color, String symbol) {
        if (data.isEmpty()) return;

        gc.setStroke(color);
        gc.setFill(color);
        gc.setFont(Font.font("Arial", FontWeight.BOLD, 16));
        
        double lastX = -1;
        double lastY = -1;

        for (Map.Entry<Integer, Integer> entry : data.entrySet()) {
            double x = getMapX(entry.getKey());
            double y = getMapY(entry.getValue());

            // Sembolü ortalayarak çizme
            gc.fillText(symbol, x - 6, y + 6);

            // Önceki bir nokta varsa çizgiyi çek
            if (lastX != -1) {
                gc.setLineWidth(2);
                gc.strokeLine(lastX, lastY, x, y);
            }
            lastX = x;
            lastY = y;
        }
    }
}