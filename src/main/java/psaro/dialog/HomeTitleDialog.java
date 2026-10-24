package psaro.dialog;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Path;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import psaro.format.Smdh;

/**
 * File → HOME Menu title: the title the HOME Menu shows for the game, given in place of its own
 * in every language but Japanese ({@link psaro.project.HomeTitle}). A box left empty keeps the
 * game's, which it shows while empty; each counts its characters against the room the 3DS gives it.
 * Until the game's own title is known, its decrypted copy can be chosen here to read it from.
 */
public final class HomeTitleDialog extends JDialog {

	/** Reads the game's own title from its decrypted copy, saying why it cannot. */
	@FunctionalInterface
	public interface OwnTitle {
		Smdh.Title read(Path copy) throws IOException;
	}

	private final JTextField shortTitle = new JTextField(36);
	private final JTextField longTitle = new JTextField(36);
	private final JTextField publisher = new JTextField(36);
	private boolean saved;

	/**
	 * Edits {@code current} (null for none), {@code own} being the game's own title to show in the
	 * empty boxes, or null when it is not known, when {@code ownFrom} reads it from a copy chosen.
	 */
	public HomeTitleDialog(Frame owner, Smdh.Title own, Smdh.Title current, OwnTitle ownFrom) {
		super(owner, "HOME Menu title", true);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);

		JLabel about = new JLabel("<html><body style='width: 380px'>The title the HOME Menu shows for the game on a 3DS set to "
				+ "any language but Japanese, which keeps the game's own. Only a patched CIA or .3ds (Export patch as a patched "
				+ "CIA) carries it: the HOME Menu reads it from the game installed, which Luma3DS game patching does not reach. "
				+ "A box left empty keeps the game's own.</body></html>");

		JPanel form = new JPanel(new GridBagLayout());
		int row = 0;
		row = add(form, row, "Short title:", shortTitle, Smdh.SHORT_UNITS);
		row = add(form, row, "Long title:", longTitle, Smdh.LONG_UNITS);
		add(form, row, "Publisher:", publisher, Smdh.PUBLISHER_UNITS);
		if (current != null) {
			shortTitle.setText(current.shortTitle());
			longTitle.setText(current.longTitle());
			publisher.setText(current.publisher());
		}

		JPanel content = new JPanel(new BorderLayout(0, 12));
		content.setBorder(new EmptyBorder(12, 16, 8, 16));
		content.add(about, BorderLayout.NORTH);
		content.add(form, BorderLayout.CENTER);
		if (own != null) {
			showOwn(own);
		} else {
			JPanel unknown = new JPanel(new BorderLayout(8, 0));
			unknown.add(hint("<html>To show the game's own title in the boxes, choose its decrypted .3ds or CIA,<br>"
					+ "the one a patched copy is built from.</html>"), BorderLayout.CENTER);
			JButton choose = new JButton("Choose…");
			choose.addActionListener(e -> {
				Smdh.Title read = chooseOwn(ownFrom);
				if (read != null) {
					showOwn(read);
					content.remove(unknown);
					pack();
				}
			});
			unknown.add(choose, BorderLayout.EAST);
			content.add(unknown, BorderLayout.SOUTH);
		}

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton clear = new JButton("Use the game's");
		clear.setToolTipText("Empty every box, so the game keeps its own title");
		clear.addActionListener(e -> {
			shortTitle.setText("");
			longTitle.setText("");
			publisher.setText("");
		});
		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> dispose());
		JButton save = new JButton("Save");
		save.addActionListener(e -> save());
		buttons.add(clear);
		buttons.add(cancel);
		buttons.add(save);
		getRootPane().setDefaultButton(save);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(content, BorderLayout.CENTER);
		getContentPane().add(buttons, BorderLayout.SOUTH);
		getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
				JComponent.WHEN_IN_FOCUSED_WINDOW);

		pack();
		setLocationRelativeTo(owner);
	}

	/** Whether Save closed it. */
	public boolean saved() {
		return saved;
	}

	/** The title as saved; null when every box was left empty. */
	public Smdh.Title title() {
		Smdh.Title t = new Smdh.Title(shortTitle.getText().strip(), longTitle.getText().strip(), publisher.getText().strip());
		return t.isBlank() ? null : t;
	}

	private void save() {
		Smdh.Title t = title();
		String problem = t == null ? null : t.problem();
		if (problem != null) {
			JOptionPane.showMessageDialog(this, problem, getTitle(), JOptionPane.WARNING_MESSAGE);
			return;
		}
		saved = true;
		dispose();
	}

	/** Shows {@code own}, the game's own title, in the boxes while they are empty: what an empty box keeps. */
	private void showOwn(Smdh.Title own) {
		shortTitle.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, own.shortTitle());
		longTitle.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, own.longTitle());
		publisher.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, own.publisher());
	}

	/** Asks for the game's decrypted copy and reads its title with {@code ownFrom}; null when none was read. */
	private Smdh.Title chooseOwn(OwnTitle ownFrom) {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("The game's decrypted copy");
		chooser.setFileFilter(new FileNameExtensionFilter("Decrypted game (*.3ds, *.cci, *.cia)", "3ds", "cci", "cia"));
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
			return null;
		}
		try {
			return ownFrom.read(chooser.getSelectedFile().toPath());
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, e.getMessage(), getTitle(), JOptionPane.WARNING_MESSAGE);
			return null;
		}
	}

	/** Adds a labelled box at {@code row} with its count; the next row. */
	private static int add(JPanel form, int row, String label, JTextField field, int units) {
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 0, 0, 8);
		c.anchor = GridBagConstraints.LINE_START;
		c.gridy = row;
		form.add(new JLabel(label), c);
		c.gridx = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		form.add(field, c);
		c.gridx = 2;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		c.insets = new Insets(4, 0, 0, 0);
		JLabel count = hint("");
		form.add(count, c);
		Runnable recount = () -> {
			int n = field.getText().strip().length();
			count.setText(n + "/" + (units - 1));
			field.putClientProperty(FlatClientProperties.OUTLINE, n < units ? null : FlatClientProperties.OUTLINE_ERROR);
		};
		field.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				recount.run();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				recount.run();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				recount.run();
			}
		});
		recount.run();
		return row + 1;
	}

	private static JLabel hint(String text) {
		JLabel l = new JLabel(text);
		l.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
		return l;
	}
}
