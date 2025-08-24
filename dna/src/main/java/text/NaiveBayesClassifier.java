package text;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

import text.PorterStemmer;
import model.Statement;
import model.StatementType;
import model.Value;

/**
 * Trains a separate Naive Bayes classifier for each unique Variable group,
 * predicting Value from Text using TF-IDF features.
 */
public class NaiveBayesClassifier {

    /**
     * Holds a trained classifier for a Variable group.
     */
    public static class TrainedClassifier {
        public final NaiveBayesModel model;
        public final List<String> vocab;
        public final Map<String, Integer> labelMap;
        public final Map<String, Integer> vocabDf; // Document frequency mapping

        public TrainedClassifier(NaiveBayesModel model, List<String> vocab, Map<String, Integer> labelMap, Map<String, Integer> vocabDf) {
            this.model = model;
            this.vocab = vocab;
            this.labelMap = labelMap;
            this.vocabDf = vocabDf;
        }
    }

    /**
     * Helper to hold one training sample.
     */
    public static class Sample {
        String text;
        String label;
        public Sample(String text, String label) {
            this.text = text;
            this.label = label;
        }
    }

    /**
     * Trains classifiers grouped by Variable.
     * Includes both coded and negative (uncoded) sentences as "nonstatement".
     * @return Map of Variable to trained classifier.
     */
    public static Map<String, TrainedClassifier> trainByVariableGroups() {
        Map<String, List<Sample>> samplesByVariable = new HashMap<>();

        // SQL extraction (coded statements)
        String subString = "SUBSTRING(DOCUMENTS.Text, STATEMENTS.Start + 1, STATEMENTS.Stop - STATEMENTS.Start) AS Text";
        if (dna.Dna.sql.getConnectionProfile().getType().equals("postgresql")) {
            subString = "SUBSTRING(DOCUMENTS.Text, CAST(Start + 1 AS INT4), CAST(Stop - Start AS INT4)) AS Text";
        }
        String sql =
            "SELECT DISTINCT STATEMENTS.ID, STATEMENTS.DocumentId as DocumentId, " + subString + ", ENTITIES.Value, VARIABLES.Variable, VARIABLES.ID " +
            "FROM STATEMENTS " +
            "INNER JOIN DOCUMENTS ON DOCUMENTS.ID = STATEMENTS.DocumentId " +
            "INNER JOIN DATASHORTTEXT ON DATASHORTTEXT.StatementId = STATEMENTS.ID " +
            "INNER JOIN ENTITIES ON ENTITIES.ID = DATASHORTTEXT.Entity " +
            "INNER JOIN VARIABLES ON VARIABLES.ID = ENTITIES.VariableId;";

        Set<Integer> documentIds = new HashSet<>();
        try (Connection conn = dna.Dna.sql.getDataSource().getConnection();
             PreparedStatement s = conn.prepareStatement(sql);
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
                String value = rs.getString("Value");
                String variable = rs.getString("Variable");
                String text = rs.getString("Text");
                int documentId = rs.getInt("DocumentId");
                documentIds.add(documentId);
                if (value != null && variable != null && text != null && !text.trim().isEmpty()) {
                    samplesByVariable.computeIfAbsent(variable, k -> new ArrayList<>())
                        .add(new Sample(text, value));
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            return Collections.emptyMap();
        }

        // Dynamically get all variable names from all statement types
        int statementTypeId = dna.Dna.sql.getMostCommonStatementTypeId();
        System.out.println("Most common statement type ID: " + statementTypeId);
        Set<String> variableNameSet = new HashSet<>();
        ArrayList<model.StatementType> statementTypes = dna.Dna.sql.getStatementTypes();
        model.StatementType targetType = null;
        for (model.StatementType st : statementTypes) {
            if (st.getId() == statementTypeId) {
                targetType = st;
                break;
            }
        }
        if (targetType != null) {
            for (model.Value v : targetType.getVariables()) {
                variableNameSet.add(v.getKey());
            }
        } else {
            System.err.println("No StatementType found with ID: " + statementTypeId);
        }

        List<String> variableNames = new ArrayList<>(variableNameSet);


        // Get all document IDs, not just those with coded statements
        Set<Integer> allDocumentIds = new HashSet<>();
        try (Connection conn = dna.Dna.sql.getDataSource().getConnection();
            PreparedStatement s = conn.prepareStatement("SELECT ID FROM DOCUMENTS");
            ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
                allDocumentIds.add(rs.getInt("ID"));
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        // For each document, find uncoded sentences to add as "nonstatement"
        Random rand = new Random();
        for (int docId : allDocumentIds) {
            String documentText = dna.Dna.sql.getDocumentText(docId);
            ArrayList<Statement> codedStatements = dna.Dna.sql.getShallowStatements(docId);

            // Get coded regions for overlap check
            List<int[]> codedRanges = new ArrayList<>();
            for (Statement s : codedStatements) {
                codedRanges.add(new int[] { s.getStart(), s.getStop() });
            }

            // Split into sentences
            String[] sentences = gui.TextPanel.smartSentenceSplit(documentText);
            int pos = 0;
            List<String> uncoded = new ArrayList<>();
            for (String sentence : sentences) {
                int start = documentText.indexOf(sentence, pos);
                int stop = start + sentence.length();
                pos = stop;

                boolean overlaps = false;
                for (int[] range : codedRanges) {
                    if (start < range[1] && stop > range[0]) {
                        overlaps = true;
                        break;
                    }
                }
                if (!overlaps) {
                    uncoded.add(sentence.trim());
                }
            }

            // Only add "nonstatement" for variable "concept"
            if (variableNames.contains("concept") && !uncoded.isEmpty()) {
                Collections.shuffle(uncoded, rand);
                int N = Math.min(uncoded.size(), codedStatements.size()*2);
                System.out.println("Adding " + N + " uncoded sentences as 'nonstatement' for document ID " + docId);
                System.out.println("Uncoded sentences: " + uncoded.size() + ", Coded statements: " + codedStatements.size());
                for (int i = 0; i < N; i++) {
                    String nonstatement = uncoded.get(i);
                    samplesByVariable.computeIfAbsent("concept", k -> new ArrayList<>())
                        .add(new Sample(nonstatement, "nonstatement"));
                }
            }
        }

        Map<String, TrainedClassifier> classifiers = new HashMap<>();
        for (Map.Entry<String, List<Sample>> entry : samplesByVariable.entrySet()) {
            String variable = entry.getKey();
            List<Sample> samples = entry.getValue();
            if (samples.size() < 2) continue;

            // Vectorization
            List<String> vocab = buildVocab(samples);
            Map<String, Integer> df = documentFrequency(samples, vocab);
            double[][] X = vectorizeTfIdf(samples, vocab, df);
            Map<String, Integer> labelMap = encodeLabels(samples);
            int[] y = encodeLabelsArray(samples, labelMap);

            // Train NB model (custom implementation)
            NaiveBayesModel nb = trainNaiveBayes(X, y, labelMap.size());

            classifiers.put(variable, new TrainedClassifier(nb, vocab, labelMap, df));
        }

        System.out.println("Trained classifiers for " + classifiers.size() + " Variable groups (including nonstatements).");
        return classifiers;
    }



    // --- Vectorization helpers ---

    /** Build vocab list from all samples with stemming. */
    private static List<String> buildVocab(List<Sample> samples) {
        Set<String> vocabSet = new HashSet<>();
        for (Sample sample : samples) {
            for (String token : tokenize(sample.text)) {
                vocabSet.add(token);
            }
        }
        List<String> vocab = new ArrayList<>(vocabSet);
        Collections.sort(vocab);
        return vocab;
    }

    /** Document frequency for each vocab word. */
    private static Map<String, Integer> documentFrequency(List<Sample> samples, List<String> vocab) {
        Map<String, Integer> df = new HashMap<>();
        for (String v : vocab) df.put(v, 0);
        for (Sample sample : samples) {
            Set<String> tokensInDoc = new HashSet<>(Arrays.asList(tokenize(sample.text)));
            for (String token : tokensInDoc) {
                if (df.containsKey(token)) {
                    df.put(token, df.getOrDefault(token, 0) + 1);
                }
            }
        }
        return df;
    }

    /** Compute TF-IDF vectors (features) for all samples. */
    public static double[][] vectorizeTfIdf(List<Sample> samples, List<String> vocab, Map<String, Integer> df) {
        int N = samples.size();
        double[][] X = new double[N][vocab.size()];
        for (int i = 0; i < N; i++) {
            String[] tokens = tokenize(samples.get(i).text);
            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) tf.put(token, tf.getOrDefault(token, 0) + 1);
            for (int j = 0; j < vocab.size(); j++) {
                String term = vocab.get(j);
                int tfTerm = tf.getOrDefault(term, 0);
                int dfTerm = df.getOrDefault(term, 1);
                double idf = Math.log((double) N / (dfTerm));
                X[i][j] = tfTerm * idf;
            }
        }
        return X;
    }

    /** Compute TF-IDF vector for a single sentence/sample. */
    public static double[] vectorizeTfIdf(String sentence, List<String> vocab, Map<String, Integer> df, int N) {
        String[] tokens = tokenize(sentence);
        Map<String, Integer> tf = new HashMap<>();
        for (String token : tokens) tf.put(token, tf.getOrDefault(token, 0) + 1);
        double[] x = new double[vocab.size()];
        for (int j = 0; j < vocab.size(); j++) {
            String term = vocab.get(j);
            int tfTerm = tf.getOrDefault(term, 0);
            int dfTerm = df.getOrDefault(term, 1);
            double idf = Math.log((double) N / (dfTerm));
            x[j] = tfTerm * idf;
        }
        return x;
    }

    /** Tokenizer (lowercase, remove punctuation, stem words). */
    public static String[] tokenize(String text) {
        String[] rawTokens = text.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+");
        PorterStemmer stemmer = new PorterStemmer();
        ArrayList<String> stems = new ArrayList<>();
        for (String token : rawTokens) {
            if (token.isEmpty()) continue;
            String stem = stemmer.stem(token);
            if (!stem.isEmpty()) stems.add(stem);
        }
        return stems.toArray(new String[0]);
    }

    // --- Label encoding ---

    /** Map label string to integer. */
    private static Map<String, Integer> encodeLabels(List<Sample> samples) {
        Map<String, Integer> labelMap = new HashMap<>();
        int idx = 0;
        for (Sample s : samples) {
            if (!labelMap.containsKey(s.label)) {
                labelMap.put(s.label, idx++);
            }
        }
        return labelMap;
    }

    /** Get label array. */
    private static int[] encodeLabelsArray(List<Sample> samples, Map<String, Integer> labelMap) {
        int[] y = new int[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            y[i] = labelMap.get(samples.get(i).label);
        }
        return y;
    }

    // --- Custom Naive Bayes implementation ---

    /**
     * Trains a multinomial Naive Bayes model.
     * Returns model with log-probabilities for each class and feature.
     */
    private static NaiveBayesModel trainNaiveBayes(double[][] X, int[] y, int nClasses) {
        int n = X.length, d = X[0].length;
        double[] classCounts = new double[nClasses];
        for (int i : y) classCounts[i]++;
        double[] logPrior = new double[nClasses];
        for (int c = 0; c < nClasses; c++) {
            logPrior[c] = Math.log((classCounts[c] + 1.0) / (n + nClasses));
        }

        // Feature counts per class
        double[][] featureSums = new double[nClasses][d];
        double[] totalFeatureSums = new double[nClasses];
        for (int i = 0; i < n; i++) {
            int c = y[i];
            for (int j = 0; j < d; j++) {
                featureSums[c][j] += X[i][j];
                totalFeatureSums[c] += X[i][j];
            }
        }

        // Calculate log likelihoods: log(P(feature|class)) for multinomial NB (with Laplace smoothing)
        double[][] logLikelihood = new double[nClasses][d];
        for (int c = 0; c < nClasses; c++) {
            for (int j = 0; j < d; j++) {
                logLikelihood[c][j] = Math.log((featureSums[c][j] + 1.0) / (totalFeatureSums[c] + d));
            }
        }
        return new NaiveBayesModel(logPrior, logLikelihood);
    }

    /**
     * Holds NB model parameters.
     */
    public static class NaiveBayesModel {
        public final double[] logPrior;        // log P(class)
        public final double[][] logLikelihood; // log P(feature|class)
        public NaiveBayesModel(double[] logPrior, double[][] logLikelihood) {
            this.logPrior = logPrior;
            this.logLikelihood = logLikelihood;
        }

        /**
         * Predicts the class index for a given vectorized sample.
         */
        public int predict(double[] x) {
            double best = Double.NEGATIVE_INFINITY;
            int bestClass = -1;
            for (int c = 0; c < logPrior.length; c++) {
                double score = logPrior[c];
                for (int j = 0; j < x.length; j++) {
                    score += x[j] * logLikelihood[c][j];
                }
                if (score > best) {
                    best = score;
                    bestClass = c;
                }
            }
            return bestClass;
        }

        /**
         * Returns the probability distribution for all classes (softmax over log-probabilities).
         */
        public double[] predictProba(double[] x) {
            double[] logProbs = new double[logPrior.length];
            for (int c = 0; c < logPrior.length; c++) {
                double score = logPrior[c];
                for (int j = 0; j < x.length; j++) {
                    score += x[j] * logLikelihood[c][j];
                }
                logProbs[c] = score;
            }
            // Softmax
            double maxLog = Double.NEGATIVE_INFINITY;
            for (double lp : logProbs) maxLog = Math.max(maxLog, lp);
            double sum = 0.0;
            double[] probs = new double[logProbs.length];
            for (int i = 0; i < logProbs.length; i++) {
                probs[i] = Math.exp(logProbs[i] - maxLog);
                sum += probs[i];
            }
            for (int i = 0; i < probs.length; i++) {
                probs[i] /= sum;
            }
            return probs;
        }
    }

    /**
     * Get inverse label map (int -> label) from labelMap (label -> int).
     */
    public static String inverseLabelMap(Map<String, Integer> labelMap, int idx) {
        for (Map.Entry<String, Integer> entry : labelMap.entrySet()) {
            if (entry.getValue() == idx) return entry.getKey();
        }
        return null;
    }

    /**
     * Predict the value for each variable group for the given text.
     * For the group where variable="concept", also returns the probability.
     * Returns a map: variable -> PredictionResult(value, prob) (prob is only set for "concept", else -1).
     */
    public static class PredictionResult {
        public final String value;
        public final double prob; // -1 if not calculated

        public PredictionResult(String value, double prob) {
            this.value = value;
            this.prob = prob;
        }
    }

    public static Map<String, PredictionResult> predictAll(Map<String, TrainedClassifier> classifiers, String text) {
        Map<String, PredictionResult> results = new HashMap<>();
        for (Map.Entry<String, TrainedClassifier> entry : classifiers.entrySet()) {
            String variable = entry.getKey();
            TrainedClassifier tc = entry.getValue();
            double[] x = vectorizeTfIdf(text, tc.vocab, tc.vocabDf, 1);
            int predIdx = tc.model.predict(x);
            String predictedValue = inverseLabelMap(tc.labelMap, predIdx);
            double prob = -1.0;
            if ("concept".equals(variable)) {
                double[] probs = tc.model.predictProba(x);
                prob = probs[predIdx];
            }
            results.put(variable, new PredictionResult(predictedValue, prob));
        }
        return results;
    }
}