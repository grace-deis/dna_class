package gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;

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
		this.add(textScrollPane);}

		/*// Annotation method dropdown
		JPanel topPanel = new JPanel();
		topPanel.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
		JLabel methodLabel = new JLabel("Annotation Method:");
		JComboBox<String> methodDropdown = new JComboBox<>(new String[] { "Select Method", "Naive Bayes", "To Do" });
		topPanel.add(methodLabel);
		topPanel.add(methodDropdown);


		// Annotate button
		JButton annotateButton = new JButton("Annotate");
		topPanel.add(annotateButton);
		annotateButton.addActionListener(e -> {
			try {
				// TO DO: Implement the annotation logic here
				JOptionPane.showMessageDialog(this, "Annotation logic not implemented yet.");
			} catch (Exception ex) {
				LogEvent logEvent = new LogEvent(Logger.ERROR, "Annotation failed", "An error occurred while annotating the text.", ex);
				Dna.logger.log(logEvent);
				JOptionPane.showMessageDialog(this, "An error occurred during annotation: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
			}
		});

		this.add(topPanel, BorderLayout.NORTH);
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

		public void setPredictedStatements(ArrayList<PredictedStatement> preds) {
        this.predictedStatements = preds;
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
			//remove all initial foreground color styles
			int initialStart = 0;
			int initialEnd = textWindow.getText().length();
			Style blackStyle = sc.addStyle("ConstantWidth", null);
			StyleConstants.setForeground(blackStyle, Color.black);
			StyleConstants.setBackground(blackStyle, Color.white);
			doc.setCharacterAttributes(initialStart, initialEnd - initialStart, blackStyle, false);

			// color statements
			ArrayList<Statement> statements = Dna.sql.getShallowStatements(documentId);
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
			}

			for (PredictedStatement ps : predictedStatements) {
				Style predStyle = sc.addStyle("PredictedHighlight", null);
				StyleConstants.setBackground(predStyle, new Color (255, 255, 0, 128)); // semi-transparent yellow
				int predStart = ps.getStart();
				int predLength = ps.getStop() - predStart;
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

	public void suggestHighlights(NaiveBayesClassifier.TrainedClassifier tc) {
		String text = textWindow.getText();
		ArrayList<PredictedStatement> preds = new ArrayList<>();
		// Split into sentences (simple split, can be improved)
		String[] sentences = text.split("(?<=[.!?])\\s+");
		int pos = 0;
		for (String sentence : sentences) {
			// Vectorize using classifier's vocab/df
			double[] x = NaiveBayesClassifier.vectorizeTfIdf(sentence, tc.vocab, tc.vocabDf, sentences.length);
			int predIdx = tc.model.predict(x);
			String predictedVar = NaiveBayesClassifier.inverseLabelMap(tc.labelMap, predIdx);
			int start = text.indexOf(sentence, pos);
			int stop = start + sentence.length();
			preds.add(new PredictedStatement(start, stop, predictedVar));
			pos = stop;
		}
		setPredictedStatements(preds);
		paintStatements();
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
}

class PredictedStatement {
    private int start, stop;
    private String predictedVariable;

    public PredictedStatement(int start, int stop, String var) {
        this.start = start;
        this.stop = stop;
        this.predictedVariable = var;
    }
    public int getStart() { return start; }
    public int getStop() { return stop; }
    public String getPredictedVariable() { return predictedVariable; }
}