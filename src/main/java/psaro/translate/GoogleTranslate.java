package psaro.translate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import psaro.text.ControlCodes;

/**
 * A rough Japanese-to-English machine translation, as a reference for the human translator.
 *
 * <p>Asks the same service the translate.google.com page uses: its {@code batchexecute} RPC,
 * call {@code MkEWBc}. It is undocumented and not meant for apps, so Google may change it or
 * start refusing automated requests; failures come back as an {@link IOException} with a
 * message fit to show the user. A throttled request (HTTP 429) is retried twice before it is
 * reported. (The older {@code translate.googleapis.com} {@code client=gtx} endpoint throttles
 * far harder and translates worse.)
 *
 * <p>The game breaks lines in the middle of sentences, and Google treats a line break as the end
 * of one, so the text is sent with those breaks removed: a break is kept only after
 * sentence-ending punctuation. Japanese has no spaces between words, so nothing needs inserting.
 * Color codes are removed too. Results are kept for the session.
 */
public final class GoogleTranslate {

	private static final String ENDPOINT =
			"https://translate.google.com/_/TranslateWebserverUi/data/batchexecute?rpcids=MkEWBc&source-path=%2F&hl=en&rt=c";
	private static final String RPC = "MkEWBc";
	private static final String USER_AGENT = "Mozilla/5.0";
	private static final int TOO_MANY_REQUESTS = 429;
	private static final int MAX_ATTEMPTS = 3;
	private static final long RETRY_DELAY_MS = 1000;
	/** A line break after one of these ends a sentence, so it is kept. */
	private static final String SENTENCE_END = "。！？!?…」』)）";
	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

	private GoogleTranslate() {
	}

	/** A result already fetched this session for {@code japanese}, or null. */
	public static String cached(String japanese) {
		return CACHE.get(prepare(japanese));
	}

	/** Blocking: call from a background thread. */
	public static String translate(String japanese) throws IOException {
		String text = prepare(japanese);
		if (text.isBlank()) {
			return "";
		}
		String hit = CACHE.get(text);
		if (hit != null) {
			return hit;
		}
		byte[] form = ("f.req=" + URLEncoder.encode(request(text), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
		for (int attempt = 1; ; attempt++) {
			HttpURLConnection conn = (HttpURLConnection) URI.create(ENDPOINT).toURL().openConnection();
			conn.setRequestMethod("POST");
			conn.setDoOutput(true);
			conn.setInstanceFollowRedirects(false);
			conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
			conn.setRequestProperty("User-Agent", USER_AGENT);
			conn.setConnectTimeout(8000);
			conn.setReadTimeout(10000);
			int code;
			try {
				try (OutputStream out = conn.getOutputStream()) {
					out.write(form);
				}
				code = conn.getResponseCode();
			} catch (IOException e) {
				throw new IOException("Could not reach Google Translate: " + e.getMessage(), e);
			}
			if (code == TOO_MANY_REQUESTS && attempt < MAX_ATTEMPTS) {
				conn.disconnect();
				pause(RETRY_DELAY_MS * attempt);
				continue;
			}
			if (code == TOO_MANY_REQUESTS || code / 100 == 3) {
				// a redirect here is Google's "unusual traffic" check, which an app cannot pass
				throw new IOException("Google Translate is limiting requests from this computer. Try again in a "
						+ "while, or paste the text into translate.google.com in a browser.");
			}
			if (code != HttpURLConnection.HTTP_OK) {
				throw new IOException("Google Translate answered HTTP " + code + ". Its website's service may have "
						+ "changed in a way PsaroEditor does not understand yet.");
			}
			String body;
			try (InputStream in = conn.getInputStream()) {
				body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			String english = parse(body);
			CACHE.put(text, english);
			return english;
		}
	}

	/** The {@code f.req} for one translation: {@code [[["MkEWBc", "[[text,\"ja\",\"en\",1],[]]", null, "generic"]]]}. */
	static String request(String text) {
		String args = new JSONArray().put(new JSONArray().put(text).put("ja").put("en").put(1)).put(new JSONArray()).toString();
		return new JSONArray().put(new JSONArray().put(new JSONArray().put(RPC).put(args).put(JSONObject.NULL).put("generic")))
				.toString();
	}

	/**
	 * The translation in a reply. The reply opens with {@code )]}'}, then length-prefixed JSON
	 * chunks; the chunk holding {@code ["wrb.fr", "MkEWBc", "<json>", ...]} carries the result as
	 * a JSON string, whose {@code [1][0][0][5]} lists the sentences as {@code [English, ...]}.
	 */
	static String parse(String body) throws IOException {
		try {
			for (String line : body.split("\n")) {
				if (!line.startsWith("[")) {
					continue;
				}
				JSONArray chunk = new JSONArray(line);
				for (int i = 0; i < chunk.length(); i++) {
					JSONArray entry = chunk.optJSONArray(i);
					if (entry != null && "wrb.fr".equals(entry.optString(0)) && RPC.equals(entry.optString(1))) {
						return sentences(new JSONArray(entry.getString(2)));
					}
				}
			}
		} catch (JSONException e) {
			throw new IOException("Google Translate's answer was not understood; its website's service may have changed.", e);
		}
		throw new IOException("Google Translate's answer held no translation; its website's service may have changed.");
	}

	private static String sentences(JSONArray result) {
		JSONArray parts = result.getJSONArray(1).getJSONArray(0).getJSONArray(0).getJSONArray(5);
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < parts.length(); i++) {
			Object english = parts.getJSONArray(i).opt(0);
			if (english instanceof String s) {
				out.append(s);
			}
		}
		return out.toString().strip();
	}

	/**
	 * The text as it is sent: without color codes, and with line breaks removed except after
	 * sentence-ending punctuation.
	 */
	static String prepare(String raw) {
		StringBuilder out = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == ControlCodes.COLOUR) {
				i++;
			} else if (c == '\n') {
				int last = lastVisible(out);
				if (last >= 0 && SENTENCE_END.indexOf(out.charAt(last)) >= 0) {
					out.append('\n');
				}
			} else if (c >= 0x20) {
				out.append(c);
			}
		}
		return out.toString();
	}

	private static int lastVisible(StringBuilder s) {
		int i = s.length() - 1;
		while (i >= 0 && (s.charAt(i) == ' ' || s.charAt(i) == '　')) {
			i--;
		}
		return i;
	}

	private static void pause(long ms) throws IOException {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Translation was cancelled.", e);
		}
	}
}
