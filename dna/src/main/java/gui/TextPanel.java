package gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.Arrays;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.swing.JDialog;

import dna.Dna;
import logger.LogEvent;
import logger.Logger;
import model.Regex;
import model.Statement;
import text.NaiveBayesClassifier;

/**
 * Text panel, which displays the text of the selected document and paints the
 * statements in the given document in different colors. It keeps the current
 * document ID on record to paint only the statements in the current document.
 */
class TextPanel extends JPanel {
	private static final long serialVersionUID = -8094978928012991210L;
	private JTextPane textWindow;
	private JScrollPane textScrollPane;
	private DefaultStyledDocument doc;
	private StyleContext sc;
	private int documentId;
	private ArrayList<PredictedStatement> predictedStatements = new ArrayList<>();
	private JDialog infoDialog = null;
	private String annotationMethod = null;
	/**
	 * Create a new text panel.
	 * @throws Exception 
	 */
	TextPanel() {
		this.setLayout(new BorderLayout());
		sc = new StyleContext();
	    doc = new DefaultStyledDocument(sc);
		textWindow = new JTextPane(doc);

	    // Create and add the main document style
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
	

        textWindow.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int caretPos = textWindow.getCaretPosition();
                for (PredictedStatement ps : predictedStatements) {
                    if (caretPos >= ps.getStart() && caretPos < ps.getStop()) {
                        showAllPredictedValues(ps);
                        break;
                    }
                }
            }
        });

		// Global listener to hide the dialog if clicked anywhere else
		Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
			if (infoDialog != null && infoDialog.isVisible() && event instanceof MouseEvent) {
				MouseEvent me = (MouseEvent) event;
				if (me.getID() == MouseEvent.MOUSE_PRESSED) {
					Component c = SwingUtilities.getDeepestComponentAt(infoDialog, me.getX(), me.getY());
					if (c == null) {
						hidePredictionInfo();
					}
				}
			}
		}, AWTEvent.MOUSE_EVENT_MASK);
	}

	private void hidePredictionInfo() {
		if (infoDialog != null) {
			infoDialog.setVisible(false);
			infoDialog.dispose();
			infoDialog = null;
		}
	}

	/**
	 * Return the text pane component.
	 * 
	 * @return The text pane in which the document text is displayed.
	 */
	JTextPane getTextWindow() {
		return textWindow;
	}

	/**
	 * Return the text scroll pane.
	 * 
	 * @return The text scroll pane that holds the text pane.
	 */
	JScrollPane getTextScrollPane() {
		return textScrollPane;
	}
	
	/**
	 * Get the current vertical scroll location. This can be used to restore the
	 * scroll location after reloading the document data from the database.
	 * 
	 * @return  An integer giving the vertical scroll position.
	 */
	int getVerticalScrollLocation() {
		return (int) textScrollPane.getViewport().getViewPosition().getY(); // get the scroll position to restore it later
	}

	/**
	 * Set the vertical scroll location. This can be used to restore the scroll
	 * location after reloading the document data from the database.
	 * 
	 * @param verticalScrollLocation  The vertical scroll location.
	 */
	void setVerticalScrollLocation(int verticalScrollLocation) {
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				textScrollPane.getViewport().setViewPosition(new Point(0, verticalScrollLocation)); // scroll to previously saved position
			}
		});
	}

	public void setAnnotationMethod(String method) {
		this.annotationMethod = method;
	}

	public void setPredictedStatements(ArrayList<PredictedStatement> preds) {
        this.predictedStatements = preds;
    }

	public boolean hasPredictedHighlights() {
		if (predictedStatements == null) return false;
		for (PredictedStatement ps : predictedStatements) {
			if (ps.getConceptProb() > 0.7) {
				return true;
			}
		}
		return false;
    }


	public void suggestHighlights() {
		if (annotationMethod == null || annotationMethod.equals("Select Method")) {
			clearPredictedStatements();
			return;
		}
		if ("Naive Bayes".equals(annotationMethod)) {
			highlightUsingNaiveBayes();
		}
		// else if ("Other Method".equals(annotationMethod)) {
		//     highlightUsingOtherMethod();
		// }
	}


	private void highlightUsingNaiveBayes() {
		var classifiers = text.NaiveBayesClassifier.trainByVariableGroups();
		if (classifiers != null && !classifiers.isEmpty()) {
			String text = textWindow.getText();
			ArrayList<PredictedStatement> preds = new ArrayList<>();
			String[] sentences = smartSentenceSplit(text);
			int pos = 0;
			for (String sentence : sentences) {
				int start = text.indexOf(sentence, pos);
				int stop = start + sentence.length();
				Map<String, String> predictedValues = new HashMap<>();
				Double conceptProb = null;
				for (String var : classifiers.keySet()) {
					NaiveBayesClassifier.TrainedClassifier tc = classifiers.get(var);
					double[] x = NaiveBayesClassifier.vectorizeTfIdf(sentence, tc.vocab, tc.vocabDf, sentences.length);
					int predIdx = tc.model.predict(x);
					String predictedVar = NaiveBayesClassifier.inverseLabelMap(tc.labelMap, predIdx);
					predictedValues.put(var, predictedVar);
					if (var.equals("concept")) {
						// Compute concept probability
						double[] classScores = new double[tc.labelMap.size()];
						for (int c = 0; c < classScores.length; c++) {
							double score = tc.model.logPrior[c];
							for (int j = 0; j < x.length; j++) {
								score += x[j] * tc.model.logLikelihood[c][j];
							}
							classScores[c] = score;
						}
						double maxScore = Arrays.stream(classScores).max().orElse(0.0);
						double sumExp = 0.0;
						for (int c = 0; c < classScores.length; c++) {
							classScores[c] = Math.exp(classScores[c] - maxScore);
							sumExp += classScores[c];
						}
						conceptProb = classScores[predIdx] / sumExp;
					}
				}
				preds.add(new PredictedStatement(start, stop, predictedValues, conceptProb));
				pos = stop;
			}
			setPredictedStatements(preds);
			paintStatements();
		}
	}

	public static String[] smartSentenceSplit(String text) {
		// Abbreviations to protect (add more as needed)
		String abbrevPattern = "(Mr|Ms|Mrs|Dr|Prof|hon|Sr|Jr)\\.";
		// Replace periods after abbreviations with a placeholder
		String safeText = text.replaceAll(abbrevPattern, "$1<ABBR_DOT>");
		// Split on punctuation followed by whitespace and a capital letter (likely new sentence)
		String pattern = "(?<=[.!?])\\s+(?=[A-Z])";
		String[] sentences = safeText.split(pattern);
		// Restore periods in abbreviations
		for (int i = 0; i < sentences.length; i++) {
			sentences[i] = sentences[i].replaceAll("<ABBR_DOT>", ".");
		}
		return sentences;
	}

	public void clearPredictedStatements() {
		setPredictedStatements(new ArrayList<>());
		paintStatements();
	}

	private void showAllPredictedValues(PredictedStatement ps) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> entry : ps.getPredictedValues().entrySet()) {
        sb.append("Predicted ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
    }
    if (ps.getConceptProb() != null) {
        sb.append(String.format("Concept probability: %.4f\n", ps.getConceptProb()));
    }

    // Create a non-modal, undecorated dialog that closes on focus lost
    JDialog popup = new JDialog(SwingUtilities.getWindowAncestor(this));
    popup.setUndecorated(true);
    popup.setModal(false);

    JTextArea textArea = new JTextArea(sb.toString());
    textArea.setEditable(false);
    textArea.setOpaque(true);
    textArea.setBackground(new Color(255,255,220));
    textArea.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));

    popup.add(textArea);
    popup.pack();

    // Position: center over this panel, or use mouse location
    Point mouse = MouseInfo.getPointerInfo().getLocation();
    popup.setLocation(mouse);

    // Dispose on focus lost
    popup.addWindowFocusListener(new java.awt.event.WindowAdapter() {
        public void windowLostFocus(java.awt.event.WindowEvent e) {
            popup.dispose();
        }
    });

    popup.setVisible(true);
    popup.requestFocus();
}




	/**
	 * Set the contents of the text panel, including the document ID and text,
	 * and paint the statements in the text, then scroll to the top of the text.
	 * 
	 * @param documentId  ID of the document to display.
	 * @param text        Text of the document to display.
	 */
	void setContents(int documentId, String text) {
		this.textWindow.setText(text);
		this.documentId = documentId;
		paintStatements();
		textWindow.setCaretPosition(0);
	}

	
	/**
	 * Highlight statements and regex in the text by adding color.
	 */
	void paintStatements() {
		if (documentId > -1) {
			// Remove all initial foreground color styles
			int initialStart = 0;
			int initialEnd = textWindow.getText().length();
			Style blackStyle = sc.addStyle("ConstantWidth", null);
			StyleConstants.setForeground(blackStyle, Color.black);
			StyleConstants.setBackground(blackStyle, Color.white);
			doc.setCharacterAttributes(initialStart, initialEnd - initialStart, blackStyle, false);

			// color statements (coded)
			ArrayList<Statement> statements = Dna.sql.getShallowStatements(documentId);
			ArrayList<int[]> codedRanges = new ArrayList<>();
			int i, start;
			for (i = 0; i < statements.size(); i++) {
				start = statements.get(i).getStart();
				Style bgStyle = sc.addStyle("ConstantWidth", null);
				if (Dna.sql.getActiveCoder() != null &&
						(statements.get(i).getCoderId() == Dna.sql.getActiveCoder().getId() || Dna.sql.getActiveCoder().isPermissionViewOthersStatements()) &&
						(statements.get(i).getCoderId() == Dna.sql.getActiveCoder().getId() || Dna.sql.getActiveCoder().getCoderRelations().get(statements.get(i).getCoderId()).isViewStatements())) {
					if (Dna.sql.getActiveCoder().isColorByCoder() == true) {
						StyleConstants.setBackground(bgStyle, statements.get(i).getCoderColor().toAWTColor());
					} else {
						StyleConstants.setBackground(bgStyle, statements.get(i).getStatementTypeColor().toAWTColor());
					}
				}
				doc.setCharacterAttributes(start, statements.get(i).getStop() - start, bgStyle, false);
				codedRanges.add(new int[]{start, statements.get(i).getStop()});
			}

			// paint predicted highlights (do not overlap coded statements)
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
				double certainty = ps.getConceptProb();
				int alpha = (int) (minAlpha + (maxAlpha - minAlpha) * Math.pow(certainty, curve));
				Color highlightColor = new Color(100, 149, 237, alpha); // yellow with variable opacity
                Style predStyle = sc.addStyle("PredictedHighlight", null);
                StyleConstants.setBackground(predStyle, highlightColor);
                int predLength = predStop - predStart;
                if (predStart >= 0 && predStart + predLength <= textWindow.getText().length()) {
                    doc.setCharacterAttributes(predStart, predLength, predStyle, false);
                }
            }

			// color regex
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
	
	/**
	 * Repaint statements and update font size after a new coder has been
	 * selected.
	 */
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
	class PredictedStatement {
		private int start, stop;
		private Map<String, String> predictedValues;
		private Double conceptProb; // Nullable: only present for concept, or set to null for others

		public PredictedStatement(int start, int stop, Map<String, String> predictedValues, Double conceptProb) {
			this.start = start;
			this.stop = stop;
			this.predictedValues = predictedValues;
			this.conceptProb = conceptProb;
		}
		public int getStart() { return start; }
		public int getStop() { return stop; }
		public Map<String, String> getPredictedValues() { return predictedValues; }
		public Double getConceptProb() { return conceptProb; }
	}
}

