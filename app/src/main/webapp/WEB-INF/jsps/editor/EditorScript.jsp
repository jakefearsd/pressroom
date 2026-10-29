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
  The editor's script half, shared by the entry editor and the page editor;
  EditorSurface.jsp is the markup half and both screens include both.

  What is here: mounting PressroomEditor, the toolbar commands, the write/split/
  preview mode control and the live preview, the three seam functions, paste
  and drop upload, the status line, the guide, the media chooser and the SEO
  image pickers.

  What is deliberately NOT here: local draft recovery and the leave warning.
  Both are genuinely per-screen -- a different form id, different storage keys,
  and a different field denylist (an entry excludes bean.status from the
  snapshot, a page must not) -- so each editor keeps its own install() call
  and its own dirty flag, and EditorAutosaveWiringTest reads each file for it.

  Two globals the including page must define, as top-level function
  declarations in its own <script> (not inside $(document).ready -- they are
  read by name when the editor mounts, which happens on ready):

    rollerSaveDraft()  what Ctrl+S does
    rollerPublish()    what Ctrl+Enter does; the page editor points both at
                       its one Save button, the same collapse its keydown
                       handler already makes

  Passing the references straight through rather than wrapping them is
  deliberate: editor.js binds a key only when the callback is present, so a
  screen that defines neither leaves both keys to CodeMirror rather than
  swallowing them into a no-op.
--%>
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<script>

    <%-- The editor. Entries and pages are stored as Markdown -- always, with
         no alternative format to switch to -- so this is a Markdown editor
         with a preview, not a rich-text editor. A WYSIWYG surface may replace
         it one day, but it would edit Markdown rather than produce HTML. --%>
    var rollerEditor = null;

    <%-- PressroomEditor.create takes ONE onChange, and several things on these
         pages need to know the text moved: the leave-warning dirty flag,
         local draft recovery, the live preview and the word count. The page
         owns the fan-out rather than each of them reaching into the editor,
         which is what keeps the editor swappable. --%>
    var rollerEditorChangeListeners = [];

    <%-- Toolbar commands, assigned once the editor exists. Declared out here
         because the document-level Ctrl+/ handler reads it too. --%>
    var commands = {};

    <%-- Per-install, so two Rollers on one origin do not share a mode. --%>
    var ROLLER_EDITOR_MODE_KEY = 'roller.editor.mode.v1:${pageContext.request.contextPath}';

    <%-- The form the surface sits in, whatever this screen calls it (#entry /
         #pageEditForm). Read off the surface rather than named here, so the
         two screens share one binding instead of one each. --%>
    function rollerEditorForm() {
        var surface = document.getElementById('editorSurface');
        return surface ? surface.closest('form') : null;
    }

    <%-- The toolbar's help button and Ctrl+/ both call this through
         commands.help, which only calls it if it is defined. --%>
    window.rollerOpenGuide = function () {
        bootstrap.Offcanvas.getOrCreateInstance(document.getElementById('editorGuide')).show();
    };

    <%-- CodeMirror's "Mod" bindings answer to Cmd on a Mac, so that is the
         key the tooltips and the guide should name there. Same platform test
         CodeMirror itself makes. The markup says Ctrl; this only rewrites it. --%>
    function rollerShowMacModifier() {
        if (!/Mac|iP(hone|[oa]d)/.test(navigator.platform || '')) {
            return;
        }
        document.querySelectorAll('#editorGuide .editor-mod').forEach(function (k) {
            k.textContent = '\u2318';
        });
        document.querySelectorAll('#editorToolbar [data-shortcut]').forEach(function (b) {
            b.title = b.title.replace('Ctrl+', '\u2318');
        });
    }

    <%-- Word count / reading time. Split from rollerUpdateStatus() below so
         the one-time call right after the editor is created (to seed existing
         content's word count on first paint) does not also mark a freshly
         opened, untouched entry as having unsaved changes. --%>
    function rollerUpdateWordCount() {
        var stats = rollerEditor.stats();
        var status = document.getElementById('editorStatus');
        var template = status.dataset.wordsTemplate || '';
        document.getElementById('editorStatusWords').textContent =
            template.replace('{0}', stats.words).replace('{1}', stats.minutes);
    }

    <%-- The save-state span holds its three possible messages as data
         attributes (set from the message bundle in the markup) rather than as
         JS string literals, so there is exactly one place -- the JSP -- that
         ever spells out "Unsaved changes" et al. state is one of
         'unsaved' / 'savedLocally' / 'saved', matching the dataset property
         names data-unsaved / data-saved-locally / data-saved decode to. --%>
    function rollerSetSaveState(state) {
        var el = document.getElementById('editorStatusSave');
        if (el) {
            el.textContent = el.dataset[state] || '';
        }
    }

    <%-- Registered on every editor change and every form input/change below.
         Any edit -- to the text or to a rail field like category or tags --
         means the content no longer matches what the server last saved. --%>
    function rollerUpdateStatus() {
        rollerUpdateWordCount();
        rollerSetSaveState('unsaved');
    }

    $(document).ready(function () {
        <%-- The shortcuts for focus OUTSIDE the editor: the title field, the
             rail, the SEO drawer. Inside the editor they are bound on the
             editor itself instead (onSave/onPublish/onHelp below), because
             CodeMirror's own default keymap claims two of the three --
             Mod-Enter inserts a blank line and Mod-slash toggles a comment --
             and this handler cannot take a key back off it; see the bail
             below. The two halves must stay in step: a shortcut added here
             and not there works everywhere except the writing surface. --%>
        document.addEventListener('keydown', function (event) {
            <%-- A binding that DID handle the key calls preventDefault without
                 stopPropagation, so the event still reaches document -- and
                 this handler would fire a second click, i.e. two saves, or a
                 save and a publish, per Ctrl-S. Bailing on an already-handled
                 event is what keeps any such pair from overlapping. --%>
            if (event.defaultPrevented) {
                return;
            }
            if (!(event.ctrlKey || event.metaKey)) {
                return;
            }
            if (event.key === 's' || event.key === 'S') {
                event.preventDefault();
                if (window.rollerSaveDraft) {
                    window.rollerSaveDraft();
                }
            } else if (event.key === 'Enter') {
                event.preventDefault();
                if (window.rollerPublish) {
                    window.rollerPublish();
                }
            } else if (event.key === '/' && commands.help) {
                event.preventDefault();
                commands.help();
            }
        });

        <%-- Assigned before the editor is created, because create() is
             handed commands.help as its Ctrl+/ binding. The bodies run
             later, so referring to rollerEditor here is fine. --%>
        commands = {
            bold: function () { rollerEditor.wrapSelection('**', '**', '<spring:message code="editor.boldPlaceholder" javaScriptEscape="true"/>'); },
            italic: function () { rollerEditor.wrapSelection('*', '*', '<spring:message code="editor.italicPlaceholder" javaScriptEscape="true"/>'); },
            heading: function () { rollerEditor.toggleLinePrefix('## '); },
            quote: function () { rollerEditor.toggleLinePrefix('> '); },
            ul: function () { rollerEditor.toggleLinePrefix('- '); },
            ol: function () { rollerEditor.toggleLinePrefix('1. '); },
            link: function () { rollerEditor.wrapSelection('[', '](https://)', '<spring:message code="editor.linkPlaceholder" javaScriptEscape="true"/>'); },
            code: function () { rollerEditor.wrapSelection('`', '`', '<spring:message code="editor.codePlaceholder" javaScriptEscape="true"/>'); },
            table: function () { rollerEditor.insert('\n| <spring:message code="editor.tableHeader" javaScriptEscape="true"/> | <spring:message code="editor.tableHeader" javaScriptEscape="true"/> |\n| --- | --- |\n|  |  |\n'); },
            image: function () { onClickMediaFileInsert(); },
            help: function () { if (window.rollerOpenGuide) { window.rollerOpenGuide(); } }
        };
        <%-- The insert menu is the registry's own list, read back off the
             DOM: the editor's completion source and the menu therefore
             cannot disagree about what a shortcode is called. --%>
        var shortcodes = [];
        document.querySelectorAll('#shortcodeInsertMenu .shortcode-card').forEach(function (b) {
            shortcodes.push({ name: b.dataset.shortcode, snippet: b.dataset.snippet, label: b.textContent.trim() });
        });
        rollerEditor = PressroomEditor.create({
            textarea: document.getElementById('edit_content'),
            placeholder: '<spring:message code="editor.placeholder" javaScriptEscape="true"/>',
            shortcodes: shortcodes,
            <%-- Bound on the editor, not just on document: CodeMirror's
                 default keymap owns Mod-Enter and Mod-slash, so the
                 document-level handler never sees them unhandled. Passed by
                 reference, so a screen that supplies neither leaves the keys
                 to CodeMirror rather than binding a dead no-op. --%>
            onSave: window.rollerSaveDraft,
            onPublish: window.rollerPublish,
            onHelp: commands.help,
            <%-- The shortcuts the toolbar tooltips and the guide advertise.
                 Bound in the editor only: outside it there is no selection to
                 format, and the keys keep their browser meaning. --%>
            onBold: commands.bold,
            onItalic: commands.italic,
            onLink: commands.link,
            onUpload: rollerUploadImages,
            onChange: function () {
                <%-- A listener that throws must not stop the ones after it:
                     losing the dirty flag because draft recovery failed would
                     be a silent loss of the leave warning. --%>
                rollerEditorChangeListeners.forEach(function (listener) {
                    try {
                        listener();
                    } catch (e) {
                        /* one bad listener is not the others' problem */
                    }
                });
            }
        });

        rollerShowMacModifier();

        <%-- The preview follows the text. Registered on the page's own fan-out
             array, not on the editor, so an editor swap carries it along. --%>
        rollerEditorChangeListeners.push(rollerSchedulePreview);

        <%-- Status line: word count follows every change, same as the preview
             above. Seeded once, right here, so existing content shows its real
             word count on first paint rather than only after the first
             keystroke -- rollerUpdateWordCount() alone, not
             rollerUpdateStatus(), so seeding it does not also mark untouched
             content "Unsaved changes". --%>
        rollerUpdateWordCount();
        rollerEditorChangeListeners.push(rollerUpdateStatus);

        <%-- The status line's own copy of the "anything changed" signal: a
             rail field (category, tags, status) has no editor change event to
             hang off, so it needs this binding independently of the editor's
             own fan-out array above. --%>
        var editorForm = rollerEditorForm();
        if (editorForm) {
            $(editorForm).on('input change', rollerUpdateStatus);
        }

        <%-- Scroll sync, editor -> preview only. Proportional rather than
             line-mapped: a rendered gallery or map is metres taller than the
             two lines of Markdown that produced it, so there is no honest
             line-to-pixel mapping to build. Throttled to one message per
             animation frame -- a scroll event fires far faster than the frame
             can repaint, and every extra postMessage is work nobody sees. --%>
        var rollerScrollQueued = false;
        rollerEditor.view.scrollDOM.addEventListener('scroll', function () {
            if (rollerScrollQueued) {
                return;
            }
            rollerScrollQueued = true;
            window.requestAnimationFrame(function () {
                rollerScrollQueued = false;
                if (!rollerPreview.ready) {
                    return;
                }
                rollerPostToPreview({
                    type: 'roller-preview-scroll',
                    scroll: rollerEditor.scrollFraction()
                });
            });
        });

        document.getElementById('editorToolbar').addEventListener('click', function (event) {
            var button = event.target.closest('button[data-cmd]');
            if (button && commands[button.dataset.cmd]) {
                event.preventDefault();
                commands[button.dataset.cmd]();
            }
        });
        document.getElementById('editorMode').addEventListener('click', function (event) {
            var button = event.target.closest('button[data-mode]');
            if (button) {
                rollerSetEditorMode(button.dataset.mode);
            }
        });
        try {
            var storedMode = window.localStorage.getItem(ROLLER_EDITOR_MODE_KEY);
            if (storedMode) {
                rollerSetEditorMode(storedMode);
            }
        } catch (e) {
            /* storage unavailable: write mode */
        }

        <%-- Every card goes through insertMediaFile, the editor's one insert
             seam, so the WYSIWYG-for-Markdown surface that may replace this
             one inherits the whole menu by reimplementing a single function. --%>
        $(".shortcode-card").on('click', function (event) {
            event.preventDefault();
            if (this.dataset.chooser === 'true') {
                onClickMediaFileInsert();
            } else {
                insertMediaFile(this.dataset.snippet);
                rollerEditor.focus();
            }
        });
    });

    <%-- The preview is the weblog's own theme in an iframe, not a fragment
         dumped into a div. The shell document (PreviewServlet's ?shell=true
         branch) carries the theme stylesheet and the gallery/map/embed asset
         scripts around one empty #previewArticle; we post the server-rendered
         HTML in and it swaps and re-initialises. That is why an author sees
         their prose set the way it will publish rather than unstyled. --%>
    var rollerPreview = { frame: null, ready: false, timer: null, pending: false, inflight: false };

    function rollerEnsurePreviewFrame() {
        if (rollerPreview.frame) {
            return;
        }
        var pane = document.getElementById('editorPreviewPane');
        var frame = document.createElement('iframe');
        <%-- allow-same-origin is required, not optional: without it the frame
             gets an opaque origin, its postMessage arrives as "null" and the
             origin check on both sides refuses it. The document is ours and
             same-origin already, so this grants nothing new. --%>
        frame.setAttribute('sandbox', 'allow-scripts allow-same-origin');
        frame.setAttribute('title', '<spring:message code="editor.mode.preview" javaScriptEscape="true"/>');
        frame.src = pane.dataset.shellUrl;
        pane.appendChild(frame);
        rollerPreview.frame = frame;
        <%-- Registered once, with the frame. The shell announces itself when
             its document is ready, so the first push waits for that rather
             than guessing -- and because the ensure/push pair below is
             idempotent, re-entering preview mode costs nothing.

             event.source is checked in addition to event.origin: two Roller
             tabs open on the same origin each have their own iframe, and the
             origin check alone cannot tell one tab's shell from another's --
             only the frame this listener itself created may speak to it. --%>
        window.addEventListener('message', function (event) {
            if (event.origin !== window.location.origin) {
                return;
            }
            if (event.source !== rollerPreview.frame.contentWindow) {
                return;
            }
            if (event.data && event.data.type === 'roller-preview-ready') {
                rollerPreview.ready = true;
                rollerPushPreview();
            }
        });
    }

    <%-- Debounced: a POST per keystroke would render the whole shortcode
         pipeline on every character. 400ms of idle is below "did it hang?" --%>
    function rollerSchedulePreview() {
        if (document.getElementById('editorSurface').dataset.mode === 'write') {
            return;
        }
        window.clearTimeout(rollerPreview.timer);
        rollerPreview.timer = window.setTimeout(rollerPushPreview, 400);
    }

    <%-- The preview is rendered by the SERVER, not by a Markdown library in
         the browser. Only the server can expand [gallery], [map] and the rest,
         and a preview that disagreed with the published page about those would
         be worse than no preview at all.

         The endpoint and the id field come off the surface, because the two
         screens post to different actions (entryEdit / pageEdit) -- see
         EditorSurface.jsp's header.

         In-flight coalescing rather than cancellation: a slow render must not
         be overtaken by a later one whose response arrives first, which would
         paint stale text over new. At most one request is out; anything that
         happens while it is out collapses into a single follow-up. --%>
    function rollerPushPreview() {
        if (!rollerPreview.ready) {
            return;
        }
        if (rollerPreview.inflight) {
            rollerPreview.pending = true;
            return;
        }
        rollerPreview.inflight = true;
        var surface = document.getElementById('editorSurface');
        var idField = surface.dataset.idField;
        var payload = {
            text: rollerGetEntryText(),
            weblog: $("input[name='weblog']").val(),
            '${_csrf.parameterName}': '${_csrf.token}'
        };
        if (idField) {
            <%-- Absent on an add form, where nothing has an id yet; the
                 endpoints both read a blank id as "preview a scratch row". --%>
            payload.id = $("input[name='" + idField + "']").val() || '';
        }
        $.ajax({
            type: 'POST',
            url: surface.dataset.previewUrl,
            data: payload,
            success: function (html) {
                rollerPostToPreview({ type: 'roller-preview', html: html, scroll: rollerEditor.scrollFraction() });
            },
            error: function () {
                rollerPostToPreview({
                    type: 'roller-preview',
                    html: '<p class="roller-preview-error"><spring:message code="weblogEdit.previewFailed" javaScriptEscape="true"/></p>'
                });
            },
            complete: function () {
                rollerPreview.inflight = false;
                if (rollerPreview.pending) {
                    rollerPreview.pending = false;
                    rollerPushPreview();
                }
            }
        });
    }

    <%-- One place that talks to the frame, so the origin argument cannot
         drift between the three callers. --%>
    function rollerPostToPreview(message) {
        if (!rollerPreview.frame || !rollerPreview.frame.contentWindow) {
            return;
        }
        rollerPreview.frame.contentWindow.postMessage(message, window.location.origin);
    }

    <%-- Mode is a data attribute on the surface; CSS lays the panes out. --%>
    function rollerSetEditorMode(mode) {
        var surface = document.getElementById('editorSurface');
        surface.dataset.mode = mode;
        document.querySelectorAll('#editorMode button[data-mode]').forEach(function (b) {
            b.setAttribute('aria-checked', b.dataset.mode === mode ? 'true' : 'false');
        });
        var pane = document.getElementById('editorPreviewPane');
        pane.hidden = (mode === 'write');
        if (mode !== 'write') {
            rollerEnsurePreviewFrame();
            rollerPushPreview();
        }
        try {
            window.localStorage.setItem(ROLLER_EDITOR_MODE_KEY, mode);
        } catch (e) {
            /* storage unavailable: the choice simply does not persist */
        }
    }

    <%-- The one seam for putting text into the editor, used by the media
         chooser (which inserts an [image id=..] shortcode) and by the browser
         tests. Everything else talks to the editor through here, so swapping
         the editor again does not mean hunting down its callers. --%>
    function insertMediaFile(toInsert) {
        rollerEditor.insert(toInsert);
    }

    function rollerSetEntryText(text) {
        rollerEditor.setValue(text);
    }

    function rollerGetEntryText() {
        return rollerEditor.getValue();
    }

    <%-- Replace one span of the document in place, leaving everything else --
         including the caret -- where it is. Deliberately NOT
         rollerSetEntryText(whole text): setValue replaces the document and
         drops the selection to the end, so dropping two images at once yanked
         the caret away while the author was still typing between them.
         Returns false when the text is no longer there (the author deleted
         the placeholder mid-upload), in which case there is nothing to fix. --%>
    function rollerReplaceInEditor(needle, replacement) {
        var view = rollerEditor.view;
        var at = view.state.doc.toString().indexOf(needle);
        if (at < 0) {
            return false;
        }
        view.dispatch({ changes: { from: at, to: at + needle.length, insert: replacement } });
        return true;
    }

    <%-- Paste/drop upload. A placeholder line marks where the image will go;
         it is replaced by the shortcode on success and removed on refusal, and
         the refusal is shown on the status line. Files go one at a time so a
         refusal is attributable to its file -- editor.js's fileHandler is the
         caller, passing every pasted/dropped image file at once, and this is
         the only seam that reaches the upload endpoint from inside the editor. --%>
    function rollerUploadImages(files) {
        var queue = Array.prototype.slice.call(files);
        (function next() {
            var file = queue.shift();
            if (!file) { return; }
            var placeholder = '[<spring:message code="editor.uploadingPlaceholder" javaScriptEscape="true"/> '
                    + file.name.replace(/[\[\]]/g, '') + '…]';
            rollerEditor.insert(placeholder + '\n');
            var form = new FormData();
            form.append('file', file, file.name);
            form.append('weblog', $("input[name='weblog']").val());
            form.append('${_csrf.parameterName}', '${_csrf.token}');
            fetch('<c:url value="/roller-ui/authoring/mediaFileAdd!upload.rol"/>', { method: 'POST', body: form, credentials: 'same-origin' })
                .then(function (r) { return r.json().then(function (body) { return { status: r.status, body: body }; }); })
                .then(function (res) {
                    var result = res.body && res.body.results && res.body.results[0];
                    if (result && result.id) {
                        rollerReplaceInEditor(placeholder, '[image id="' + result.id + '"]');
                    } else {
                        rollerDropPlaceholder(placeholder);
                        <%-- A per-file refusal names its file; a refusal of the
                             whole request (uploads off, no files) has no
                             results and says why at the top level. --%>
                        rollerStatusError((result && result.detail) || (res.body && res.body.detail)
                                || '<spring:message code="editor.uploadFailed" javaScriptEscape="true"/>');
                    }
                })
                .catch(function () {
                    rollerDropPlaceholder(placeholder);
                    rollerStatusError('<spring:message code="editor.uploadFailed" javaScriptEscape="true"/>');
                })
                .then(next);
        })();
    }

    <%-- The placeholder was inserted with its own trailing newline; take that
         with it when the upload was refused, and fall back to the bare text
         if the author has since typed on that line. --%>
    function rollerDropPlaceholder(placeholder) {
        if (!rollerReplaceInEditor(placeholder + '\n', '')) {
            rollerReplaceInEditor(placeholder, '');
        }
    }

    <%-- The refusal detail is server text (PressroomMessages), rendered via
         textContent -- never innerHTML -- so it cannot carry markup even
         though it did not come from a message key with a known-safe shape. --%>
    function rollerStatusError(message) {
        var status = document.getElementById('editorStatusAlerts');
        var el = document.createElement('span');
        el.className = 'is-error';
        el.textContent = message;
        status.appendChild(el);
        window.setTimeout(function () { el.remove(); }, 8000);
    }

    <%-- The title is a one-row textarea, not an input, so a long title
         wraps and the field grows to show all of it (an input cut it off
         mid-word). It is still one line of text: Enter moves on to the body
         rather than starting a second line -- except while an IME is
         composing, where Enter commits the composition -- and a pasted line
         break becomes a space. --%>
    function rollerFitTitle(title) {
        title.style.height = 'auto';
        title.style.height = title.scrollHeight + 'px';
    }

    (function () {
        var title = document.querySelector('textarea.editor-title');
        if (!title) { return; }
        title.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' && !e.isComposing) {
                e.preventDefault();
                if (window.rollerEditor && rollerEditor.view) { rollerEditor.view.focus(); }
            }
        });
        title.addEventListener('input', function () {
            if (/[\r\n]/.test(title.value)) {
                var caret = title.selectionStart;
                title.value = title.value.replace(/\s*[\r\n]+\s*/g, ' ');
                caret = Math.min(caret, title.value.length);
                title.setSelectionRange(caret, caret);
            }
            rollerFitTitle(title);
        });
        <%-- A restored draft sets the value without an input event, and a
             narrower window rewraps it: measure again on both. --%>
        title.addEventListener('focus', function () { rollerFitTitle(title); });
        window.addEventListener('resize', function () { rollerFitTitle(title); });
        rollerFitTitle(title);
    })();

    <%-- Media chooser --%>

    <%-- Opens the media chooser. With no argument the chosen file is inserted
         into the editor at the cursor; with a picker
         target ('featuredImage' / 'ogImage') the choice is routed to
         onImagePicked below instead. --%>
    function onClickMediaFileInsert(pickerTarget) {
        window.mediaPickerTarget = pickerTarget || null;
        window.mediaLightboxCloseRequested = false;
        document.getElementById('mediaChooserRefusal').hidden = true;
        <c:url var="mediaFileImageChooser" value="/roller-ui/authoring/overlay/mediaFileImageChooser.rol">
        <c:param name="weblog" value="${actionWeblog.handle}"/>
        </c:url>
        var chooserUrl = '${mediaFileImageChooser}';
        <%-- A picker takes an image, so its chooser lists images only (the
             refusal in onSelectMediaFile stays as the backstop). Insert-media
             lists every file: a non-image goes in as a link. --%>
        if (pickerTarget) {
            chooserUrl += (chooserUrl.indexOf('?') < 0 ? '?' : '&') + 'imagesOnly=true';
        }
        $("#mediaFileEditor").attr('src', chooserUrl);
        bootstrap.Modal.getOrCreateInstance(document.getElementById('mediafile_edit_lightbox')).show();
    }

    function onClose() {
        $("#mediaFileEditor").attr('src', 'about:blank');
    }

    <%-- Bootstrap silently ignores hide() while the fade-in transition is
         still running, so selecting a file quickly after opening the chooser
         would leave the modal stuck open. Ask for the close, and if the modal
         is still transitioning, the shown.bs.modal listener below re-issues
         it once the fade-in has finished. --%>
    function closeMediaFileLightbox() {
        window.mediaLightboxCloseRequested = true;
        bootstrap.Modal.getOrCreateInstance(document.getElementById('mediafile_edit_lightbox')).hide();
    }

    document.getElementById('mediafile_edit_lightbox').addEventListener('shown.bs.modal', function () {
        if (window.mediaLightboxCloseRequested) {
            bootstrap.Modal.getOrCreateInstance(this).hide();
        }
    });

    <%-- Callback from MediaFileImageChooser.jsp inside the iframe, which
         always passes the id. --%>
    function onSelectMediaFile(name, url, isImage, id) {
        <%-- A featured/social picker takes an image. Anything else used to
             close the chooser and silently do nothing; now it says so and
             stays open for another pick. --%>
        if (window.mediaPickerTarget && (isImage !== "true" || !id)) {
            document.getElementById('mediaChooserRefusal').hidden = false;
            return;
        }
        closeMediaFileLightbox();
        $("#mediaFileEditor").attr('src', 'about:blank');
        if (window.mediaPickerTarget) {
            var target = window.mediaPickerTarget;
            window.mediaPickerTarget = null;
            onImagePicked(target, name, url, isImage, id);
            return;
        }
        if (isImage === "true") {
            <%-- The [image] shortcode expands at render time into a
                 responsive <figure><picture> with the full srcset ladder,
                 so authors get the rendition pipeline automatically.
                 Existing content with the old raw <img> markup is left
                 exactly as it is. --%>
            insertMediaFile('[image id="' + id + '"]');
        } else {
            insertMediaFile(rollerMarkdownLink(name, url));
        }
    }

    <%-- Entries are Markdown, so a non-image file goes in as a Markdown link,
         not raw HTML. Brackets in the name would end the link text, and a
         space or parenthesis in the URL would end the destination, so both
         are neutralised. --%>
    function rollerMarkdownLink(name, url) {
        var text = String(name).replace(/[\[\]]/g, '');
        var href = String(url).replace(/[ ()]/g, function (c) {
            return { ' ': '%20', '(': '%28', ')': '%29' }[c];
        });
        return '[' + text + '](' + href + ')';
    }

    <%-- SEO drawer image pickers. Generic over the target because both
         screens name their fields the same way -- seo_<target>Id,
         seo_<target>_preview, seo_<target>_clear -- so the entry editor's
         featuredImage/ogImage pair and the page editor's ogImage share one
         implementation instead of two that can drift. --%>

    function openImagePicker(target) {
        onClickMediaFileInsert(target);
    }

    function onImagePicked(target, name, url, isImage, id) {
        if (isImage !== "true" || !id) {
            return;
        }
        $('#seo_' + target + 'Id').val(id);
        $('#seo_' + target + '_preview').attr('src', url + '?t=true').show();
        $('#seo_' + target + '_clear').show();
    }

    function clearPickedImage(target) {
        $('#seo_' + target + 'Id').val('');
        $('#seo_' + target + '_preview').removeAttr('src').hide();
        $('#seo_' + target + '_clear').hide();
    }

</script>
