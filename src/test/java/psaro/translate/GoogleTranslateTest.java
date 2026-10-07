package psaro.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.json.JSONArray;
import org.junit.jupiter.api.Test;

class GoogleTranslateTest {

	/** A reply shaped like the website service's, for "今日は晴れです。\n明日は雨です。". */
	private static String reply(String... english) {
		JSONArray parts = new JSONArray();
		for (String e : english) {
			parts.put(new JSONArray().put(e).put(org.json.JSONObject.NULL).put(org.json.JSONObject.NULL)
					.put(org.json.JSONObject.NULL).put(org.json.JSONObject.NULL).put(org.json.JSONObject.NULL).put("原文").put(1));
		}
		JSONArray result = new JSONArray()
				.put(new JSONArray().put(org.json.JSONObject.NULL).put(org.json.JSONObject.NULL))
				.put(new JSONArray().put(new JSONArray().put(new JSONArray().put(org.json.JSONObject.NULL)
						.put(org.json.JSONObject.NULL).put(org.json.JSONObject.NULL).put(org.json.JSONObject.NULL)
						.put(org.json.JSONObject.NULL).put(parts))).put("en"));
		JSONArray chunk = new JSONArray().put(new JSONArray().put("wrb.fr").put("MkEWBc").put(result.toString())
				.put(org.json.JSONObject.NULL).put("generic")).put(new JSONArray().put("di").put(120));
		return ")]}'\n\n" + chunk.toString().length() + "\n" + chunk + "\n25\n[[\"e\",4,null,null,600]]\n";
	}

	@Test
	void joinsTheTranslatedSentences() throws IOException {
		assertEquals("It's sunny today.\nIt will rain tomorrow.",
				GoogleTranslate.parse(reply("It's sunny today.", "\nIt will rain tomorrow.")));
	}

	@Test
	void unexpectedAnswerIsReportedClearly() {
		IOException garbled = assertThrows(IOException.class, () -> GoogleTranslate.parse("<html>blocked</html>"));
		assertTrue(garbled.getMessage().contains("no translation"), garbled.getMessage());
		IOException changed = assertThrows(IOException.class,
				() -> GoogleTranslate.parse(")]}'\n\n9\n[[\"wrb.fr\",\"MkEWBc\",\"[]\"]]"));
		assertTrue(changed.getMessage().contains("not understood"), changed.getMessage());
	}

	@Test
	void lineBreaksInsideASentenceAreJoinedAndOthersKept() {
		assertEquals("今日は晴れて暖かいです。\n明日は雨です。",
				GoogleTranslate.prepare("今日は晴れて\n暖かいです。\n明日は雨です。"));
		assertEquals("スタッフクレジット", GoogleTranslate.prepare("スタッフ\nクレジット"));
	}

	@Test
	void colorCodesAreRemoved() {
		assertEquals("名前の変更", GoogleTranslate.prepare("\u0002\u0001名前の\u0002\u0003変更\u0002\u0001"));
	}

	@Test
	void requestCarriesTheTextAsTheRpcsArguments() {
		JSONArray call = new JSONArray(GoogleTranslate.request("こんにちは")).getJSONArray(0).getJSONArray(0);
		assertEquals("MkEWBc", call.getString(0));
		JSONArray args = new JSONArray(call.getString(1)).getJSONArray(0);
		assertEquals("こんにちは", args.getString(0));
		assertEquals("ja", args.getString(1));
		assertEquals("en", args.getString(2));
	}
}
