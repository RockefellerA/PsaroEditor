package psaro.dialog;

import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Frame;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import psaro.Theme;

/** File → Preferences. Each setting is saved and applied as soon as it changes. */
public final class PreferencesDialog extends JDialog {

	public PreferencesDialog(Frame owner) {
		super(owner, "Preferences", true);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setResizable(false);

		// ── Appearance ───────────────────────────────────────────────────────
		JPanel appearance = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
		appearance.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Appearance",
				TitledBorder.LEFT, TitledBorder.TOP));
		appearance.add(new JLabel("Theme:"));
		JComboBox<Theme> theme = new JComboBox<>(Theme.values());
		theme.setSelectedItem(Theme.saved());
		theme.addActionListener(e -> {
			Theme chosen = (Theme) theme.getSelectedItem();
			chosen.save();
			// Not inline: restyling replaces the combo box's UI, and the UI that fired this
			// event still has work to do once listeners return.
			EventQueue.invokeLater(() -> {
				chosen.apply();
				pack();
			});
		});
		appearance.add(theme);

		JPanel content = new JPanel(new BorderLayout());
		content.setBorder(new EmptyBorder(12, 16, 8, 16));
		content.add(appearance, BorderLayout.CENTER);

		// ── Buttons ──────────────────────────────────────────────────────────
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton close = new JButton("Close");
		close.addActionListener(e -> dispose());
		buttons.add(close);
		getRootPane().setDefaultButton(close);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(content, BorderLayout.CENTER);
		getContentPane().add(buttons, BorderLayout.SOUTH);

		getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
				JComponent.WHEN_IN_FOCUSED_WINDOW);

		pack();
		setLocationRelativeTo(owner);
	}
}
