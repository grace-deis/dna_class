package gui;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.regex.*;
import javax.swing.*;
import javax.swing.text.*;

import dna.Dna;
import logger.LogEvent;
import logger.Logger;
import model.Regex;
import model.Statement;
import model.Entity;
import model.Value;
import model.StatementType;
import text.NaiveBayesClassifier;

public class TextPanel extends JPanel {
	private static final long serialVersionUID = -8094978928012991210L;
	private JTextPane textWindow;
	private JScrollPane textScrollPane;
	private DefaultStyledDocument doc;
	private StyleContext sc;
	private int documentId;
	private ArrayList<PredictedStatement> predictedStatements = new ArrayList<>();
	private String annotationMethod = null;

	public TextPanel() {
		this.setLayout(new BorderLayout());
		sc = new StyleContext();
		doc = new DefaultStyledDocument(sc);
		textWindow = new JTextPane(doc);

		Style defaultStyle = sc.getStyle(StyleContext.DEFAULT_STYLE);
		final Style mainStyle = sc.addStyle("MainStyle", defaultStyle);
		StyleConstants.setLeftIndent(mainStyle, 16);
		StyleConstants.setRightIndent(mainStyle, 16);
		StyleConstants.setFirstLineIndent(mainStyle, 16);
		StyleConstants.setFontFamily(mainStyle, "Serif");
		StyleConstants.setFontSize(mainStyle, 12);

		Font font = new Font("Monospaced", Font.PLAIN, 14);
		textWindow.setFont(font);

		textWindow.setEditable(false);

		textScrollPane = new JScrollPane(textWindow);
		textScrollPane.setPreferredSize(new Dimension(500, 450));
		textScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
		this.add(textScrollPane);

		/* Mouse Listener for Text Pane that shows prediction popup */
		textWindow.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) {
				int caretPos = textWindow.getCaretPosition();
				for (PredictedStatement ps : predictedStatements) {
					if (caretPos >= ps.getStart() && caretPos < ps.getStop()) {
						showPredictionPopupForStatement(ps);
						break;
					}
				}
			}
		});
	}

	JTextPane getTextWindow() {
		return textWindow;
	}

	JScrollPane getTextScrollPane() {
		return textScrollPane;
	}

	int getVerticalScrollLocation() {
		return (int) textScrollPane.getViewport().getViewPosition().getY();
	}

	void setVerticalScrollLocation(int verticalScrollLocation) {
		SwingUtilities.invokeLater(() -> textScrollPane.getViewport().setViewPosition(new Point(0, verticalScrollLocation)));
	}

	public void setAnnotationMethod(String method) {
		this.annotationMethod = method;
	}

	public void setPredictedStatements(ArrayList<PredictedStatement> preds) {
		this.predictedStatements = preds;
	}

	public boolean hasPredictedHighlights() {
		return (predictedStatements != null && !predictedStatements.isEmpty());
	}

	public void suggestHighlights() {
		if (annotationMethod == null || annotationMethod.equals("Select Automatic Annotation Method")) {
			clearPredictedStatements();
			return;
		}
		if ("Naive Bayes".equals(annotationMethod)) {
			highlightUsingNaiveBayes();
		}
	}

	/**
	 * Highlights text using the Naive Bayes classifier.
	 */
	private void highlightUsingNaiveBayes() {
		var classifiers = NaiveBayesClassifier.trainByVariableGroups();
		if (classifiers != null && !classifiers.isEmpty()) {
			String text = textWindow.getText();
			ArrayList<PredictedStatement> preds = new ArrayList<>();
			String[] sentences = smartSentenceSplit(text);
			int pos = 0;
			int statementTypeId = dna.Dna.sql.getMostCommonStatementTypeId();
			for (String sentence : sentences) {
				int start = text.indexOf(sentence, pos);
				int stop = start + sentence.length();
				Map<String, String> predictedValues = new HashMap<>();
				Map<String, Double> predictedProbs = new HashMap<>();
				for (String var : classifiers.keySet()) {
					NaiveBayesClassifier.TrainedClassifier tc = classifiers.get(var);
					double[] x = NaiveBayesClassifier.vectorizeTfIdf(sentence, tc.vocab, tc.vocabDf, sentences.length);
					int predIdx = tc.model.predict(x);
					String predictedVar = NaiveBayesClassifier.inverseLabelMap(tc.labelMap, predIdx);
					predictedValues.put(var, predictedVar);

					double[] probs = tc.model.predictProba(x);
					predictedProbs.put(var, probs[predIdx]);
				}
				// Only highlight if concept is not "nonstatement"
				String conceptVal = predictedValues.get("concept");
				if (conceptVal != null && !"nonstatement".equalsIgnoreCase(conceptVal)) {
					preds.add(new PredictedStatement(start, stop, statementTypeId, predictedValues, predictedProbs));
				}
				pos = stop;
			}
			setPredictedStatements(preds);
			paintStatements();
		}
	}

	/*Insert future highlightUsingClassifier method here */

	/*splits text into sentences ignoring abbreviations*/

	public static String[] smartSentenceSplit(String text) {
		String abbrevPattern = "(Mr|Ms|Mrs|Dr|Prof|hon|Sr|Jr)\\.";
		String safeText = text.replaceAll(abbrevPattern, "$1<ABBR_DOT>");
		String pattern = "(?<=[.!?])\\s+(?=[A-Z])";
		String[] sentences = safeText.split(pattern);
		for (int i = 0; i < sentences.length; i++) {
			sentences[i] = sentences[i].replaceAll("<ABBR_DOT>", ".");
		}
		return sentences;
	}

	/* Clears the predicted statements */
	public void clearPredictedStatements() {
		setPredictedStatements(new ArrayList<>());
		paintStatements();
	}

	/**
	 * Shows a prediction popup for a specific statement.
	 *
	 * @param ps The predicted statement.
	 */
	private void showPredictionPopupForStatement(PredictedStatement ps) {
		ArrayList<model.StatementType> types = dna.Dna.sql.getStatementTypes();
		model.StatementType stype = null;
		int statementTypeId = ps.getStatementTypeId();
		for (model.StatementType t : types) {
			if (t.getId() == statementTypeId) {
				stype = t;
				break;
			}
		}
		if (stype == null) return;

		// Make stype final for use in lambdas
		final model.StatementType stypeFinal = stype;

		java.awt.Frame frame = (java.awt.Frame) SwingUtilities.getWindowAncestor(this);
		JDialog popup = new JDialog(frame, "Predicted annotation", true);
		popup.setLayout(new java.awt.BorderLayout(10, 10));

		JPanel contentPanel = new JPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));

		Map<String, Object> editors = new HashMap<>();
		System.out.println("StatementType ID: " + stypeFinal.getId());
		for (model.Value v : stypeFinal.getVariables()) {
			System.out.println("Variable: " + v.getKey() + " (" + v.getDataType() + ")");
		}
		System.out.println("StatementType variables:");
		for (model.Value v : stypeFinal.getVariables()) {
			System.out.println(v.getKey() + " (" + v.getDataType() + ")");
		}
		for (model.Value v : stypeFinal.getVariables()) {
			String var = v.getKey();
			String dataType = v.getDataType();
			String predValue = ps.getPredictedValues().get(var);
			Double predProb = ps.getPredictedProbs() != null ? ps.getPredictedProbs().get(var) : null;

			JPanel row = new JPanel(new BorderLayout());
			JLabel label = new JLabel(var + ":");

			JComponent editor = null;

			if ("boolean".equals(dataType)) {
				int entry = 0;
				try {
					if (predValue != null) {
						if (predValue.equalsIgnoreCase("true") || predValue.equalsIgnoreCase("yes")) {
							entry = 1;
						} else if (predValue.equalsIgnoreCase("false") || predValue.equalsIgnoreCase("no")) {
							entry = 0;
						} else {
							entry = Integer.parseInt(predValue);
						}
					}
				} catch (Exception ex) {
					entry = 0;
				}
				boolean val = (entry != 0);
				BooleanButtonPanel buttons = new BooleanButtonPanel();
				buttons.setYes(val);
				buttons.setEnabled(true);
				editor = buttons;
				editors.put(var, buttons);
			} else if ("integer".equals(dataType)) {
				JTextField field = new JTextField(predValue != null ? predValue : "");
				editor = field;
				editors.put(var, field);
			} else if ("short text".equals(dataType)) {
				ArrayList<Integer> variableIdList = new ArrayList<>();
				variableIdList.add(v.getVariableId());
				ArrayList<ArrayList<model.Entity>> allEntities = dna.Dna.sql.getEntities(variableIdList, true);
				if (allEntities != null && !allEntities.isEmpty() && !allEntities.get(0).isEmpty()) {
					JComboBox<String> combo = new JComboBox<>();
					for (model.Entity ent : allEntities.get(0)) {
						combo.addItem(ent.getValue());
					}
					combo.setEditable(true);
					if (predValue != null) combo.setSelectedItem(predValue);
					editor = combo;
					editors.put(var, combo);
				} else {
					JTextField field = new JTextField(predValue != null ? predValue : "");
					editor = field;
					editors.put(var, field);
				}
			} else { // "long text" or anything else
				JTextField field = new JTextField(predValue != null ? predValue : "");
				editor = field;
				editors.put(var, field);
			}
			row.add(label, BorderLayout.WEST);
			row.add(editor, BorderLayout.CENTER);

			//show probability as a label
			if (predProb != null) {
				JLabel probLabel = new JLabel(String.format("  (p=%.2f)", predProb));
				row.add(probLabel, BorderLayout.EAST);
			}

			contentPanel.add(row);
		}
		popup.add(contentPanel, java.awt.BorderLayout.CENTER);

		JPanel buttonPanel = new JPanel();
		JButton acceptButton = new JButton("Accept");
		JButton rejectButton = new JButton("Reject");
		buttonPanel.add(acceptButton);
		buttonPanel.add(rejectButton);
		popup.add(buttonPanel, java.awt.BorderLayout.SOUTH);

		// Make documentId and textWindow final for lambda usage
		final int docIdFinal = this.documentId;
		final JTextPane textWindowFinal = this.textWindow;

		acceptButton.addActionListener(e -> {
			ArrayList<model.Value> filledValues = new ArrayList<>();
			for (model.Value v : stypeFinal.getVariables()) {
				String var = v.getKey();
				String dataType = v.getDataType();
				Object valueObj = null;
				if ("boolean".equals(dataType)) {
					BooleanButtonPanel buttons = (BooleanButtonPanel) editors.get(var);
					int val = buttons.isYes() ? 1 : 0;
					valueObj = val;
				} else if ("integer".equals(dataType)) {
					JTextField field = (JTextField) editors.get(var);
					String val = field.getText();
					try {
						valueObj = Integer.valueOf(val);
					} catch (Exception ex) {
						valueObj = 0;
					}
				} else if ("short text".equals(dataType)) {
					String val;
					Object ed = editors.get(var);
					if (ed instanceof JComboBox) {
						Object selected = ((JComboBox<?>) ed).getSelectedItem();
						val = selected != null ? selected.toString() : "";
					} else {
						val = ((JTextField) ed).getText();
					}
					model.Entity ent = null;
					ArrayList<Integer> variableIdList = new ArrayList<>();
					variableIdList.add(v.getVariableId());
					ArrayList<ArrayList<model.Entity>> allEntities = dna.Dna.sql.getEntities(variableIdList, true);
					if (allEntities != null && !allEntities.isEmpty() && !allEntities.get(0).isEmpty()) {
						for (model.Entity candidate : allEntities.get(0)) {
							if (candidate.getValue().equals(val)) {
								ent = candidate;
								break;
							}
						}
					}
					if (ent == null) ent = new model.Entity(val);
					valueObj = ent;
				} else if ("long text".equals(dataType)) {
					JTextField field = (JTextField) editors.get(var);
					valueObj = field.getText();
				} else {
					JTextField field = (JTextField) editors.get(var);
					valueObj = field.getText();
				}
				filledValues.add(new model.Value(v.getVariableId(), var, dataType, valueObj));
			}
			model.Statement s = new model.Statement(
				ps.getStart(),
				ps.getStop(),
				stypeFinal.getId(),
				dna.Dna.sql.getActiveCoder().getId(),
				filledValues
			);
			s.setDocumentId(docIdFinal);
			s.setText(textWindowFinal.getText().substring(ps.getStart(), ps.getStop()));
			int insertedId = dna.Dna.sql.addStatement(s, docIdFinal);
			if (insertedId > 0) {
				predictedStatements.remove(ps);
				paintStatements();
			}
			popup.dispose();
		});

		rejectButton.addActionListener(e -> {
			predictedStatements.remove(ps);
			paintStatements();
			popup.dispose();
		});

		popup.pack();
		popup.setLocationRelativeTo(this);
		popup.setVisible(true);
	}

	/**
	 * Inserts a predicted statement as a coded statement.
	 *
	 * @param ps The predicted statement.
	 */
	private void insertPredictedStatementAsCoded(PredictedStatement ps) {
		ArrayList<model.StatementType> types = dna.Dna.sql.getStatementTypes();
		model.StatementType stype = null;
		int statementTypeId = ps.getStatementTypeId();
		for (model.StatementType t : types) {
			if (t.getId() == statementTypeId) {
				stype = t;
				break;
			}
		}
		if (stype == null) return;

		ArrayList<model.Value> filledValues = new ArrayList<>();
		for (model.Value v : stype.getVariables()) {
			String key = v.getKey();
			String dataType = v.getDataType();
			String predicted = ps.getPredictedValues().get(key);
			Object valueObj = null;
			if ("short text".equals(dataType)) {
				model.Entity ent = null;
				if (predicted != null) {
					ArrayList<Integer> variableIdList = new ArrayList<>();
					variableIdList.add(v.getVariableId());
					ArrayList<ArrayList<model.Entity>> allEntities = Dna.sql.getEntities(variableIdList, true);
					if (allEntities != null && !allEntities.isEmpty() && !allEntities.get(0).isEmpty()) {
						for (model.Entity candidate : allEntities.get(0)) {
							if (candidate.getValue().equals(predicted)) {
								ent = candidate;
								break;
							}
						}
					}
					if (ent == null) ent = new model.Entity(predicted);
				}
				valueObj = ent;
			} else if ("integer".equals(dataType)) {
				if (predicted != null && !predicted.isEmpty()) {
					try {
						valueObj = Integer.valueOf(predicted);
					} catch (NumberFormatException ex) {
						valueObj = 0;
					}
				} else {
					valueObj = 0;
				}
			} else if ("boolean".equals(dataType)) {
				if (predicted != null) {
					valueObj = Boolean.valueOf(predicted);
				} else {
					valueObj = false;
				}
			} else if ("long text".equals(dataType)) {
				valueObj = (predicted != null) ? predicted : "";
			} else {
				valueObj = predicted;
			}
			filledValues.add(new model.Value(v.getVariableId(), key, dataType, valueObj));
		}
		model.Statement s = new model.Statement(ps.getStart(), ps.getStop(), stype.getId(), Dna.sql.getActiveCoder().getId(), filledValues);
		s.setDocumentId(this.documentId);
		s.setText(textWindow.getText().substring(ps.getStart(), ps.getStop()));
		int insertedId = Dna.sql.addStatement(s, this.documentId);
		if (insertedId > 0) {
			predictedStatements.remove(ps);
			paintStatements();
		}
	}


	void setContents(int documentId, String text) {
		this.textWindow.setText(text);
		this.documentId = documentId;
		paintStatements();
		textWindow.setCaretPosition(0);
	}

	void paintStatements() {
		if (documentId > -1) {
			int initialStart = 0;
			int initialEnd = textWindow.getText().length();
			Style blackStyle = sc.addStyle("ConstantWidth", null);
			StyleConstants.setForeground(blackStyle, Color.black);
			StyleConstants.setBackground(blackStyle, Color.white);
			doc.setCharacterAttributes(initialStart, initialEnd - initialStart, blackStyle, false);

			ArrayList<Statement> statements = Dna.sql.getShallowStatements(documentId);
			ArrayList<int[]> codedRanges = new ArrayList<>();
			int i, start;
			for (i = 0; i < statements.size(); i++) {
				start = statements.get(i).getStart();
				Style bgStyle = sc.addStyle("ConstantWidth", null);
				if (Dna.sql.getActiveCoder() != null &&
						(statements.get(i).getCoderId() == Dna.sql.getActiveCoder().getId() || Dna.sql.getActiveCoder().isPermissionViewOthersStatements()) &&
						(statements.get(i).getCoderId() == Dna.sql.getActiveCoder().getId() || Dna.sql.getActiveCoder().getCoderRelations().get(statements.get(i).getCoderId()).isViewStatements())) {
					if (Dna.sql.getActiveCoder().isColorByCoder()) {
						StyleConstants.setBackground(bgStyle, statements.get(i).getCoderColor().toAWTColor());
					} else {
						StyleConstants.setBackground(bgStyle, statements.get(i).getStatementTypeColor().toAWTColor());
					}
				}
				doc.setCharacterAttributes(start, statements.get(i).getStop() - start, bgStyle, false);
				codedRanges.add(new int[]{start, statements.get(i).getStop()});
			}

			for (PredictedStatement ps : predictedStatements) {
				int predStart = ps.getStart();
				int predStop = ps.getStop();
				boolean overlaps = false;
				for (int[] range : codedRanges) {
					if (predStart < range[1] && predStop > range[0]) {
						overlaps = true;
						break;
					}
				}
				if (overlaps) continue;

				double minAlpha = 25;
				double maxAlpha = 220;
				double curve = 4.0;
				Double certainty = 1.0;
				if (ps.getPredictedProbs() != null && ps.getPredictedProbs().containsKey("concept")) {
					certainty = ps.getPredictedProbs().get("concept");
				}
				int alpha = (int) (minAlpha + (maxAlpha - minAlpha) * Math.pow(certainty, curve));
				Color highlightColor = new Color(100, 149, 237, alpha);
				Style predStyle = sc.addStyle("PredictedHighlight", null);
				StyleConstants.setBackground(predStyle, highlightColor);
				int predLength = predStop - predStart;
				if (predStart >= 0 && predStart + predLength <= textWindow.getText().length()) {
					doc.setCharacterAttributes(predStart, predLength, predStyle, false);
				}
			}

			ArrayList<Regex> regex = Dna.sql.getRegexes();
			for (i = 0; i < regex.size(); i++) {
				String label = regex.get(i).getLabel();
				model.Color color = regex.get(i).getColor();
				Pattern p = Pattern.compile(label, Pattern.CASE_INSENSITIVE);
				Matcher m = p.matcher(textWindow.getText());
				while(m.find()) {
					start = m.start();
					Style fgStyle = sc.addStyle("ConstantWidth", null);
					StyleConstants.setForeground(fgStyle, color.toAWTColor());
					doc.setCharacterAttributes(start, m.end() - start, fgStyle, false);
				}
			}
		}
	}

	void adjustToChangedCoder() {
		if (Dna.sql.getConnectionProfile() == null) {
			Font font = new Font("Monospaced", Font.PLAIN, 14);
			textWindow.setFont(font);
		} else {
			try {
				Font font = new Font("Monospaced", Font.PLAIN, Dna.sql.getActiveCoder().getFontSize());
				textWindow.setFont(font);
				paintStatements();
			} catch (NullPointerException e) {
				LogEvent l = new LogEvent(Logger.ERROR,
						"Statements could not be painted in text.",
						"The statements could not be painted in the current document. This could be because you attempted to open a database that was created with DNA 2.0. Please create a new DNA 3 database and import documents from the old file if that is the case. Make sure you close the current database as soon as possible to avoid damage.",
						e);
				Dna.logger.log(l);
			}
		}
	}

	/**
	 * Represents a predicted statement with its associated metadata.
	 */
	public class PredictedStatement {
		private int start, stop, statementTypeId;
		private Map<String, String> predictedValues;
		private Map<String, Double> predictedProbs;
		public PredictedStatement(int start, int stop, int statementTypeId, Map<String, String> predictedValues, Map<String, Double> predictedProbs) {
			this.start = start;
			this.stop = stop;
			this.statementTypeId = statementTypeId;
			this.predictedValues = predictedValues;
			this.predictedProbs = predictedProbs;
		}
		public int getStart() { return start; }
		public int getStop() { return stop; }
		public int getStatementTypeId() { return statementTypeId; }
		public Map<String, String> getPredictedValues() { return predictedValues; }
		public Map<String, Double> getPredictedProbs() { return predictedProbs; }
	}
}