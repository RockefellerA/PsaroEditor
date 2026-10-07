package psaro;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.FlatSystemProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

/** The look chosen in File → Preferences: FlatLaf's light or dark theme, or whichever the OS uses. */
public enum Theme {

	LIGHT("Light"),
	DARK("Dark"),
	SYSTEM("Match system setting");

	private static final Preferences PREFS = Preferences.userNodeForPackage(Theme.class);
	private static final String PREF_THEME = "theme";

	private final String label;

	Theme(String label) {
		this.label = label;
	}

	@Override
	public String toString() {
		return label;
	}

	/** The saved choice; {@link #SYSTEM} until the user picks one. */
	public static Theme saved() {
		try {
			return valueOf(PREFS.get(PREF_THEME, SYSTEM.name()));
		} catch (IllegalArgumentException e) {
			return SYSTEM;
		}
	}

	public void save() {
		PREFS.put(PREF_THEME, name());
	}

	public boolean isDark() {
		return switch (this) {
			case LIGHT -> false;
			case DARK -> true;
			case SYSTEM -> systemIsDark();
		};
	}

	/** Installs this theme and restyles every open window. */
	public void apply() {
		// FlatLaf draws its own title bar on Windows; keep the menus below it, not inside it
		System.setProperty(FlatSystemProperties.MENUBAR_EMBEDDED, "false");
		FlatLaf.setup(isDark() ? new FlatDarkLaf() : new FlatLightLaf());
		FlatLaf.updateUI();
	}

	/**
	 * Whether the OS is set to dark mode: Windows' "app mode", macOS' appearance, or GNOME's
	 * colour scheme. Anything that cannot be read counts as light.
	 */
	static boolean systemIsDark() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) {
			// "AppsUseLightTheme    REG_DWORD    0x0" when apps are set to dark
			String out = run("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
					"/v", "AppsUseLightTheme");
			return out.matches("(?s).*AppsUseLightTheme\\s+REG_DWORD\\s+0x0+\\s.*");
		}
		if (os.contains("mac")) {
			// prints "Dark" in dark mode; in light mode the key does not exist
			return run("defaults", "read", "-g", "AppleInterfaceStyle").trim().equalsIgnoreCase("dark");
		}
		String scheme = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
		if (!scheme.isBlank()) {
			return scheme.contains("dark");
		}
		return run("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme").toLowerCase(Locale.ROOT)
				.contains("dark");
	}

	/** The command's output, or "" if it cannot be run, fails or takes too long. */
	private static String run(String... command) {
		try {
			Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
			p.getOutputStream().close();
			if (!p.waitFor(3, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return "";
			}
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			return p.exitValue() == 0 ? out : "";
		} catch (IOException e) {
			return "";
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "";
		}
	}
}
