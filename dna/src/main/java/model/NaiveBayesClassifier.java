package model;

import java.util.*;

public class NaiveBayesClassifier {
    private Map<String, Map<String, Integer>> wordCounts = new HashMap<>();
    private Map<String, Integer> classCounts = new HashMap<>();
    private Set<String> vocabulary = new HashSet<>();
    private int totalSamples = 0;

    public void train(Map<String, List<String>> trainingData) {
        for (Map.Entry<String, List<String>> entry : trainingData.entrySet()) {
            String label = entry.getKey();
            List<String> sentences = entry.getValue();
            classCounts.put(label, classCounts.getOrDefault(label, 0) + sentences.size());
            totalSamples += sentences.size();

            Map<String, Integer> labelWordCounts = wordCounts.computeIfAbsent(label, k -> new HashMap<>());
            for (String sentence : sentences) {
                String[] words = sentence.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+");
                for (String word : words) {
                    if (word.isEmpty()) continue;
                    vocabulary.add(word);
                    labelWordCounts.put(word, labelWordCounts.getOrDefault(word, 0) + 1);
                }
            }
        }
    }

    public Map<String, Double> predictProbs(String sentence) {
        Map<String, Double> logProbs = new HashMap<>();
        String[] words = sentence.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+");
        int vocabSize = vocabulary.size();

        for (String label : classCounts.keySet()) {
            double logProb = Math.log(classCounts.get(label) / (double) totalSamples);
            Map<String, Integer> labelWordCounts = wordCounts.get(label);
            int totalWordsInClass = labelWordCounts.values().stream().mapToInt(Integer::intValue).sum();

            for (String word : words) {
                if (word.isEmpty()) continue;
                int count = labelWordCounts.getOrDefault(word, 0);
                // Laplace smoothing
                double wordProb = (count + 1.0) / (totalWordsInClass + vocabSize);
                logProb += Math.log(wordProb);
            }
            logProbs.put(label, logProb);
        }

        // Convert log probabilities to normal probabilities
        double maxLog = Collections.max(logProbs.values());
        double sum = 0.0;
        Map<String, Double> probs = new HashMap<>();
        for (String label : logProbs.keySet()) {
            double prob = Math.exp(logProbs.get(label) - maxLog); // for numerical stability
            probs.put(label, prob);
            sum += prob;
        }
        // Normalize
        for (String label : probs.keySet()) {
            probs.put(label, probs.get(label) / sum);
        }
        return probs;
    }
}