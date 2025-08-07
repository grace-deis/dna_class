package text;

public class Prediction {
    public String value;
    public double confidence;
    public Prediction(String value, double confidence) {
        this.value = value;
        this.confidence = confidence;
    }
}