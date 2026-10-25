package psaro.patch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import psaro.format.Smdh;

/**
 * The page a patch's players build a patched copy with ({@link TitleBuild}), the other way to
 * patch the game than Luma3DS's game patching, and the only way to patch its DLC: one HTML file,
 * opened in any browser, holding the patched files of the game, its DLC or both, which rebuilds the
 * player's own decrypted copy of whichever they pick (a CIA, or the game's .3ds) and hands back
 * the patched copy. It tells which title a copy is by its title id. The player's copy is read in
 * the page a piece at a time and never leaves their machine; the page holds nothing of the title
 * but the files the patch changes. Export patch as a patched CIA writes it as a download of its
 * own, the patcher zip; as LayeredFS, a DLC's page goes in the zip, in {@link #FOLDER}, as a DLC
 * has no other way to be patched.
 */
public final class PatcherPage {

	/** Where the page and its readme go in the zip. */
	public static final String FOLDER = "Patcher";
	public static final String PAGE = "Patcher.html";
	/** Stands for the DLC's title id in the patcher download's readme, {@link PatchExport#TITLE_ID} for the game's. */
	public static final String DLC_TITLE_ID = "{dlcTitleId}";
	private static final String TEMPLATE = "/patcher.html";
	private static final String SLOT = "{{PATCH}}";

	/** What a title is to the page: the game, or its DLC. */
	public enum Kind {
		GAME, DLC
	}

	/**
	 * A title the page patches: its kind, its title id, its version when known (or null), its
	 * patched files, by content ({@link TitleBuild#build}), and for a game the title the HOME Menu is
	 * to show, or null.
	 */
	public record Title(Kind kind, String titleId, Integer version, Map<String, Map<String, byte[]>> contents,
			Smdh.Title homeTitle) {

		public Title(Kind kind, String titleId, Integer version, Map<String, Map<String, byte[]>> contents) {
			this(kind, titleId, version, contents, null);
		}

		/** Whether the page has anything to do for it. */
		boolean patched() {
			return !contents.isEmpty() || hasHomeTitle();
		}

		boolean hasHomeTitle() {
			return homeTitle != null && !homeTitle.isBlank();
		}
	}

	private PatcherPage() {
	}

	/** The page for {@code titles}, headed {@code name}. */
	public static String html(String name, List<Title> titles) throws IOException {
		JSONObject patch = new JSONObject();
		patch.put("name", name);
		JSONArray list = new JSONArray();
		for (Title t : titles) {
			JSONObject title = new JSONObject();
			title.put("kind", t.kind().name().toLowerCase(java.util.Locale.ROOT));
			title.put("titleId", t.titleId());
			if (t.version() != null) {
				title.put("version", t.version());
			}
			JSONObject contents = new JSONObject();
			t.contents().forEach((content, byPath) -> {
				JSONObject f = new JSONObject();
				byPath.forEach((path, bytes) -> f.put(path, Base64.getEncoder().encodeToString(bytes)));
				contents.put(content, f);
			});
			title.put("contents", contents);
			if (t.hasHomeTitle()) {
				Smdh.Title home = t.homeTitle();
				JSONObject names = new JSONObject();
				for (String[] part : new String[][] {{"short", home.shortTitle()}, {"long", home.longTitle()},
						{"publisher", home.publisher()}}) {
					if (part[1] != null && !part[1].isBlank()) {
						names.put(part[0], part[1]);
					}
				}
				title.put("homeTitle", names);
			}
			list.put(title);
		}
		patch.put("titles", list);
		// in a script element, so nothing in it may close one: JSON writes "</" as "<\/"
		return template().replace(SLOT, patch.toString());
	}

	/** The page written, and the readme that goes with it. */
	public record Written(Path page, String readme) {
	}

	/**
	 * Writes the page for {@code titles} (those with nothing patched left out) into {@code into},
	 * returning it with its readme; null when no title has anything patched.
	 */
	public static Written write(Path into, String name, List<Title> titles) throws IOException {
		List<Title> patched = titles.stream().filter(Title::patched).toList();
		if (patched.isEmpty()) {
			return null;
		}
		Files.createDirectories(into);
		Path page = into.resolve(PAGE);
		Files.writeString(page, html(name, patched), StandardCharsets.UTF_8);
		return new Written(page, readme(patched));
	}

	/**
	 * {@code written} as files to add to another zip, in {@link #FOLDER}: the page, and its readme,
	 * written beside it.
	 */
	public static List<PatchExport.Extra> inFolder(Written written) throws IOException {
		return inFolder(written, written.readme(), "");
	}

	/**
	 * As {@link #inFolder(Written)}, with {@code text} for the readme in place of the page's own,
	 * {@link PatchExport#TITLE_ID} in it becoming {@code titleId}.
	 */
	public static List<PatchExport.Extra> inFolder(Written written, String text, String titleId) throws IOException {
		Path readme = written.page().resolveSibling("README.txt");
		Files.writeString(readme, PatchExport.fill(text, titleId), StandardCharsets.UTF_8);
		return List.of(new PatchExport.Extra(written.page(), FOLDER + "/" + PAGE),
				new PatchExport.Extra(readme, FOLDER + "/README.txt"));
	}

	/**
	 * The readme of the DLC's page in a LayeredFS zip until a project writes its own
	 * ({@link ExportSettings#patcherReadme}), {@link PatchExport#TITLE_ID} standing for the DLC's title id.
	 */
	public static String defaultDlcReadme() {
		return readme(List.of(new Title(Kind.DLC, PatchExport.TITLE_ID, null, Map.of())));
	}

	/**
	 * The readme of the patcher download (Export patch as a patched CIA) as the page would write it
	 * for the game, with {@code dlc} its DLC too, and with {@code homeTitle} a HOME Menu title:
	 * {@link PatchExport#TITLE_ID} standing for the game's title id, {@link #DLC_TITLE_ID} the DLC's.
	 */
	public static String defaultDownloadReadme(boolean dlc, boolean homeTitle) {
		List<Title> titles = new ArrayList<>();
		// any title: the readme says only that there is one
		titles.add(new Title(Kind.GAME, PatchExport.TITLE_ID, null, Map.of(),
				homeTitle ? new Smdh.Title("title", "", "") : null));
		if (dlc) {
			titles.add(new Title(Kind.DLC, DLC_TITLE_ID, null, Map.of()));
		}
		return readme(titles);
	}

	/** How a player builds their patched copies with the page. */
	static String readme(List<Title> titles) {
		boolean game = titles.stream().anyMatch(t -> t.kind() == Kind.GAME);
		boolean dlc = titles.stream().anyMatch(t -> t.kind() == Kind.DLC);
		List<String> lines = new ArrayList<>();
		lines.add("\"" + PAGE + "\" builds a patched copy of " + (game && dlc ? "the game or its DLC" : game ? "the game" : "the DLC")
				+ " from your own decrypted copy, to install or play in place of the original. Open it in a web browser,");
		lines.add("choose your copy, and build. It all happens in the page: nothing is uploaded.");
		lines.add("");
		if (game) {
			titles.stream().filter(t -> t.kind() == Kind.GAME).findFirst()
					.ifPresent(t -> lines.add("The game (title id " + t.titleId() + ")"));
			lines.add("  This is instead of the Luma3DS patch (the download with the luma folder): use one or the other. A");
			lines.add("  patched game needs no Luma3DS game patching, so if you used that patch before, delete");
			lines.add("  luma/titles/<title id>/romfs from your SD card.");
			lines.add("  - A decrypted CIA of the game (GodMode9 can build one from the game installed on a 3DS) gives a CIA:");
			lines.add("    install it with FBI on a 3DS with Luma3DS, or in Citra (or Azahar) with File > Install CIA.");
			lines.add("  - A decrypted .3ds of the game card (GodMode9 can dump one) gives a .3ds: play it in Citra, or have");
			lines.add("    GodMode9 build a CIA from it to install on a 3DS.");
			if (titles.stream().anyMatch(t -> t.kind() == Kind.GAME && t.hasHomeTitle())) {
				lines.add("  The patched copy also gives the game its English title on the HOME Menu.");
			}
			lines.add("");
		}
		if (dlc) {
			titles.stream().filter(t -> t.kind() == Kind.DLC).findFirst()
					.ifPresent(t -> lines.add("The DLC (title id " + t.titleId() + ")"));
			lines.add("  A DLC cannot be patched any other way: it is installed again with the patch in it. Start from a");
			lines.add("  decrypted CIA of it (GodMode9 can build one from the DLC installed on a 3DS), and install the patched");
			lines.add("  CIA with FBI, or in Citra with File > Install CIA. Its text is drawn with the game's patched fonts,");
			lines.add("  so patch the game too, either way.");
			lines.add("  If the DLC was installed before and the game still shows its old text, the game is showing its own");
			lines.add("  copy of that text, kept in its Ext Save Data. Back up the game's save data and Ext Save Data with");
			lines.add("  Checkpoint, then delete the game's Ext Save Data with FBI (Ext Save Data > the game > Delete). The");
			lines.add("  game makes it again from the patched DLC. Its save data is not touched; if the game kept anything");
			lines.add("  else in its Ext Save Data and you miss it, restore that with Checkpoint.");
			lines.add("");
		}
		lines.add("Keep your decrypted copies: installing one puts the original back.");
		lines.add("");
		return String.join("\n", lines);
	}

	private static String template() throws IOException {
		try (InputStream in = PatcherPage.class.getResourceAsStream(TEMPLATE)) {
			if (in == null) {
				throw new IOException("The patcher page is missing from PsaroEditor.");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
