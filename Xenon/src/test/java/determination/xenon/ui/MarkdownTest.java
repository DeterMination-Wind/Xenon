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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the Markdown-to-HTML converter used for release notes.
@NotNullByDefault
public final class MarkdownTest {

    /// Headings, emphasis and links convert to their HTML counterparts.
    @Test
    public void inlineAndHeadings() {
        String html = Markdown.toHtml("## Title\n\n**bold** and *italic* and `code` and [link](https://x.y)\n");
        assertTrue(html.contains("<h2>Title</h2>"));
        assertTrue(html.contains("<b>bold</b>"));
        assertTrue(html.contains("<i>italic</i>"));
        assertTrue(html.contains("<code>code</code>"));
        assertTrue(html.contains("<a href=\"https://x.y\">link</a>"));
    }

    /// GFM tables convert with header and body rows.
    @Test
    public void tablesConvert() {
        String markdown = """
                | Name | Value |
                |------|-------|
                | a    | 1     |
                | b    | 2     |
                """;
        String html = Markdown.toHtml(markdown);
        assertTrue(html.contains("<table>"));
        assertTrue(html.contains("<th>Name</th>"));
        assertTrue(html.contains("<th>Value</th>"));
        assertTrue(html.contains("<td>a</td>"));
        assertTrue(html.contains("<td>2</td>"));
        assertTrue(html.contains("</table>"));
    }

    /// Images convert before links so the shared syntax does not collide.
    @Test
    public void imagesConvert() {
        String html = Markdown.toHtml("![shot](https://example.com/a.png)\n");
        assertTrue(html.contains("<img src=\"https://example.com/a.png\" alt=\"shot\"/>"));
        assertFalse(html.contains("<a href=\"https://example.com/a.png\">"));
    }

    /// Nested list items keep their own list levels and task checkboxes.
    @Test
    public void nestedListsAndTasks() {
        String markdown = """
                - task done
                  - [x] fixed crash
                  - [ ] todo
                """;
        String html = Markdown.toHtml(markdown);
        assertTrue(html.contains("<ul>"));
        assertTrue(html.contains("<li>task done</li>"));
        assertTrue(html.contains("\u2611 fixed crash"));
        assertTrue(html.contains("\u2610 todo"));
        assertTrue(html.contains("</ul>"));
    }

    /// Fenced code blocks stay verbatim.
    @Test
    public void codeBlocksStayVerbatim() {
        String html = Markdown.toHtml("```java\nif (a < b) {}\n```\n");
        assertTrue(html.contains("<pre><code>"));
        assertTrue(html.contains("if (a &lt; b) {}"));
    }

    /// Raw HTML in release notes passes through for Jsoup to handle.
    @Test
    public void rawHtmlPassesThrough() {
        String html = Markdown.toHtml("<details><summary>More</summary>hidden</details>\n");
        assertTrue(html.contains("<details>"));
        assertTrue(html.contains("<summary>More</summary>"));
    }
}
