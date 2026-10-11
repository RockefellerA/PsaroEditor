package psaro.menu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;

class ThirdPartyNoticesTest {

	/** FlatLaf's Apache License goes along with the app, as its terms ask. */
	@Test
	void flatLafsLicenceIsShipped() {
		String text = ThirdPartyNotices.text();
		assertTrue(text.contains("FlatLaf"));
		assertTrue(text.contains("FormDev Software GmbH"));
		assertTrue(text.contains("Apache License"));
		assertTrue(text.contains("Version 2.0"));
		assertTrue(text.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"));
	}

	/**
	 * The notice keeps up with the FlatLaf the build uses: its licence text the one in FlatLaf's
	 * jar, and its copyright line the jar's. An upgrade that changes either fails here, rather than
	 * the notice going quietly out of date.
	 */
	@Test
	void theNoticeMatchesTheFlatLafInUse() throws Exception {
		Path jar = Path.of(com.formdev.flatlaf.FlatLaf.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		String text = ThirdPartyNotices.text();
		try (JarFile j = new JarFile(jar.toFile())) {
			String licence;
			try (InputStream in = j.getInputStream(j.getEntry("META-INF/LICENSE"))) {
				licence = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			assertTrue(normalise(text).contains(normalise(licence)), "the licence text is FlatLaf's own, from " + jar);
			String copyright = j.getManifest().getMainAttributes().getValue("Implementation-Copyright")
					.replace("All rights reserved.", "").trim();
			assertTrue(text.contains(copyright), "the notice names \"" + copyright + "\"");
		} catch (IOException e) {
			throw new AssertionError("cannot read FlatLaf's jar at " + jar, e);
		}
	}

	/** Line endings aside: git may check the notice out with Windows ones. */
	private static String normalise(String s) {
		return s.replace("\r\n", "\n");
	}
}
