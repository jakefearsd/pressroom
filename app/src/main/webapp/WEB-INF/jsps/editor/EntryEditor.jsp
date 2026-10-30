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
<%-- This page is designed to be included in EntryEdit.jsp --%>
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>


<%-- ********************************************************************* --%>

<%-- content --%>

<%-- The writing surface and its script are shared with the page editor; this
     file is the entry-shaped half around them. Request scope, not page scope:
     a jsp:include cannot see page-scoped variables set here.

     editorFieldValue is escaped HERE rather than inside the include -- see
     EditorSurface.jsp's header for why that placement is load-bearing. --%>
<c:set var="editorFieldName" scope="request" value="bean.text"/>
<c:set var="editorFieldValue" scope="request" value="${fn:escapeXml(bean.text)}"/>
<c:set var="editorIdField" scope="request" value="bean.id"/>
<c:url var="editorPreviewUrl" scope="request" value="/pressroom-ui/authoring/entryEdit!preview.rol"/>
<%-- The shared guide's Ctrl+Enter row reads this key -- Post here, Save on
     the page editor, which has no separate publish action. --%>
<c:set var="editorPrimaryActionKey" scope="request" value="weblogEdit.post"/>
<%-- A new entry has no row yet, so the status line must not open on "Saved". --%>
<c:set var="editorNotYetSaved" scope="request" value="${empty bean.id}"/>
<%-- On a live entry Ctrl+S updates it rather than unpublishing (see the
     Publish box in EntryEdit.jsp), and the guide says so. --%>
<c:if test="${userAnAuthor and bean.published}">
    <c:set var="editorSaveActionKey" scope="request" value="weblogEdit.update"/>
</c:if>

<jsp:include page="/WEB-INF/jsps/editor/EditorSurface.jsp"/>

<%-- summary: a quiet drawer under the editor, same convention as the SEO
     drawer in the rail (.editor-drawer/.editor-drawer-body) but with no
     enclosing .editor-box -- this one sits in the main column, not the
     rail. --%>

<%-- A button, not an href="#" anchor: it toggles, it goes nowhere. The
     textarea is named for itself, since the toggle above is no label.
     The summary is the line under the title in a list of entries (the
     themes read it through WeblogEntryWrapper#getExcerpt) and the feed's
     summary, so the tip says that and what a list shows without one. An
     entry that has a summary opens with the drawer open: a written summary
     should not be out of sight. --%>
<button type="button" class="editor-drawer${empty bean.summary ? ' collapsed' : ''}" data-bs-toggle="collapse" data-bs-target="#collapseSummaryEditor"
        aria-expanded="${empty bean.summary ? 'false' : 'true'}" aria-controls="collapseSummaryEditor">
    <spring:message code="weblogEdit.summary"/>
</button>
<div id="collapseSummaryEditor" class="collapse${empty bean.summary ? '' : ' show'}">
    <div class="editor-drawer-body">
        <textarea name="bean.summary" id="edit_summary" rows="3" class="col-sm-12"
                  aria-describedby="edit_summary_tip"
                  aria-label="<spring:message code='weblogEdit.summary'/>">${fn:escapeXml(bean.summary)}</textarea>
        <div id="edit_summary_tip" class="form-text"><spring:message code="weblogEdit.summary.tip"/></div>
    </div>
</div>

<%-- ********************************************************************* --%>

<jsp:include page="/WEB-INF/jsps/editor/EditorScript.jsp"/>

<script>

    <%-- What the editor's Ctrl+S and Ctrl+Enter do here, read by name when
         EditorScript.jsp mounts the editor. Declared at script top level, not
         inside ready(): the mount happens on ready, and a definition made
         there would not exist yet.

         Click the real buttons rather than submitting the form: the buttons
         carry the formaction that decides draft-vs-publish, and a bare
         form.submit() would post to the form's own action and silently pick
         the wrong one. Ctrl+S clicks whichever button EntryEdit.jsp marks:
         Save draft, or Update on a published entry, where Save draft would
         unpublish it. --%>
    function pressroomSaveDraft() {
        var button = document.querySelector("#entry button[data-save-shortcut]");
        if (button) {
            button.click();
        }
    }

    function pressroomPublish() {
        var button = document.querySelector("#entry button[formaction$='publish.rol']");
        if (button) {
            button.click();
        }
    }

    $(document).ready(function () {

        <%-- Warn before leaving with unsaved edits, and stand down on submit.
             Bound ONCE, tracking a dirty flag. The previous version registered
             both leave-warning handlers *inside* the change callback, so every
             keystroke added another one of each -- and the one that actually
             mattered, another submit handler on the form about to be posted.

             The warning stays even though drafts now survive a lost tab: a
             local snapshot is a recovery mechanism, not a reason to stop
             telling someone they are walking away from unsaved work. --%>
        var pressroomEntryDirty = false;
        pressroomEditorChangeListeners.push(function () {
            pressroomEntryDirty = true;
        });
        $("#entry").on('input change', function () {
            pressroomEntryDirty = true;
        });
        <%-- Namespaced. A bare .off("beforeunload") below would unbind every
             beforeunload handler on the page, including one belonging to
             something else added later -- silently, with no signal. --%>
        $(window).on("beforeunload.pressroomLeaveWarning", function (event) {
            if (!pressroomEntryDirty) {
                return undefined;
            }
            if (event.originalEvent) {
                event.originalEvent.returnValue = "<spring:message code='weblogEdit.leaveWarning' javaScriptEscape='true'/>";
            }
            return "<spring:message code='weblogEdit.leaveWarning' javaScriptEscape='true'/>";
        });
        $("#entry").on('submit', function () {
            pressroomEntryDirty = false;
            $(window).off("beforeunload.pressroomLeaveWarning");
        });

        <%-- Local draft recovery. The key carries the context path so two
             Pressroom installs on one origin cannot share storage, and the
             action name so entryAdd and entryEdit are distinct. staleKeys
             names the entryAdd:new snapshot: saving a new entry redirects
             here under a real id, and without this the "new" draft would
             linger and be offered to whoever starts the next entry. --%>
        if (window.pressroomDraft) {
            window.pressroomDraft.install({
                form: document.getElementById('entry'),
                key: '${fn:escapeXml(draftKey)}',
                staleKeys: ['${draftNewKey}'],
                bar: document.getElementById('draftRecoveryBar'),
                csrfName: '${_csrf.parameterName}',
                <%-- bean.text is the editor's own textarea, already captured
                     through getText/setText. bean.status must be excluded HERE
                     but not on PageEdit: doEntryEditSave mutates the bean and
                     forwards, so the page rendered right after a successful
                     Post carries PUBLISHED while the snapshot taken at submit
                     holds DRAFT -- comparing it would raise a phantom
                     "unsaved changes" bar over a save that just succeeded. --%>
                exclude: ['bean.text', 'bean.status'],
                getText: pressroomGetEntryText,
                setText: pressroomSetEntryText,
                onEditorChange: function (callback) {
                    pressroomEditorChangeListeners.push(callback);
                }
            });
        }

        <%-- pressroom-draft.js dispatches this on the bar element right after it
             writes a snapshot to localStorage. "Only if still dirty" matters
             at submit time: the entry's own submit handler above sets
             pressroomEntryDirty = false BEFORE pressroom-draft.js's own submit
             handler runs save() and dispatches this event (both are bound on
             #entry; jQuery and native handlers on the same element still run
             in registration order, and this page's dirty-flag handler is
             registered first) -- so a real save does not flash "Draft saved
             locally" a moment before the page reloads to "Saved". --%>
        var draftBarForStatus = document.getElementById('draftRecoveryBar');
        if (draftBarForStatus) {
            draftBarForStatus.addEventListener('pressroom-draft:saved', function () {
                if (pressroomEntryDirty) {
                    pressroomSetSaveState('savedLocally');
                }
            });
        }
    });

</script>
