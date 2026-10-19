/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package org.apache.roller.it;

import java.time.Duration;

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import org.apache.roller.it.support.BrowserHealth;
import org.apache.roller.it.support.Editor;
import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.CollectionCondition.size;
import static com.codeborne.selenide.CollectionCondition.sizeGreaterThan;
import static com.codeborne.selenide.Condition.attribute;
import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static com.codeborne.selenide.Selenide.switchTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A technical post as a reader sees it, in a real browser: a GitHub alert, a
 * fenced {@code java} block, an untagged block, inline code and an
 * {@code ## h2}, published on a portfolio weblog (the dark theme whose
 * illegible pale-blue callouts started this) and previewed in the editor on
 * the journal fixture weblog.
 *
 * <p>{@code ContentAssetsRenderingTest} proves every head ships the styles
 * and the lazy bootstrap; {@code content.test.mjs} pins the bundle's pure
 * rules. Neither can see the bundle actually load under the theme CSP (on
 * both context paths), highlight.js actually colour a keyword, the gutter and
 * toolbar actually appear, or the callout's colours actually resolve to text
 * a reader can read against the tint behind it. {@link BrowserHealth} fails
 * the test if the bundle 404s, is refused, or throws.
 *
 * <p>Holds {@code GLOBAL_CONFIG} read because it creates a weblog, which the
 * weblog-creation and group-blogging switches {@code GlobalConfigMatrixIT}
 * flips would refuse.
 */
class MarkdownContentIT extends RollerIT {

    /** The code the copy button must copy: the block's text, no gutter, no trailing newline. */
    private static final String JAVA = "public class Tier {\n    int size = 1;\n}";

    private static final String BODY = "## Storage tier\n\n"
            + "> [!NOTE]\n> Mind the gap between the tiers.\n\n"
            + "```java\n" + JAVA + "\n```\n\n"
            + "```\nplain block\nsecond line\n```\n\n"
            + "Run `mvn verify` before you push.\n";

    private static final String JAVA_BLOCK = ".roller-code[data-language='java']";

    /** The untagged block: enhanced like the other, with no language to name. */
    private static final String PLAIN_BLOCK = ".roller-code:not([data-language])";

    /** The live preview is debounced, then round-trips to the server. */
    private static final Duration RENDER = Duration.ofSeconds(10);

    @Test
    @ResourceLock(value = RollerIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
    void aPublishedTechnicalPostGetsCalloutsHighlightingCopyAndAnchors() {
        String suffix = Long.toString(System.nanoTime(), 36);
        loginAsAdmin();
        String handle = createWeblog("mdcontent" + suffix, "portfolio");
        String permalink = publish(handle, "IT Markdown " + suffix);

        Selenide.open(permalink);
        BrowserHealth.current().settle();
        String content = ".pf-entry-content";

        // --- the callout, legible on the dark canvas -------------------------
        SelenideElement callout = $(content + " .markdown-alert-note").shouldBe(visible);
        callout.$(".markdown-alert-title").shouldBe(visible).shouldHave(text("Note"));
        callout.$("p:not(.markdown-alert-title)").shouldHave(text("Mind the gap"));
        assertLegible(callout.$("p:not(.markdown-alert-title)"), "the callout's body text");
        assertLegible(callout.$(".markdown-alert-title"), "the callout's title");

        // --- the java block: toolbar, highlighting, gutter -------------------
        SelenideElement java = $(content + " " + JAVA_BLOCK).should(exist);
        java.$(".roller-code-lang").shouldHave(exactText("java"));
        java.$("button.roller-code-copy").shouldBe(visible);
        java.$$(".hljs-keyword").shouldHave(sizeGreaterThan(0));
        java.$(".roller-code-lines").shouldHave(attribute("aria-hidden", "true"));
        assertEquals("1\n2\n3", java.$(".roller-code-lines").getAttribute("textContent"),
                "one number per line of the block");
        assertEquals("none", executeJavaScript(
                "return getComputedStyle(arguments[0]).userSelect;", java.$(".roller-code-lines")),
                "the gutter must not be selectable");

        // --- the untagged block: copy and numbers, no colour, no label --------
        SelenideElement plain = $(content + " " + PLAIN_BLOCK).should(exist);
        plain.$(".roller-code-lang").shouldNot(exist);
        plain.$("button.roller-code-copy").shouldBe(visible);
        plain.$(".roller-code-lines").should(exist);
        plain.$$("[class*='hljs']").shouldHave(size(0));

        // --- copy takes the code, never the gutter ---------------------------
        // The clipboard is stubbed: a headless browser may refuse the real one,
        // and what matters here is what the button hands it.
        executeJavaScript("window.__copied = null;"
                + "Object.defineProperty(navigator, 'clipboard', {configurable: true,"
                + " value: {writeText: function (t) { window.__copied = t; return Promise.resolve(); }}});");
        java.$("button.roller-code-copy").click();
        java.$("button.roller-code-copy").shouldHave(exactText("Copied"));
        assertEquals(JAVA, executeJavaScript("return window.__copied;"));

        // --- inline code is a chip -------------------------------------------
        SelenideElement chip = $(content + " p > code").shouldHave(exactText("mvn verify"));
        assertNotEquals("rgba(0, 0, 0, 0)",
                executeJavaScript("return getComputedStyle(arguments[0]).backgroundColor;", chip),
                "inline code must sit on a tint");

        // --- the heading anchor ----------------------------------------------
        SelenideElement anchor = $(content + " h2#storage-tier > a.roller-heading-anchor").should(exist);
        String href = anchor.getAttribute("href");
        assertNotNull(href);
        assertTrue(href.endsWith("#storage-tier"), "the anchor must link to its heading: " + href);
        anchor.hover();
        anchor.shouldBe(visible);
    }

    /**
     * The editor's live preview replaces the article's innerHTML after every
     * push, long after the page loaded; the macro's initialiser rides the
     * preview seam so the swapped-in blocks are enhanced too.
     */
    @Test
    void theEditorPreviewEnhancesTheSameContent() {
        loginAsAdmin();
        openEditorInWriteMode(WEBLOG_HANDLE);
        Editor.setText(BODY);

        $(Editor.MODE_PREVIEW).should(visible).click();
        switchTo().frame($(Editor.PREVIEW_FRAME).shouldBe(visible));
        try {
            String article = "#previewArticle.qj-prose";
            $(article + " .markdown-alert-note .markdown-alert-title").shouldHave(text("Note"), RENDER);
            $(article + " " + JAVA_BLOCK + " .roller-code-lang").shouldHave(exactText("java"), RENDER);
            $$(article + " " + JAVA_BLOCK + " .hljs-keyword").shouldHave(sizeGreaterThan(0));
            $(article + " " + PLAIN_BLOCK + " button.roller-code-copy").should(exist);
            $(article + " h2#storage-tier > a.roller-heading-anchor").should(exist);
        } finally {
            switchTo().defaultContent();
        }
        // Back to write mode: the mode persists in localStorage for the class.
        $(Editor.MODE_WRITE).click();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The element's text colour against what it actually sits on: its own and
     * its ancestors' backgrounds composited over the page canvas, the way the
     * browser paints them. Translucent tints are the point of the design, so
     * comparing the raw computed strings would prove nothing.
     */
    private static void assertLegible(SelenideElement element, String what) {
        Double ratio = executeJavaScript(
                "var el = arguments[0];"
                + "var canvas = document.createElement('canvas'); canvas.width = canvas.height = 1;"
                + "var ctx = canvas.getContext('2d', {willReadFrequently: true});"
                + "function rgba(c) { ctx.clearRect(0, 0, 1, 1); ctx.fillStyle = '#000'; ctx.fillStyle = c;"
                + "  ctx.fillRect(0, 0, 1, 1); var d = ctx.getImageData(0, 0, 1, 1).data;"
                + "  return [d[0], d[1], d[2], d[3] / 255]; }"
                + "var layers = []; for (var n = el; n && n.nodeType === 1; n = n.parentElement) {"
                + "  var bg = rgba(getComputedStyle(n).backgroundColor); if (bg[3] > 0) { layers.push(bg); } }"
                + "var under = rgba(getComputedStyle(document.documentElement).backgroundColor);"
                + "var base = under[3] > 0 ? under : [255, 255, 255, 1];"
                + "for (var i = layers.length - 1; i >= 0; i--) { var l = layers[i];"
                + "  base = [0, 1, 2].map(function (k) { return l[k] * l[3] + base[k] * (1 - l[3]); }); }"
                + "var fg = rgba(getComputedStyle(el).color);"
                + "function lum(c) { return [0, 1, 2].map(function (k) { var v = c[k] / 255;"
                + "  return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); })"
                + "  .reduce(function (s, v, k) { return s + v * [0.2126, 0.7152, 0.0722][k]; }, 0); }"
                + "var a = lum(fg), b = lum(base);"
                + "return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);",
                element);
        assertNotNull(ratio);
        assertTrue(ratio >= 4.5, what + " must read at 4.5:1 or better against what it sits on, got "
                + String.format("%.2f", ratio));
    }

    /** Creates a weblog owned by the seeded admin on the named theme. */
    private String createWeblog(String handle, String themeId) {
        openPath("/roller-ui/createWeblog.rol");
        $("#name").should(visible).setValue("Markdown Content " + handle);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue(themeId);
        clickAndAwaitNewPage($("button[type='submit']"));
        $("#messages").should(exist);
        return handle;
    }

    /** Publishes the fixture body on the weblog and returns the entry's permalink. */
    private String publish(String handle, String title) {
        openEditorInWriteMode(handle);
        $("input[name='bean.title']").setValue(title);
        Editor.setText(BODY);
        clickAndAwaitNewPage($("button[formaction$='entryAdd!publish.rol']"));
        String permalink = $("#entry_bean_permalink").should(exist).getAttribute("href");
        assertNotNull(permalink, "publishing must expose the entry's permalink");
        return permalink;
    }

    /**
     * The add-entry screen with the write pane showing. The mode control
     * persists in localStorage across the class, and in preview mode the
     * editor is hidden, where {@code Editor.setText} would wait forever.
     */
    private void openEditorInWriteMode(String handle) {
        openPath("/roller-ui/authoring/entryAdd.rol?weblog=" + handle);
        $("#entry").should(exist);
        $(Editor.ROOT).should(exist);
        $(Editor.MODE_WRITE).click();
        $(Editor.ROOT).shouldBe(visible);
    }
}
