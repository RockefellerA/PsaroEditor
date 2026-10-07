package psaro;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Asks GitHub for the latest PsaroEditor release, and downloads and starts its installer. */
public final class UpdateChecker {

	public static final String RELEASES_PAGE = "https://github.com/RockefellerA/PsaroEditor/releases/latest";

	private static final String RELEASES_API =
			"https://api.github.com/repos/RockefellerA/PsaroEditor/releases/latest";
	private static final String USER_AGENT = "PsaroEditor-App";
	private static final String DEV = "dev";

	private UpdateChecker() {
	}

	/** Installer kind picked from a release's assets, matched to the current OS. */
	public enum InstallerKind {
		MSI(".msi"), // Windows
		DMG(".dmg"), // macOS
		DEB(".deb"); // Linux (Debian/Ubuntu)

		final String extension;

		InstallerKind(String extension) {
			this.extension = extension;
		}
	}

	/**
	 * What GitHub reported. {@code latestVersion} is null when nothing has been released yet;
	 * {@code installerUrl} is null when the release has no installer for this platform.
	 */
	public record ReleaseInfo(String currentVersion, String latestVersion, String installerUrl,
			InstallerKind installerKind, boolean updateAvailable) {

		public boolean anyReleased() {
			return latestVersion != null;
		}
	}

	@FunctionalInterface
	public interface ProgressCallback {
		void onProgress(int percent);
	}

	/**
	 * The running version, from the jar manifest, or {@code "dev"} for a build that did not
	 * come from a release: run from the IDE or Maven, or a locally packaged -SNAPSHOT jar.
	 */
	public static String currentVersion() {
		String v = UpdateChecker.class.getPackage().getImplementationVersion();
		return v == null || v.endsWith("-SNAPSHOT") ? DEV : v;
	}

	public static boolean isDevBuild(String version) {
		return DEV.equals(version);
	}

	/** The installer kind for the current platform, or null if there is none. */
	static InstallerKind installerKindForCurrentPlatform() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) {
			return InstallerKind.MSI;
		}
		if (os.contains("mac") || os.contains("darwin")) {
			return InstallerKind.DMG;
		}
		if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
			return InstallerKind.DEB;
		}
		return null;
	}

	/** Blocking: call from a background thread. */
	public static ReleaseInfo checkLatest() throws IOException {
		String current = currentVersion();
		InstallerKind kind = installerKindForCurrentPlatform();

		HttpURLConnection conn = (HttpURLConnection) URI.create(RELEASES_API).toURL().openConnection();
		conn.setRequestProperty("Accept", "application/vnd.github+json");
		conn.setRequestProperty("User-Agent", USER_AGENT);
		conn.setConnectTimeout(8000);
		conn.setReadTimeout(10000);

		int code = conn.getResponseCode();
		// GitHub answers 404 for releases/latest until the first release is published
		if (code == HttpURLConnection.HTTP_NOT_FOUND) {
			return new ReleaseInfo(current, null, null, kind, false);
		}
		if (code != HttpURLConnection.HTTP_OK) {
			throw new IOException("GitHub answered HTTP " + code + " " + conn.getResponseMessage());
		}
		String body;
		try (InputStream in = conn.getInputStream()) {
			body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		return parse(body, current, kind);
	}

	/** Reads a GitHub {@code releases/latest} response. */
	static ReleaseInfo parse(String body, String current, InstallerKind kind) {
		JSONObject release = new JSONObject(body);
		String tag = release.getString("tag_name");
		String latest = tag.startsWith("v") ? tag.substring(1) : tag;

		String installerUrl = null;
		if (kind != null) {
			JSONArray assets = release.optJSONArray("assets", new JSONArray());
			for (int i = 0; i < assets.length(); i++) {
				JSONObject asset = assets.getJSONObject(i);
				if (asset.getString("name").toLowerCase(Locale.ROOT).endsWith(kind.extension)) {
					installerUrl = asset.getString("browser_download_url");
					break;
				}
			}
		}

		boolean newer = !isDevBuild(current) && isNewer(latest, current);
		return new ReleaseInfo(current, latest, installerUrl, kind, newer);
	}

	/**
	 * Downloads the installer for {@code kind} to a temp file, starts the platform's installer
	 * (or mounts the DMG), then exits the app. Blocking: call from a background thread.
	 */
	public static void downloadAndInstall(String installerUrl, InstallerKind kind, ProgressCallback progress)
			throws IOException {
		HttpURLConnection conn = (HttpURLConnection) URI.create(installerUrl).toURL().openConnection();
		conn.setRequestProperty("User-Agent", USER_AGENT);
		conn.setConnectTimeout(10000);
		conn.setReadTimeout(60000);

		long total = conn.getContentLengthLong();
		Path dest = Files.createTempFile("psaroeditor-update-", kind.extension);

		try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(dest)) {
			byte[] buf = new byte[8192];
			long downloaded = 0;
			int n;
			while ((n = in.read(buf)) != -1) {
				out.write(buf, 0, n);
				downloaded += n;
				if (total > 0) {
					progress.onProgress((int) (downloaded * 100 / total));
				}
			}
		}

		String path = dest.toAbsolutePath().toString();
		ProcessBuilder pb = switch (kind) {
			// Windows: run the MSI installer
			case MSI -> new ProcessBuilder("msiexec", "/i", path);
			// macOS: `open` mounts the DMG; the app quits so its bundle can be replaced
			case DMG -> new ProcessBuilder("open", path);
			// Linux: hand the .deb to the desktop's installer rather than needing sudo here
			case DEB -> new ProcessBuilder("xdg-open", path);
		};
		pb.start();
		System.exit(0);
	}

	static boolean isNewer(String candidate, String current) {
		int[] a = parseVersion(candidate);
		int[] b = parseVersion(current);
		for (int i = 0; i < Math.max(a.length, b.length); i++) {
			int ai = i < a.length ? a[i] : 0;
			int bi = i < b.length ? b[i] : 0;
			if (ai != bi) {
				return ai > bi;
			}
		}
		return false;
	}

	private static int[] parseVersion(String v) {
		String[] parts = v.split("\\.");
		int[] nums = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			try {
				nums[i] = Integer.parseInt(parts[i]);
			} catch (NumberFormatException e) {
				// a non-numeric part counts as 0
			}
		}
		return nums;
	}
}
