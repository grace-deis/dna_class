package gui;

public class PredictedStatement {
    private int start;
    private int stop;
    private double confidence;

    public PredictedStatement(int start, int stop, double confidence){
        this.start = start;
        this.stop = stop;
        this.confidence = confidence;
    }

    public int getStart(){
        return start;
    }
    
    public int getStop(){
        return stop;
    }

    public double getConfidence(){
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    @Override
    public String toString(){
        return "PredictedStatement{" +
        "start=" + start +
        ", stop=" + stop +
        ", confidence=" + confidence +
        '}';
    }
}
