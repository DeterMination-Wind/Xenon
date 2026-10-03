/*
 * Xenon Launcher
 * Copyright (C) 2021-2026  Xenon contributors
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
package determination.xenon.ui;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Minimal Markdown-to-HTML converter for release notes and changelogs.
///
/// Supports headings, nested lists (plus task-list checkboxes), fenced code
/// blocks, blockquotes, horizontal rules, GFM tables, links, images and the
/// usual inline emphasis. Raw HTML is passed through so GitHub release notes
/// keep their `<details>`/`<img>` blocks.
///
/// The output is fed to {@link HTMLRenderer}, which understands the subset
/// of HTML this converter emits.
@NotNullByDefault
public final class Markdown {

    /// `### heading`
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    /// `- item`, `* item`, `1. item` with optional leading indentation.
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)([-*+]|\\d+\\.)\\s+(.*)$");

    /// `---`, `***`, `___`
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");

    /// GFM table separator: `|---|:--:|---|`
    private static final Pattern TABLE_SEPARATOR =
            Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");

    /// `> quote`
    private static final Pattern BLOCKQUOTE = Pattern.compile("^\\s*>\\s?(.*)$");

    private Markdown() {
    }

    /// Converts Markdown `text` into an HTML fragment.
    ///
    /// @param text Markdown source; never `null`
    /// @return an HTML fragment accepted by {@link HTMLRenderer}
    public static String toHtml(String text) {
        StringBuilder html = new StringBuilder();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        Deque<ListState> lists = new ArrayDeque<>();
        boolean inCode = false;
        boolean inTable = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Fenced code blocks keep their content verbatim.
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                closeLists(html, lists);
                if (inCode) {
                    html.append("</code></pre>\n");
                    inCode = false;
                } else {
                    html.append("<pre><code>");
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                html.append(escapeHtml(line)).append('\n');
                continue;
            }

            // GFM table: header row followed by a separator row.
            if (!inTable && line.indexOf('|') >= 0 && i + 1 < lines.length
                    && TABLE_SEPARATOR.matcher(lines[i + 1]).matches()
                    && lines[i + 1].indexOf('-') >= 0) {
                closeLists(html, lists);
                html.append("<table>");
                appendTableRow(html, line, "th");
                i++; // consume the separator line
                inTable = true;
                continue;
            }
            if (inTable) {
                if (line.indexOf('|') >= 0 && !line.isBlank()) {
                    appendTableRow(html, line, "td");
                    continue;
                }
                html.append("</table>\n");
                inTable = false;
            }

            Matcher heading = HEADING.matcher(trimmed);
            if (heading.matches()) {
                closeLists(html, lists);
                int level = heading.group(1).length();
                html.append("<h").append(level).append('>')
                        .append(inline(heading.group(2).trim()))
                        .append("</h").append(level).append(">\n");
                continue;
            }

            if (trimmed.isEmpty()) {
                closeLists(html, lists);
                html.append('\n');
                continue;
            }

            Matcher item = LIST_ITEM.matcher(line);
            if (item.matches()) {
                appendListItem(html, lists, item);
                continue;
            }

            if (HORIZONTAL_RULE.matcher(trimmed).matches()) {
                closeLists(html, lists);
                html.append("<hr/>\n");
                continue;
            }

            Matcher quote = BLOCKQUOTE.matcher(line);
            if (quote.matches()) {
                closeLists(html, lists);
                html.append("<blockquote><p>").append(inline(quote.group(1))).append("</p></blockquote>\n");
                continue;
            }

            closeLists(html, lists);
            html.append("<p>").append(inline(line)).append("</p>\n");
        }

        if (inTable) {
            html.append("</table>\n");
        }
        closeLists(html, lists);
        if (inCode) {
            html.append("</code></pre>\n");
        }
        return html.toString();
    }

    /// Appends one `<tr>` built from a `| a | b |` row.
    private static void appendTableRow(StringBuilder html, String line, String cellTag) {
        List<String> cells = splitTableRow(line);
        html.append("<tr>");
        for (String cell : cells) {
            html.append('<').append(cellTag).append('>')
                    .append(inline(cell))
                    .append("</").append(cellTag).append('>');
        }
        html.append("</tr>\n");
    }

    /// Splits a table row on `|`, dropping the optional outer pipes.
    private static List<String> splitTableRow(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    /// Maintains one list level while walking the document.
    private static final class ListState {
        private final boolean ordered;
        private final int indent;

        private ListState(boolean ordered, int indent) {
            this.ordered = ordered;
            this.indent = indent;
        }
    }

    /// Opens/closes list levels so `item` is placed at the right nesting depth.
    private static void appendListItem(StringBuilder html, Deque<ListState> lists, Matcher item) {
        int indent = item.group(1).replace("\t", "  ").length() / 2;
        boolean ordered = item.group(2).endsWith(".");
        String content = item.group(3);

        // Task-list checkboxes render as real symbols.
        if (content.startsWith("[ ] ")) {
            content = "\u2610 " + content.substring(4);
        } else if (content.length() >= 4 && content.substring(0, 3).equalsIgnoreCase("[x]")) {
            content = "\u2611 " + content.substring(4);
        }

        while (!lists.isEmpty() && indent < lists.peek().indent) {
            html.append(lists.pop().ordered ? "</ol>\n" : "</ul>\n");
        }
        if (lists.isEmpty() || indent > lists.peek().indent
                || lists.peek().ordered != ordered) {
            if (!lists.isEmpty() && lists.peek().ordered != ordered) {
                html.append(lists.pop().ordered ? "</ol>\n" : "</ul>\n");
            }
            lists.push(new ListState(ordered, indent));
            html.append(ordered ? "<ol>\n" : "<ul>\n");
        }
        html.append("<li>").append(inline(content)).append("</li>\n");
    }

    /// Closes every open list level.
    private static void closeLists(StringBuilder html, Deque<ListState> lists) {
        while (!lists.isEmpty()) {
            html.append(lists.pop().ordered ? "</ol>\n" : "</ul>\n");
        }
    }

    /// Converts inline Markdown (emphasis, code, links, images).
    private static String inline(String text) {
        String result = text;
        // Inline code first so its content is protected from the other rules.
        result = result.replaceAll("`([^`]+)`", "<code>$1</code>");
        // Images before links (their syntax overlaps).
        result = result.replaceAll("!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)",
                "<img src=\"$2\" alt=\"$1\"/>");
        result = result.replaceAll("\\[([^\\]]+)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)",
                "<a href=\"$2\">$1</a>");
        result = result.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        result = result.replaceAll("__(.+?)__", "<b>$1</b>");
        result = Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)")
                .matcher(result).replaceAll("<i>$1</i>");
        result = result.replaceAll("~~(.+?)~~", "<del>$1</del>");
        return result;
    }

    /// Escapes HTML metacharacters for verbatim contexts.
    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
