package psaro.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import psaro.format.LumaDump;

/** File → Open Luma crash dump: the dump read out as text, to read or copy. */
public final class CrashDumpDialog extends JDialog {

	public CrashDumpDialog(Frame owner, String fileName, LumaDump dump) {
		super(owner, "Crash dump: " + fileName, false);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		getRootPane().registerKeyboardAction(e -> dispose(),
				KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

		JTextArea text = new JTextArea(dump.report());
		text.setEditable(false);
		text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, text.getFont().getSize()));
		text.setCaretPosition(0);
		JScrollPane scroll = new JScrollPane(text);
		scroll.setPreferredSize(new Dimension(720, 560));

		JButton copy = new JButton("Copy");
		copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(new StringSelection(text.getText()), null));
		JButton close = new JButton("Close");
		close.addActionListener(e -> dispose());
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
		buttons.add(copy);
		buttons.add(close);

		JPanel content = new JPanel(new BorderLayout());
		content.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 6));
		content.add(scroll, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);
		setContentPane(content);
		pack();
		setLocationRelativeTo(owner);
	}
}
