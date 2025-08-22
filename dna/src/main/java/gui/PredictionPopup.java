package gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Map;

public class PredictionPopup extends JDialog {
    private boolean accepted = false;

    public PredictionPopup(Frame owner, Map<String, String> predictedValues) {
        super(owner, "Auto Annotation", true);
        setLayout(new BorderLayout(10,10));

        JPanel valuesPanel = new JPanel(new GridLayout(predictedValues.size(), 2, 5, 5));
        for (Map.Entry<String, String> entry : predictedValues.entrySet()) {
            valuesPanel.add(new JLabel(entry.getKey() + ":"));
            valuesPanel.add(new JLabel(entry.getValue()));
        }
        add(valuesPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton acceptButton = new JButton("Accept");
        JButton rejectButton = new JButton("Reject");
        buttonPanel.add(rejectButton);
        buttonPanel.add(acceptButton);
        add(buttonPanel, BorderLayout.SOUTH);

        acceptButton.addActionListener(e -> {
            accepted = true;
            dispose();
        });

        rejectButton.addActionListener(e -> {
            accepted = false;
            dispose();
        });

        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    public boolean wasAccepted() {
        return accepted;
    }
}