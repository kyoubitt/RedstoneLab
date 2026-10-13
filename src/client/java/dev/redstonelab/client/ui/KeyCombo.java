package dev.redstonelab.client.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

/**
 * Keys pressed together, in order ("R,LEFT" = hold R, then press Left). The last key fires it.
 * Saved as key names joined with commas.
 */
public record KeyCombo(List<Integer> keys) {
	public static final KeyCombo NONE = new KeyCombo(List.of());

	private static final Map<String, Integer> BY_NAME = new LinkedHashMap<>();
	private static final Map<Integer, String> BY_KEY = new LinkedHashMap<>();

	private static void name(String name, int key) {
		BY_NAME.put(name, key);
		BY_KEY.putIfAbsent(key, name);
	}

	static {
		for (char c = 'A'; c <= 'Z'; c++) {
			name(String.valueOf(c), GLFW.GLFW_KEY_A + (c - 'A'));
		}
		for (char c = '0'; c <= '9'; c++) {
			name(String.valueOf(c), GLFW.GLFW_KEY_0 + (c - '0'));
		}
		for (int i = 1; i <= 12; i++) {
			name("F" + i, GLFW.GLFW_KEY_F1 + i - 1);
		}
		for (int i = 0; i <= 9; i++) {
			name("KP_" + i, GLFW.GLFW_KEY_KP_0 + i);
		}
		name("SPACE", GLFW.GLFW_KEY_SPACE);
		name("BACKSPACE", GLFW.GLFW_KEY_BACKSPACE);
		name("ENTER", GLFW.GLFW_KEY_ENTER);
		name("TAB", GLFW.GLFW_KEY_TAB);
		name("LEFT", GLFW.GLFW_KEY_LEFT);
		name("RIGHT", GLFW.GLFW_KEY_RIGHT);
		name("UP", GLFW.GLFW_KEY_UP);
		name("DOWN", GLFW.GLFW_KEY_DOWN);
		name("LSHIFT", GLFW.GLFW_KEY_LEFT_SHIFT);
		name("RSHIFT", GLFW.GLFW_KEY_RIGHT_SHIFT);
		name("LCTRL", GLFW.GLFW_KEY_LEFT_CONTROL);
		name("RCTRL", GLFW.GLFW_KEY_RIGHT_CONTROL);
		name("LALT", GLFW.GLFW_KEY_LEFT_ALT);
		name("RALT", GLFW.GLFW_KEY_RIGHT_ALT);
		name("INSERT", GLFW.GLFW_KEY_INSERT);
		name("DELETE", GLFW.GLFW_KEY_DELETE);
		name("HOME", GLFW.GLFW_KEY_HOME);
		name("END", GLFW.GLFW_KEY_END);
		name("PAGE_UP", GLFW.GLFW_KEY_PAGE_UP);
		name("PAGE_DOWN", GLFW.GLFW_KEY_PAGE_DOWN);
		name("MINUS", GLFW.GLFW_KEY_MINUS);
		name("EQUAL", GLFW.GLFW_KEY_EQUAL);
		name("COMMA", GLFW.GLFW_KEY_COMMA);
		name("PERIOD", GLFW.GLFW_KEY_PERIOD);
		name("SLASH", GLFW.GLFW_KEY_SLASH);
		name("SEMICOLON", GLFW.GLFW_KEY_SEMICOLON);
		name("APOSTROPHE", GLFW.GLFW_KEY_APOSTROPHE);
		name("LBRACKET", GLFW.GLFW_KEY_LEFT_BRACKET);
		name("RBRACKET", GLFW.GLFW_KEY_RIGHT_BRACKET);
		name("BACKSLASH", GLFW.GLFW_KEY_BACKSLASH);
		name("GRAVE", GLFW.GLFW_KEY_GRAVE_ACCENT);
		// MaLiLib-style names, so old settings files still load.
		BY_NAME.put("LEFT_SHIFT", GLFW.GLFW_KEY_LEFT_SHIFT);
		BY_NAME.put("RIGHT_SHIFT", GLFW.GLFW_KEY_RIGHT_SHIFT);
		BY_NAME.put("LEFT_CONTROL", GLFW.GLFW_KEY_LEFT_CONTROL);
		BY_NAME.put("RIGHT_CONTROL", GLFW.GLFW_KEY_RIGHT_CONTROL);
		BY_NAME.put("LEFT_ALT", GLFW.GLFW_KEY_LEFT_ALT);
		BY_NAME.put("RIGHT_ALT", GLFW.GLFW_KEY_RIGHT_ALT);
	}

	/** "R,LEFT" -> keys; unknown names are skipped. */
	public static KeyCombo parse(String text) {
		List<Integer> keys = new ArrayList<>();
		for (String part : text.split(",")) {
			Integer key = BY_NAME.get(part.trim().toUpperCase(Locale.ROOT));
			if (key != null && !keys.contains(key)) {
				keys.add(key);
			}
		}
		return keys.isEmpty() ? NONE : new KeyCombo(List.copyOf(keys));
	}

	public static boolean known(int key) {
		return BY_KEY.containsKey(key);
	}

	/** "R,LEFT", as saved. */
	public String save() {
		List<String> names = new ArrayList<>();
		for (int key : this.keys) {
			names.add(BY_KEY.getOrDefault(key, "?"));
		}
		return String.join(",", names);
	}

	/** "R + Left", for the settings screen. */
	public String display() {
		if (this.keys.isEmpty()) {
			return "Unbound";
		}
		List<String> names = new ArrayList<>();
		for (int key : this.keys) {
			String n = BY_KEY.getOrDefault(key, "?");
			names.add(n.length() <= 1 ? n : n.charAt(0) + n.substring(1).toLowerCase(Locale.ROOT).replace('_', ' '));
		}
		return String.join(" + ", names);
	}
}
