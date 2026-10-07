package psaro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import psaro.UpdateChecker.InstallerKind;
import psaro.UpdateChecker.ReleaseInfo;

class UpdateCheckerTest {

	/** Trimmed from a real GitHub releases/latest response for the release workflow's assets. */
	private static final String RELEASE = """
			{
			  "tag_name": "v0.2.0",
			  "assets": [
			    {"name": "PsaroEditor-0.2.0.msi",
			     "browser_download_url": "https://github.com/RockefellerA/PsaroEditor/releases/download/v0.2.0/PsaroEditor-0.2.0.msi"},
			    {"name": "PsaroEditor-1.2.0.dmg",
			     "browser_download_url": "https://github.com/RockefellerA/PsaroEditor/releases/download/v0.2.0/PsaroEditor-1.2.0.dmg"},
			    {"name": "psaroeditor_0.2.0_amd64.deb",
			     "browser_download_url": "https://github.com/RockefellerA/PsaroEditor/releases/download/v0.2.0/psaroeditor_0.2.0_amd64.deb"}
			  ]
			}
			""";

	@Test
	void picksTheInstallerForEachPlatform() {
		assertTrue(UpdateChecker.parse(RELEASE, "0.1.0", InstallerKind.MSI).installerUrl().endsWith(".msi"));
		assertTrue(UpdateChecker.parse(RELEASE, "0.1.0", InstallerKind.DMG).installerUrl().endsWith(".dmg"));
		assertTrue(UpdateChecker.parse(RELEASE, "0.1.0", InstallerKind.DEB).installerUrl().endsWith(".deb"));
		assertNull(UpdateChecker.parse(RELEASE, "0.1.0", null).installerUrl());
	}

	@Test
	void reportsAnUpdateOnlyWhenTheReleaseIsNewer() {
		ReleaseInfo older = UpdateChecker.parse(RELEASE, "0.1.0", InstallerKind.MSI);
		assertEquals("0.2.0", older.latestVersion());
		assertTrue(older.updateAvailable());
		assertFalse(UpdateChecker.parse(RELEASE, "0.2.0", InstallerKind.MSI).updateAvailable());
		assertFalse(UpdateChecker.parse(RELEASE, "0.10.0", InstallerKind.MSI).updateAvailable());
		assertFalse(UpdateChecker.parse(RELEASE, "dev", InstallerKind.MSI).updateAvailable());
	}

	@Test
	void releaseWithoutAssetsHasNoInstaller() {
		ReleaseInfo info = UpdateChecker.parse("{\"tag_name\": \"v1.0.0\"}", "0.9.0", InstallerKind.DEB);
		assertTrue(info.updateAvailable());
		assertNull(info.installerUrl());
	}

	@Test
	void comparesVersionsNumerically() {
		assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.9"));
		assertTrue(UpdateChecker.isNewer("1.0", "0.99.99"));
		assertTrue(UpdateChecker.isNewer("0.2.1", "0.2"));
		assertFalse(UpdateChecker.isNewer("0.2", "0.2.0"));
		assertFalse(UpdateChecker.isNewer("0.2.0", "0.2.1"));
	}
}
