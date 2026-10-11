package psaro.menu;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;

/**
 * The notices the libraries PsaroEditor ships with ask to go along with it: FlatLaf's Apache
 * License, whose text is in {@code /licenses}, copied from FlatLaf's own jar. (The bundled
 * typefaces carry their licences beside them, in {@code /fonts}.)
 */
public final class ThirdPartyNotices extends JDialog {

	/** The notices, in the order shown. */
	static final List<String> FILES = List.of("/licenses/FlatLaf-LICENSE.txt");

	/** Every notice's text, one after another. */
	public static String text() {
		StringBuilder out = new StringBuilder();
		for (String file : FILES) {
			try (InputStream in = ThirdPartyNotices.class.getResourceAsStream(file)) {
				if (in == null) {
					throw new IllegalStateException(file + " is missing from the build");
				}
				if (!out.isEmpty()) {
					out.append("\n\n========================================================================\n\n");
				}
				out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		return out.toString();
	}

	public ThirdPartyNotices(Window owner) {
		super(owner, "Third-party notices", ModalityType.APPLICATION_MODAL);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		getRootPane().registerKeyboardAction(e -> dispose(),
				KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

		JTextArea notices = new JTextArea(text());
		notices.setEditable(false);
		notices.setFont(new Font(Font.MONOSPACED, Font.PLAIN, notices.getFont().getSize()));
		notices.setCaretPosition(0);
		JScrollPane scroll = new JScrollPane(notices);
		scroll.setPreferredSize(new Dimension(640, 460));

		JButton close = new JButton("Close");
		close.addActionListener(e -> dispose());
		getRootPane().setDefaultButton(close);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.add(close);

		JPanel content = new JPanel(new BorderLayout(0, 8));
		content.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
		content.add(scroll, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);
		setContentPane(content);
		pack();
		setLocationRelativeTo(owner);
	}
}
