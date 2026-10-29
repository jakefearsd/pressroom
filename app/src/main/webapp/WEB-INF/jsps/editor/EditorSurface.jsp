<%--
  Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  The ASF licenses this file to You
  under the Apache License, Version 2.0 (the "License"); you may not
  use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.  For additional information regarding
  copyright in this work, please see the NOTICE file in the top level
  directory of this distribution.
--%>
<%--
  The writing surface, shared by the entry editor and the page editor:
  toolbar, mode control, panes, status line, writing guide and the media
  chooser. EditorScript.jsp is its other half and both screens include both.

  ONE editor, two screens. Everything an author does to a page is either
  identical to what they do to an entry or a defect, so the page editor got
  its own trimmed-down copy of this markup exactly once -- and immediately
  fell behind (no toolbar, no mode control, no preview, no guide, no word
  count). Nothing about a second copy fails loudly; it simply drifts.

  Four things the including page must set, all in REQUEST scope (a
  page-scoped <c:set> is invisible across jsp:include and resolves to the
  empty string here):

    editorFieldName   the posted textarea name: bean.text / bean.content
    editorFieldValue  its value, ALREADY fn:escapeXml'd by the caller
    editorPreviewUrl  where the live preview posts (entryEdit / pageEdit)
    editorIdField     the form field naming the row being previewed

  And three it may set: editorPrimaryActionKey (the guide's Ctrl+Enter
  label), editorSaveActionKey (its Ctrl+S label; default weblogEdit.save) and
  editorNotYetSaved (true when there is no stored row yet, so the status line
  opens on "Not saved" rather than "Saved").

  The value arrives escaped rather than being escaped here, deliberately:
  EditorJspEscapingTest scans every JSP for a raw bean.text / bean.content
  expression, so a screen that hands this include an unescaped value fails
  that ratchet at its own c:set. Escaping again here would show the author
  &lt; where they typed <. (That scan reads source, not code, which is why
  neither expression is spelled out here in full -- naming one would fail
  the ratchet from inside a comment about the ratchet.)
--%>
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<%-- The insert menu is generated from the shortcode registry (model attribute
     shortcodeCards), so adding a sixth shortcode means writing its handler and
     nothing here. Snippets ride in a data attribute rather than inline
     JavaScript: JSTL escapes them for the attribute, and the browser hands
     back the exact text through dataset. The preview endpoint and id field
     ride the same way, and for the same reason. --%>
<div class="editor-surface" id="editorSurface" data-mode="write"
     data-preview-url="<c:out value='${editorPreviewUrl}'/>"
     data-id-field="<c:out value='${editorIdField}'/>">
    <div class="editor-toolbar" id="editorToolbar" role="toolbar" aria-label="<spring:message code='editor.toolbar'/>">
        <button type="button" class="editor-tool" data-cmd="bold" title="<spring:message code='editor.bold'/> (Ctrl+B)" data-shortcut><span class="bi bi-type-bold" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.bold'/></span></button>
        <button type="button" class="editor-tool" data-cmd="italic" title="<spring:message code='editor.italic'/> (Ctrl+I)" data-shortcut><span class="bi bi-type-italic" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.italic'/></span></button>
        <button type="button" class="editor-tool" data-cmd="heading" title="<spring:message code='editor.heading'/>"><span class="bi bi-type-h2" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.heading'/></span></button>
        <span class="editor-tool-sep" aria-hidden="true"></span>
        <button type="button" class="editor-tool" data-cmd="quote" title="<spring:message code='editor.quote'/>"><span class="bi bi-blockquote-left" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.quote'/></span></button>
        <button type="button" class="editor-tool" data-cmd="ul" title="<spring:message code='editor.bulletList'/>"><span class="bi bi-list-ul" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.bulletList'/></span></button>
        <button type="button" class="editor-tool" data-cmd="ol" title="<spring:message code='editor.numberedList'/>"><span class="bi bi-list-ol" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.numberedList'/></span></button>
        <span class="editor-tool-sep" aria-hidden="true"></span>
        <button type="button" class="editor-tool" data-cmd="link" title="<spring:message code='editor.link'/> (Ctrl+K)" data-shortcut><span class="bi bi-link-45deg" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.link'/></span></button>
        <button type="button" class="editor-tool" data-cmd="code" title="<spring:message code='editor.code'/>"><span class="bi bi-code" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.code'/></span></button>
        <button type="button" class="editor-tool" data-cmd="table" title="<spring:message code='editor.table'/>"><span class="bi bi-table" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.table'/></span></button>
        <button type="button" class="editor-tool" data-cmd="image" title="<spring:message code='weblogEdit.insertMediaFile'/>"><span class="bi bi-image" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='weblogEdit.insertMediaFile'/></span></button>
        <div class="dropdown d-inline-block" id="shortcodeInsertMenu">
            <button class="editor-tool dropdown-toggle" type="button" id="shortcodeInsertButton"
                    data-bs-toggle="dropdown" aria-expanded="false"><spring:message code="weblogEdit.insertShortcode"/></button>
            <ul class="dropdown-menu" aria-labelledby="shortcodeInsertButton">
                <c:forEach items="${shortcodeCards}" var="card">
                    <li><button type="button" class="dropdown-item shortcode-card"
                                data-shortcode="<c:out value='${card.name}'/>"
                                data-snippet="<c:out value='${card.snippet}'/>"
                                data-chooser="${card.usesMediaChooser}"><spring:message code="${card.labelKey}"/></button></li>
                </c:forEach>
            </ul>
        </div>
        <div class="editor-mode" id="editorMode" role="radiogroup" aria-label="<spring:message code='editor.mode'/>">
            <button type="button" role="radio" aria-checked="true" data-mode="write"><spring:message code="editor.mode.write"/></button>
            <button type="button" role="radio" aria-checked="false" data-mode="split"><spring:message code="editor.mode.split"/></button>
            <button type="button" role="radio" aria-checked="false" data-mode="preview"><spring:message code="editor.mode.preview"/></button>
        </div>
        <button type="button" class="editor-tool editor-help" data-cmd="help" title="<spring:message code='editor.guide'/> (Ctrl+/)" data-shortcut><span class="bi bi-question-circle" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.guide'/></span></button>
    </div>
    <div class="editor-panes">
        <div class="editor-write">
            <%-- The aria-label is also the CodeMirror surface's name: editor.js
                 copies it onto the contenteditable that replaces this (hidden)
                 textarea, which is what assistive technology lands on. --%>
            <textarea name="${editorFieldName}" id="edit_content" rows="18"
                      aria-label="<spring:message code='editor.textLabel'/>">${editorFieldValue}</textarea>
        </div>
        <%-- The shell URL rides a data attribute rather than an inline JS
             string: JSTL escapes it for the attribute and dataset hands back
             the exact text, so a weblog handle carrying a quote (the allowed
             character set is deployer-configurable) cannot break out of a
             string literal. Same reasoning as the shortcode snippets above. --%>
        <div class="editor-preview-pane" id="editorPreviewPane" hidden aria-live="polite"
             data-shell-url="<c:out value='${previewShellURL}'/>"></div>
    </div>
    <%-- The two status spans replace what used to be an empty div that only
         ever grew error spans from pressroomStatusError. Rendering them here --
         rather than from pressroomUpdateStatus() on first run -- means the
         status line's CSS ("empty" hides it) never needs to distinguish
         "not yet initialised" from "genuinely nothing to say", and existing
         content's word count is correct on the very first paint rather than
         appearing only after the first keystroke. --%>
    <div class="editor-status" id="editorStatus"
         data-words-template="<spring:message code='editor.status.words'/>">
        <span id="editorStatusWords"></span>
        <span id="editorStatusSave"
              data-unsaved="<spring:message code='editor.status.unsaved'/>"
              data-saved-locally="<spring:message code='editor.status.savedLocally'/>"
              data-saved="<spring:message code='editor.status.saved'/>"><c:choose><c:when test="${editorNotYetSaved}"><spring:message code="weblogEdit.unsaved"/></c:when><c:otherwise><spring:message code="editor.status.saved"/></c:otherwise></c:choose></span>
        <%-- Upload refusals land here, a live region of their own: the word
             count beside it changes on every keystroke and must not be
             announced each time. --%>
        <span class="editor-status-alerts" id="editorStatusAlerts" role="status"></span>
    </div>
</div>

<%-- Media file chooser, opened by the toolbar's image button, by the
     media-chooser shortcodes, and by the SEO drawer's image pickers. --%>

<div id="mediafile_edit_lightbox" class="modal" role="dialog" tabindex="-1" aria-modal="true" aria-labelledby="mediafile-edit-lightbox-title">

    <div class="modal-dialog modal-lg">

        <div class="modal-content">

            <div class="modal-header">
                <p id="mediafile-edit-lightbox-title" class="modal-title"><spring:message code="weblogEdit.insertMediaFile"/></p>
                <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="<spring:message code='generic.close'/>"></button>
            </div>

            <div class="modal-body">
                <iframe id="mediaFileEditor"
                        style="visibility:inherit"
                        height="600" <%-- pixels, sigh, this is suboptimal--%>
                        width="100%"
                        frameborder="no"
                        scrolling="auto">
                </iframe>
            </div>

            <div class="modal-footer">
                <%-- Shown when a featured/social picker is handed a file that is
                     not an image: the chooser stays open for another pick. --%>
                <p class="editor-chooser-refusal" id="mediaChooserRefusal" role="status" hidden><spring:message code="editor.chooser.imagesOnly"/></p>
                <button type="button" class="btn btn-secondary" data-bs-dismiss="modal"><spring:message code="generic.close"/></button>
            </div>

        </div>
    </div>

</div>

<%-- Writing guide: an offcanvas rather than a modal, so it can sit alongside
     the editor instead of blocking it. The shortcode table is generated from
     ${shortcodeCards} -- the same model attribute that drives the Insert
     menu above -- so a sixth shortcode cannot ship without also showing up
     here; ShortcodeCardIT compares the two sets in a real browser. --%>
<div class="offcanvas offcanvas-end editor-guide" tabindex="-1" id="editorGuide" aria-labelledby="editorGuideTitle">
    <div class="offcanvas-header">
        <p class="rail-group-label mb-0" id="editorGuideTitle"><spring:message code="editor.guide"/></p>
        <button type="button" class="btn-close" data-bs-dismiss="offcanvas" aria-label="<spring:message code='generic.close'/>"></button>
    </div>
    <div class="offcanvas-body">
        <p class="rail-group-label"><spring:message code="editor.guide.markdown"/></p>
        <table class="editor-guide-table">
            <tr><td><code>## <spring:message code="editor.heading"/></code></td><td><spring:message code="editor.guide.h1"/></td></tr>
            <tr><td><code>**<spring:message code="editor.boldPlaceholder"/>**</code></td><td><spring:message code="editor.bold"/></td></tr>
            <tr><td><code>*<spring:message code="editor.italicPlaceholder"/>*</code></td><td><spring:message code="editor.italic"/></td></tr>
            <tr><td><code>[<spring:message code="editor.linkPlaceholder"/>](https://…)</code></td><td><spring:message code="editor.link"/></td></tr>
            <tr><td><code>- <spring:message code="editor.guide.item"/></code></td><td><spring:message code="editor.bulletList"/></td></tr>
            <tr><td><code>1. <spring:message code="editor.guide.item"/></code></td><td><spring:message code="editor.numberedList"/></td></tr>
            <tr><td><code>&gt; <spring:message code="editor.guide.quote"/></code></td><td><spring:message code="editor.quote"/></td></tr>
            <tr><td><code>`<spring:message code="editor.codePlaceholder"/>`</code></td><td><spring:message code="editor.code"/></td></tr>
            <tr><td><code>| a | b |</code></td><td><spring:message code="editor.table"/></td></tr>
        </table>
        <p class="rail-group-label"><spring:message code="editor.guide.shortcodes"/></p>
        <table class="editor-guide-table">
            <c:forEach items="${shortcodeCards}" var="card">
                <tr data-shortcode="<c:out value='${card.name}'/>"><td><code><c:out value="${card.snippet}"/></code></td><td><spring:message code="${card.labelKey}"/></td></tr>
            </c:forEach>
        </table>
        <p class="rail-group-label"><spring:message code="editor.guide.shortcuts"/></p>
        <%-- editor-mod marks the modifier: EditorScript.jsp shows the Command
             key on a Mac, where CodeMirror's "Mod" bindings answer to Cmd. --%>
        <table class="editor-guide-table">
            <tr><td><kbd class="editor-mod">Ctrl</kbd>+<kbd>S</kbd></td><td><spring:message code="${empty editorSaveActionKey ? 'weblogEdit.save' : editorSaveActionKey}"/></td></tr>
            <tr><td><kbd class="editor-mod">Ctrl</kbd>+<kbd>Enter</kbd></td><td><spring:message code="${editorPrimaryActionKey}"/></td></tr>
            <tr><td><kbd class="editor-mod">Ctrl</kbd>+<kbd>B</kbd> / <kbd>I</kbd></td><td><spring:message code="editor.bold"/> / <spring:message code="editor.italic"/></td></tr>
            <tr><td><kbd class="editor-mod">Ctrl</kbd>+<kbd>K</kbd></td><td><spring:message code="editor.link"/></td></tr>
            <tr><td><kbd class="editor-mod">Ctrl</kbd>+<kbd>F</kbd></td><td><spring:message code="editor.find"/></td></tr>
            <tr><td><kbd class="editor-mod">Ctrl</kbd>+<kbd>/</kbd></td><td><spring:message code="editor.guide"/></td></tr>
            <tr><td><kbd>Esc</kbd>&#32;<kbd>Tab</kbd></td><td><spring:message code="editor.guide.leaveEditor"/></td></tr>
        </table>
    </div>
</div>
