package psaro.menu;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Image;
import java.awt.event.KeyEvent;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import psaro.AppIcon;
import psaro.UpdateChecker;

/** Help → About: the icon, version, author and copyright. */
public final class About extends JDialog {

	public About(Frame owner) {
		super(owner, "About PsaroEditor", true);
		setResizable(false);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		getRootPane().registerKeyboardAction(e -> dispose(),
				KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

		JLabel icon = new JLabel();
		Image image = AppIcon.scaled(96);
		if (image != null) {
			icon.setIcon(new ImageIcon(image));
		}
		icon.setHorizontalAlignment(SwingConstants.CENTER);

		String version = UpdateChecker.currentVersion();
		JLabel text = new JLabel("<html><div style='text-align: center;'>"
				+ "<span style='font-size: 1.4em;'><b>PsaroEditor</b></span><br/>"
				+ "Version: " + (UpdateChecker.isDevBuild(version) ? "development build" : version) + "<br/><br/>"
				+ "Author: Andrew Rockefeller © 2026<br/><br/>"
				+ "This is an unofficial fan tool — it is not affiliated with Nintendo<br/>"
				+ "or the publishers of the games it edits.</div></html>");
		text.setHorizontalAlignment(SwingConstants.CENTER);

		JButton ok = new JButton("OK");
		ok.addActionListener(e -> dispose());
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER));
		buttons.add(ok);
		getRootPane().setDefaultButton(ok);

		JPanel content = new JPanel(new BorderLayout(0, 12));
		content.setBorder(BorderFactory.createEmptyBorder(20, 28, 12, 28));
		content.add(icon, BorderLayout.NORTH);
		content.add(text, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);
		setContentPane(content);

		pack();
		setLocationRelativeTo(owner);
	}
}
