package gui;

public class Sentence {
    public String text;
    public double probability;
    public String topClass;
    public int start;
    public int stop;

    public Sentence(String text, double probability, String topClass) {
        this.text = text;
        this.probability = probability;
        this.topClass = topClass;
    }
}
