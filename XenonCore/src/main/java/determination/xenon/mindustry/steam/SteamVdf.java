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
package determination.xenon.mindustry.steam;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Minimal parser for Valve's text VDF format (`keyvalue`/`libraryfolders`/`loginusers`).
///
/// <p>The parser covers the subset Steam actually writes for its client
/// configuration files: quoted strings, nested `{ ... }` objects and the
/// `\\`/`\"` escapes. Keys may repeat — the last occurrence wins, mirroring
/// how Steam itself reads the files.</p>
///
/// <p>Instances are stateless; every call to [#parse(String)] returns a
/// fresh immutable tree.</p>
@NotNullByDefault
public final class SteamVdf {

    private SteamVdf() {
    }

    /// Parses `text` into an ordered VDF object.
    ///
    /// @param text the raw file content, possibly starting with a UTF-8 BOM
    /// @return the parsed root object, never `null`
    /// @throws IllegalArgumentException when the document is malformed
    ///         (unbalanced braces, missing value, ...)
    public static VdfObject parse(String text) {
        // Steam never writes a BOM, but editors that re-save these files may.
        String normalized = text.startsWith("\uFEFF") ? text.substring(1) : text;
        VdfCursor cursor = new VdfCursor(tokenize(normalized));
        Map<String, VdfValue> values = new LinkedHashMap<>();
        while (cursor.hasNext()) {
            String key = cursor.nextString();
            values.put(key, parseValue(cursor));
        }
        return new VdfObject(values);
    }

    private static VdfValue parseValue(VdfCursor cursor) {
        String token = cursor.next();
        if ("{".equals(token)) {
            return parseObject(cursor);
        }
        if ("}".equals(token)) {
            throw new IllegalArgumentException("Unexpected VDF object end");
        }
        return new VdfString(token);
    }

    private static VdfObject parseObject(VdfCursor cursor) {
        Map<String, VdfValue> values = new LinkedHashMap<>();
        while (cursor.hasNext()) {
            if ("}".equals(cursor.peek())) {
                cursor.next();
                return new VdfObject(values);
            }
            String key = cursor.nextString();
            values.put(key, parseValue(cursor));
        }
        throw new IllegalArgumentException("Unclosed VDF object");
    }

    private static @Unmodifiable List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '{' || c == '}') {
                tokens.add(Character.toString(c));
                i++;
                continue;
            }
            if (c == '"') {
                StringBuilder token = new StringBuilder();
                i++;
                while (i < text.length()) {
                    char ch = text.charAt(i++);
                    if (ch == '"') {
                        break;
                    }
                    if (ch == '\\' && i < text.length()
                            && (text.charAt(i) == '\\' || text.charAt(i) == '"')) {
                        token.append(text.charAt(i++));
                    } else {
                        token.append(ch);
                    }
                }
                tokens.add(token.toString());
                continue;
            }

            int start = i;
            while (i < text.length()) {
                c = text.charAt(i);
                if (Character.isWhitespace(c) || c == '{' || c == '}') {
                    break;
                }
                i++;
            }
            tokens.add(text.substring(start, i));
        }
        return List.copyOf(tokens);
    }

    /// A VDF value: either a nested object or a plain string.
    @NotNullByDefault
    public interface VdfValue {
    }

    /// A VDF string value.
    ///
    /// @param value the decoded (unescaped) text
    @NotNullByDefault
    public record VdfString(String value) implements VdfValue {
    }

    /// A VDF object: ordered key/value pairs with nested objects allowed.
    ///
    /// @param values the entries in file order; the map is made unmodifiable
    @NotNullByDefault
    public record VdfObject(@Unmodifiable Map<String, VdfValue> values) implements VdfValue {

        public VdfObject {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        /// Returns the string stored under `key`.
        ///
        /// @return the value, or `null` when the key is missing or holds an object
        public @Nullable String string(String key) {
            VdfValue value = values.get(key);
            return value instanceof VdfString string ? string.value() : null;
        }

        /// Returns the object stored under `key`.
        ///
        /// @return the nested object, or `null` when the key is missing or holds a string
        public @Nullable VdfObject object(String key) {
            VdfValue value = values.get(key);
            return value instanceof VdfObject object ? object : null;
        }
    }

    /// Token stream cursor used by the recursive descent parser.
    private static final class VdfCursor {
        private final List<String> tokens;
        private int index;

        private VdfCursor(List<String> tokens) {
            this.tokens = tokens;
        }

        private boolean hasNext() {
            return index < tokens.size();
        }

        private String peek() {
            if (!hasNext()) {
                throw new IllegalArgumentException("Unexpected VDF end");
            }
            return tokens.get(index);
        }

        private String next() {
            String token = peek();
            index++;
            return token;
        }

        private String nextString() {
            String token = next();
            if ("{".equals(token) || "}".equals(token)) {
                throw new IllegalArgumentException("Expected VDF string");
            }
            return token;
        }
    }
}
