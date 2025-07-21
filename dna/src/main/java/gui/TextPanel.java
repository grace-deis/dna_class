package gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;

import org.ojalgo.optimisation.Variable;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import dna.Dna;
import logger.LogEvent;
import logger.Logger;
import model.Regex;
import model.Statement;

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

	private JComboBox<String> variableDropdown;
    private JTable annotationTable;
	
	/**
	 * Create a new text panel.
	 */
	TextPanel() {
		this.setLayout(new BorderLayout());
		sc = new StyleContext();
		doc = new DefaultStyledDocument(sc);
		textWindow = new JTextPane(doc);

		// Top panel with the dropdowns and button
		JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

		// Annotation method dropdown
		JLabel methodLabel = new JLabel("Annotation Method:");
		JComboBox<String> methodDropdown = new JComboBox<>(new String[] { "Naive Bayes", "To Do" });
		topPanel.add(methodLabel);
		topPanel.add(methodDropdown);

		 // Variable dropdown, initially disabled until documents are uploaded
        JLabel variableLabel = new JLabel("Variable:");
		variableDropdown = new JComboBox<>();
		variableDropdown.setEnabled(false);
		topPanel.add(variableLabel);
		topPanel.add(variableDropdown);

		variableDropdown.addActionListener(e -> {
			String selectedVar = (String) variableDropdown.getSelectedItem();
			if (selectedVar != null) {
				loadAnnotations(selectedVar, annotationTable);
			}
		});

		// Annotate button
		JButton annotateButton = new JButton("Annotate");
		topPanel.add(annotateButton);
		annotateButton.addActionListener(e -> {
			loadVariables();       // Reload variables from the database
			openAnnotationWindow(); // Then open the annotation window
		});

		this.add(topPanel, BorderLayout.NORTH);
		// ===== END OF TOP PANEL =====

		// Text window setup
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
		this.add(textScrollPane, BorderLayout.CENTER);

		this.documentId = -1; // initialize

		loadVariables();
	}

	private void loadVariables() {
		new SwingWorker<List<String>, Void>() {
			@Override
			protected List<String> doInBackground() {
				List<String> variableList = new ArrayList<>();
				String sql = "SELECT DISTINCT Variable FROM VARIABLES";

				try (Connection conn = Dna.sql.getDataSource().getConnection();
					PreparedStatement stmt = conn.prepareStatement(sql);
					ResultSet rs = stmt.executeQuery()) {

					while (rs.next()) {
						String variable = rs.getString("Variable");
						if (variable != null && !variable.isEmpty()) {
							variableList.add(variable);
						}
					}
				} catch (SQLException e) {
					LogEvent le = new LogEvent(Logger.WARNING,
							"[GUI] Could not load variables from database.",
							"Tried to access VARIABLES table but query failed.",
							e);
					Dna.logger.log(le);
				}
				return variableList;
			}

			@Override
			protected void done() {
				try {
					List<String> variables = get();
					// Use the method to update dropdown
					updateVariableDropdown(variables);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}.execute();
	}



	public void openAnnotationWindow() {
        JFrame frame = new JFrame("Annotations Viewer");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(800, 600);

        annotationTable = new JTable(); // save to field so accessible elsewhere
        JScrollPane scrollPane = new JScrollPane(annotationTable);
        frame.add(scrollPane);
        frame.setVisible(true);

		new SwingWorker<List<String[]>, Void>() {
			String[] columnNames;

			@Override
			protected List<String[]> doInBackground() {
				List<String[]> results = new ArrayList<>();
				String subString = "SUBSTRING(DOCUMENTS.Text, Start + 1, Stop - Start) AS Text";
				if (Dna.sql.getConnectionProfile().getType().equals("postgresql")) {
					subString = "SUBSTRING(DOCUMENTS.Text, CAST(Start + 1 AS INT4), CAST(Stop - Start AS INT4)) AS Text";
				}

				String sql = "SELECT DISTINCT STATEMENTS.ID, " + subString + ", ENTITIES.Value "
						+ "FROM STATEMENTS "
						+ "INNER JOIN DOCUMENTS ON DOCUMENTS.ID = STATEMENTS.DocumentId "
						+ "INNER JOIN VARIABLES ON VARIABLES.StatementTypeId = STATEMENTS.StatementTypeId "
						+ "INNER JOIN DATASHORTTEXT ON DATASHORTTEXT.StatementId = STATEMENTS.ID "
						+ "INNER JOIN ENTITIES ON ENTITIES.ID = DATASHORTTEXT.Entity "
						+ "WHERE ENTITIES.VariableId = 3;";

				try (Connection conn = Dna.sql.getDataSource().getConnection();
						PreparedStatement s = conn.prepareStatement(sql);
						ResultSet rs = s.executeQuery()) {

					java.sql.ResultSetMetaData meta = rs.getMetaData();
					int columnCount = meta.getColumnCount();

					columnNames = new String[columnCount];
					for (int i = 1; i <= columnCount; i++) {
						columnNames[i - 1] = meta.getColumnName(i);
					}

					while (rs.next()) {
						String[] row = new String[columnCount];
						for (int i = 1; i <= columnCount; i++) {
							row[i - 1] = rs.getString(i);
						}
						results.add(row);
					}

				} catch (SQLException e) {
					LogEvent le = new LogEvent(Logger.WARNING,
							"[GUI] Could not retrieve annotations from database.",
							"Tried to access annotation-related columns, but query failed.",
							e);
					Dna.logger.log(le);
				}

				return results;
			}

			@Override
			protected void done() {
				try {
					List<String[]> data = get();
					String[][] tableData = data.toArray(new String[0][]);
					annotationTable.setModel(new javax.swing.table.DefaultTableModel(tableData, columnNames));
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}.execute();
	}

	public void updateVariableDropdown(List<String> newVariables) {
		variableDropdown.removeAllItems();

		for (String v : newVariables) {
			variableDropdown.addItem(v);
		}

		boolean hasVariables = !newVariables.isEmpty();
		variableDropdown.setEnabled(hasVariables);

		if (hasVariables) {
			variableDropdown.setSelectedIndex(0);
			// Load annotations for the first variable
			loadAnnotations(newVariables.get(0), annotationTable);
		}
	}

	private void loadAnnotations(String variableName, JTable table) {
        // Your annotation loading logic here, e.g. update the table model
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
}