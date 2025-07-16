package gui;
import weka.core.*;

import java.util.Arrays;

import weka.classifiers.bayes.NaiveBayesMultinomialText;

public class NaiveBayesClassifier {
    private NaiveBayesMultinomialText model;
    private Instances trainingData;
    private StringToWordVector vectorFilter;

    public NaiveBayesClassifier() throws Exception {
        trainingData = new Instances("TrainData", new ArrayList<>(Arrays.asList(
            new Attribute("text", (List<String>) null),
            new Attribute("class", new ArrayList<>(/*distinct labels fetched from DB */))
        )), 0);
        trainingData.setClassIndex(1);

        vectorFilter = new StringToWordVector();
        vectorFilter.setTFTransform(false);
        vectorFilter.setIDFTransform(false);
        vectorFilter.setInputFormat(trainingData);

        model = new NaiveBayesMultinomialText();

    }

    public void train(List<String> texts, List<String> labels) throws Exception {
        for (int i = 0; i < texts.size(); i++) {
            DenseInstance inst = new DenseInstance(2);
            inst.setValue(trainingData.attribute("text"), texts.get(i));
            inst.setValue(trainingData.attribute("class"), labels.get(i));
            trainingData.add(inst);
        }
        Instances vectData = Filter.useFilter(trainingData, vectorFilter);
        vectData.setClassIndex(1);
        model.buildClassifier(vectData);
    }

    public List<PredictedStatement> predictedSentences(List<Sentence> sentences) throws Exception {
        List<PredictedStatement> preds = new ArrayList<>();
        Instances testFormat = new Instances(trainingData, 0);
        testFormat.setClassIndex(1);
        for (Sentence s : sentences) {
            DenseInstance inst = new DenseInstance(2);
            inst.setValue(testFormat.attribute("text"), s.text);
            testFormat.add(inst);
        }

        Instances vectTest = Filter.useFilter(testFormat, vectorFilter);
        vectTest.setClassIndex(1);
        for (int i = 0; i < vectTest.seize(); i++) {
            Instance inst = vectTest.get(i);
            double[] dist = model.distributionForInstance(inst);
            double maxP = -1; int idx = -1;
            for (int j = 0; j < dist.length; j++) {
                if (dist[j] > maxP) {maxP = dist[j]; idx = j; }
            }
            if (maxP > 0.6) {
                Sentence s = sentences.get(i);
                preds.add(new PredictedStatement(s.start, s.stop, maxP));
            }
        }
        return preds;
    }
}
