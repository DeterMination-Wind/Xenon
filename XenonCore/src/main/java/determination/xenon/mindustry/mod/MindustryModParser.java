/*
 * Xenon Launcher
 * Copyright (C) 2026  Xenon contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package determination.xenon.mindustry.mod;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Parses a Mindustry mod / plugin archive and produces a
 * {@link MindustryLocalMod}. Looks for a descriptor entry at the archive
 * root in the priority order: {@code mod.hjson}, {@code mod.json},
 * {@code plugin.hjson}, {@code plugin.json}.
 *
 * <p>Mindustry uses Hjson, which Gson cannot read directly; this parser
 * normalises a small but practically useful subset of Hjson into JSON
 * before delegating to Gson. See {@link #hjsonToJson(String)}.</p>
 */
public final class MindustryModParser {
    private static final Gson GSON = new Gson();

    /** Descriptor names checked in priority order (hjson first). */
    private static final String[] CANDIDATES = {
            "mod.hjson", "mod.json", "plugin.hjson", "plugin.json"
    };

    private MindustryModParser() {}

    /**
     * Parse the archive at {@code file} and build a
     * {@link MindustryLocalMod} from its descriptor.
     *
     * @throws MindustryModParseException if no descriptor exists or the
     *     descriptor cannot be parsed as either Hjson or JSON.
     */
    public static MindustryLocalMod parse(Path file) throws IOException {
        String raw = null;
        String picked = null;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            for (String candidate : CANDIDATES) {
                ZipEntry entry = zip.getEntry(candidate);
                if (entry == null) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    picked = candidate;
                    break;
                }
            }
        }
        if (raw == null) {
            throw new MindustryModParseException(
                    "No mod.json / mod.hjson / plugin.json / plugin.hjson in " + file);
        }

        JsonObject obj;
        if (picked.endsWith(".hjson")) {
            obj = parseHjsonOrJson(raw, file);
        } else {
            try {
                obj = JsonParser.parseString(raw).getAsJsonObject();
            } catch (JsonSyntaxException strict) {
                // Some authors ship .json that's actually hjson.
                Logger.LOG.log(System.Logger.Level.DEBUG,
                        "Strict JSON parse failed for " + file + ", retrying as hjson", strict);
                obj = parseHjsonOrJson(raw, file);
            }
        }

        return build(file, obj);
    }

    private static JsonObject parseHjsonOrJson(String raw, Path file)
            throws MindustryModParseException {
        String normalised = hjsonToJson(raw);
        try {
            return JsonParser.parseString(normalised).getAsJsonObject();
        } catch (JsonSyntaxException hjson) {
            // Last resort: try the original text as strict JSON.
            try {
                return JsonParser.parseString(raw).getAsJsonObject();
            } catch (JsonSyntaxException strict) {
                throw new MindustryModParseException(
                        "Failed to parse mod descriptor in " + file, hjson);
            }
        }
    }

    private static MindustryLocalMod build(Path file, JsonObject obj) {
        String name = string(obj, "name");
        String displayName = string(obj, "displayName");
        String author = string(obj, "author");
        String version = string(obj, "version");
        String description = string(obj, "description");
        String main = string(obj, "main");
        int minGameVersion = 0;
        if (obj.has("minGameVersion") && !obj.get("minGameVersion").isJsonNull()) {
            JsonElement el = obj.get("minGameVersion");
            try {
                if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
                    minGameVersion = el.getAsInt();
                } else if (el.isJsonPrimitive()) {
                    // mod.json sometimes has "136" as a string.
                    String s = el.getAsString().trim();
                    int dot = s.indexOf('.');
                    if (dot >= 0) s = s.substring(0, dot);
                    minGameVersion = s.isEmpty() ? 0 : Integer.parseInt(s);
                }
            } catch (RuntimeException ignored) {
                // Keep default 0 for unparseable values.
            }
        }
        boolean java = false;
        if (obj.has("java") && obj.get("java").isJsonPrimitive()) {
            try {
                java = obj.get("java").getAsBoolean();
            } catch (RuntimeException ignored) {
                java = "true".equalsIgnoreCase(obj.get("java").getAsString());
            }
        }
        List<String> deps = new ArrayList<>();
        if (obj.has("dependencies") && obj.get("dependencies").isJsonArray()) {
            JsonArray arr = obj.getAsJsonArray("dependencies");
            for (JsonElement el : arr) {
                if (el != null && el.isJsonPrimitive()) deps.add(el.getAsString());
            }
        }
        return new MindustryLocalMod(file, name, displayName, author, version,
                description, main, minGameVersion, java, deps);
    }

    private static String string(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : null;
    }

    // ------------------------------------------------------------------
    // Tiny Hjson normaliser
    // ------------------------------------------------------------------

    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)(^|[\\s,{\\[])(//|#)[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern BARE_KEY = Pattern.compile("(?m)([\\{,]\\s*)([A-Za-z_][A-Za-z0-9_-]*)\\s*:");
    // Same, but at start of input (no leading { or ,).
    private static final Pattern BARE_KEY_HEAD = Pattern.compile("(?m)^\\s*([A-Za-z_][A-Za-z0-9_-]*)\\s*:");
    private static final Pattern TRAILING_COMMA = Pattern.compile(",\\s*([}\\]])");

    /**
     * Convert a small subset of Hjson into JSON. Handles {@code //} and
     * {@code #} line comments, {@code /* &#42;/} block comments, bare
     * (unquoted) keys, and trailing commas. Multi-line {@code '''...'''}
     * strings are not supported; presence triggers an exception so the
     * caller falls back to a strict JSON attempt.
     */
    static String hjsonToJson(String input) throws MindustryModParseException {
        if (input.contains("'''")) {
            throw new MindustryModParseException(
                    "Multi-line ''' hjson strings are not supported");
        }
        String s = input;

        // Strip block then line comments. Block first so // inside /* */ is gone.
        s = BLOCK_COMMENT.matcher(s).replaceAll("");
        s = LINE_COMMENT.matcher(s).replaceAll("$1");

        // Quote bare keys.
        s = quoteBareKeys(s);

        // Drop trailing commas before } or ].
        s = TRAILING_COMMA.matcher(s).replaceAll("$1");

        // Quote unquoted scalar values that aren't true/false/null/number.
        s = quoteBareValues(s);

        return s;
    }

    private static String quoteBareKeys(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        Matcher head = BARE_KEY_HEAD.matcher(s);
        if (head.find() && head.start() == indexOfFirstNonWs(s)) {
            out.append(s, 0, head.start(1));
            out.append('"').append(head.group(1)).append('"');
            out.append(s, head.end(1), head.end());
            s = out + s.substring(head.end());
            out.setLength(0);
        }
        Matcher m = BARE_KEY.matcher(s);
        int last = 0;
        while (m.find()) {
            out.append(s, last, m.start());
            out.append(m.group(1)).append('"').append(m.group(2)).append('"').append(':');
            last = m.end();
        }
        out.append(s, last, s.length());
        return out.toString();
    }

    private static int indexOfFirstNonWs(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    /**
     * Hjson allows {@code key: some bare string until end of line}. Wrap
     * those in double quotes. Skips already-quoted strings, numbers,
     * booleans, {@code null}, objects, and arrays.
     */
    private static String quoteBareValues(String s) {
        StringBuilder out = new StringBuilder(s.length() + 32);
        int i = 0;
        int len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            // Pass through strings verbatim.
            if (c == '"') {
                int end = i + 1;
                while (end < len) {
                    char e = s.charAt(end);
                    if (e == '\\' && end + 1 < len) { end += 2; continue; }
                    if (e == '"') { end++; break; }
                    end++;
                }
                out.append(s, i, Math.min(end, len));
                i = end;
                continue;
            }
            // Look for `"key" :` followed by a bare scalar.
            if (c == ':' && i > 0) {
                out.append(c);
                int j = i + 1;
                // skip spaces / tabs (not newlines)
                while (j < len && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) {
                    out.append(s.charAt(j));
                    j++;
                }
                if (j >= len) { i = j; continue; }
                char first = s.charAt(j);
                if (first == '"' || first == '{' || first == '[' || first == ','
                        || first == '}' || first == ']' || first == '\n' || first == '\r') {
                    i = j;
                    continue;
                }
                // Capture until end of line / comma / closing brace.
                int valStart = j;
                while (j < len) {
                    char v = s.charAt(j);
                    if (v == '\n' || v == '\r') break;
                    if (v == ',' || v == '}' || v == ']') break;
                    j++;
                }
                String token = s.substring(valStart, j).trim();
                if (token.isEmpty()) {
                    i = j;
                    continue;
                }
                if (looksLikeJsonScalar(token)) {
                    out.append(' ').append(token);
                } else {
                    out.append(' ').append('"')
                            .append(token.replace("\\", "\\\\").replace("\"", "\\\""))
                            .append('"');
                }
                i = j;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean looksLikeJsonScalar(String token) {
        if (token.equals("true") || token.equals("false") || token.equals("null")) return true;
        // signed/unsigned int or decimal
        return token.matches("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");
    }
}
