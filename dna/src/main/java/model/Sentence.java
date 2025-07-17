package model;

public class Sentence {
    public String text;
    public int start;
    public int stop;

    public Sentence(String text, int start, int stop) {
        this.text = text;
        this.start = start;
        this.stop = stop;
    }
}