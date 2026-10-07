package psaro.menu;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JProgressBar;
import javax.swing.SwingWorker;
import psaro.UpdateChecker;

/** Help menu for the main window: the update checker and the About dialog. */
public final class HelpMenu extends JMenu {

	private static final String CHECK_TITLE = "Check for Updates";

	private final JFrame owner;

	public HelpMenu(JFrame owner) {
		super("Help");
		this.owner = owner;
		setMnemonic(KeyEvent.VK_H);

		JMenuItem checkUpdates = new JMenuItem("Check for Updates…");
		checkUpdates.setMnemonic(KeyEvent.VK_U);
		add(checkUpdates);
		checkUpdates.addActionListener(e -> {
			checkUpdates.setEnabled(false);
			new SwingWorker<UpdateChecker.ReleaseInfo, Void>() {
				@Override
				protected UpdateChecker.ReleaseInfo doInBackground() throws Exception {
					return UpdateChecker.checkLatest();
				}

				@Override
				protected void done() {
					checkUpdates.setEnabled(true);
					try {
						showUpdateResult(get());
					} catch (Exception ex) {
						Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
						JOptionPane.showMessageDialog(owner, "Could not check for updates:\n" + cause.getMessage(),
								CHECK_TITLE, JOptionPane.ERROR_MESSAGE);
					}
				}
			}.execute();
		});

		addSeparator();

		JMenuItem about = new JMenuItem("About PsaroEditor");
		about.setMnemonic(KeyEvent.VK_A);
		add(about);
		about.addActionListener(e -> new About(owner).setVisible(true));
	}

	private void showUpdateResult(UpdateChecker.ReleaseInfo info) {
		if (!info.anyReleased()) {
			JOptionPane.showMessageDialog(owner, "No releases of PsaroEditor have been published yet.",
					CHECK_TITLE, JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		if (!info.updateAvailable()) {
			String msg = UpdateChecker.isDevBuild(info.currentVersion())
					? "Running a development build. Latest release is v" + info.latestVersion() + "."
					: "You're up to date! (v" + info.currentVersion() + ")";
			JOptionPane.showMessageDialog(owner, msg, CHECK_TITLE, JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		String msg = String.format(
				"An update is available!\n\nCurrent version:  %s\nNew version:      %s\n\nDownload and install now?",
				info.currentVersion(), info.latestVersion());
		int choice = JOptionPane.showConfirmDialog(owner, msg, "Update Available", JOptionPane.YES_NO_OPTION,
				JOptionPane.INFORMATION_MESSAGE);
		if (choice != JOptionPane.YES_OPTION) {
			return;
		}

		// no installer for this platform in the release: let the user pick from the page
		if (info.installerUrl() == null || info.installerKind() == null) {
			openInBrowser(UpdateChecker.RELEASES_PAGE);
			return;
		}
		downloadUpdate(info.installerUrl(), info.installerKind());
	}

	private void downloadUpdate(String installerUrl, UpdateChecker.InstallerKind kind) {
		JDialog progress = new JDialog(owner, "Downloading Update…", true);
		JProgressBar bar = new JProgressBar(0, 100);
		bar.setIndeterminate(true);
		bar.setStringPainted(true);
		bar.setString("Downloading…");
		progress.add(bar, BorderLayout.CENTER);
		progress.setSize(320, 75);
		progress.setLocationRelativeTo(owner);
		progress.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

		SwingWorker<Void, Integer> worker = new SwingWorker<>() {
			@Override
			protected Void doInBackground() throws Exception {
				UpdateChecker.downloadAndInstall(installerUrl, kind, this::publish);
				return null;
			}

			@Override
			protected void process(List<Integer> chunks) {
				int pct = chunks.get(chunks.size() - 1);
				if (bar.isIndeterminate()) {
					bar.setIndeterminate(false);
				}
				bar.setValue(pct);
				bar.setString("Downloading… " + pct + "%");
			}

			@Override
			protected void done() {
				progress.dispose();
				try {
					get();
				} catch (Exception ex) {
					Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
					JOptionPane.showMessageDialog(owner, "Download failed:\n" + cause.getMessage(), "Update Error",
							JOptionPane.ERROR_MESSAGE);
				}
			}
		};

		worker.execute();
		progress.setVisible(true); // modal: blocks until dispose() or System.exit()
	}

	private void openInBrowser(String url) {
		if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
			JOptionPane.showMessageDialog(owner, "Download the update from:\n" + url, CHECK_TITLE,
					JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		try {
			Desktop.getDesktop().browse(URI.create(url));
		} catch (IOException ex) {
			JOptionPane.showMessageDialog(owner, "Could not open a browser. Download the update from:\n" + url,
					CHECK_TITLE, JOptionPane.ERROR_MESSAGE);
		}
	}
}
