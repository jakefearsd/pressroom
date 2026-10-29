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
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<%-- Prevent annoying scrolling when a collapse toggle's href is "#". --%>
<script type="text/javascript">
    $(document).ready(function () {
        $("a[href='#'][data-bs-toggle='collapse']").click(function (e) {
            e.preventDefault();
        });
    });
</script>

<p class="subtitle">
    <c:choose>
        <c:when test="${actionWeblog.siteSpace}"><spring:message code="weblogPagesForm.subtitle.site"/></c:when>
        <c:otherwise><spring:message code="weblogPagesForm.subtitle" arguments="${actionWeblog.handle}"/></c:otherwise>
    </c:choose>
</p>

<%-- Local draft recovery -- see EntryEdit.jsp. --%>
<script src="<c:url value='/theme/scripts/pressroom-draft.js'/>"></script>
<script>
    // ClipboardJS is loaded globally by head.jsp.
    $(document).ready(function () {
        var clipboard = new ClipboardJS('.clipbutton');
        clipboard.on('success', function (e) {
            e.trigger.classList.add('copied');
            setTimeout(function () { e.trigger.classList.remove('copied'); }, 1500);
            e.clearSelection();
        });
    });

    <%-- Session-expiry banner, copied from EntryEdit.jsp: an author can spend
         an hour on a page as easily as on an entry, and a save that lands on
         the login screen loses the lot. Purely local arithmetic against the
         container's own maxInactiveInterval -- no endpoint, no polling,
         nothing that would itself keep the session alive. The timer restarts
         on any input, so it measures inactivity the same way the container
         does; clock drift can only make it warn EARLY, the safe direction. --%>
    document.addEventListener('DOMContentLoaded', function () {
        var bar = document.getElementById('sessionExpiryBar');
        if (!bar) {
            return;
        }
        var timeout = parseInt(bar.dataset.timeout, 10);
        if (!(timeout > 180)) {
            // A session shorter than the warning lead time would show the
            // banner permanently, which teaches people to ignore it.
            return;
        }
        var warnAfterMs = (timeout - 120) * 1000;
        var timer = null;
        var arm = function () {
            bar.hidden = true;
            window.clearTimeout(timer);
            timer = window.setTimeout(function () {
                bar.hidden = false;
            }, warnAfterMs);
        };
        ['keydown', 'click', 'input'].forEach(function (type) {
            document.addEventListener(type, arm, true);
        });
        arm();
    });
</script>

<%-- Request scope, not page scope: EditorSurface.jsp arrives via jsp:include
     and cannot see page-scoped variables set here. --%>
<c:set var="draftKey" scope="request"
       value="pressroom.draft.v1:${pageContext.request.contextPath}:${actionWeblog.handle}:pageEdit:${empty bean.id ? 'new' : bean.id}"/>
<c:set var="draftNewKey" scope="request"
       value="pressroom.draft.v1:${pageContext.request.contextPath}:${actionWeblog.handle}:pageEdit:new"/>

<%-- The same writing surface plus publish rail the entry editor runs (the
     approved card is docs/design/editor/editor-writing-surface.html): the main
     column carries title, address and the Markdown editor, and everything
     about *managing* the page lives in a 252px rail. --%>

<div class="editor-grid">

<form id="pageEditForm" method="post" class="form-stacked editor-form"
      action="${pageContext.request.contextPath}/pressroom-ui/authoring/pageEdit!save.rol">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <input type="hidden" name="bean.id" value="${fn:escapeXml(bean.id)}"/>

    <%-- ================================================================== --%>
    <%-- The writing surface: title, address, editor --%>

    <div class="editor-main">

        <div id="sessionExpiryBar" class="draft-bar" hidden role="status" aria-live="polite"
             data-timeout="${pageContext.session.maxInactiveInterval}">
            <span class="draft-bar-text"><spring:message code="session.expiringSoon"/></span>
        </div>

        <%-- Draft recovery. Hidden until pressroom-draft.js finds a local
             snapshot the server does not have. type="button" is load-bearing:
             this sits inside the form, where a bare <button> submits it. --%>
        <div id="draftRecoveryBar" class="draft-bar" hidden role="status" aria-live="polite"
             data-restored="<spring:message code='weblogEdit.draftRecovery.restored'/>">
            <span class="draft-bar-text"
                  data-template="<spring:message code='weblogEdit.draftRecovery.message'/>"></span>
            <button type="button" class="draft-bar-restore"><spring:message code="weblogEdit.draftRecovery.restore"/></button>
            <span class="draft-bar-sep">&#183;</span>
            <button type="button" class="draft-bar-discard"><spring:message code="weblogEdit.draftRecovery.discard"/></button>
        </div>

        <%-- title: the page's one piece of layout hierarchy. Large serif,
             borderless -- emphasis elsewhere is weight, never size. 
             A one-row textarea so a long title wraps (EditorScript.jsp keeps
             it to one line and grows it). No whitespace inside the element:
             it would become part of the title. --%>
        <textarea id="page_bean_title" name="bean.title" rows="1" maxlength="255"
                  autofocus
                  class="editor-title"
                  placeholder="<spring:message code="weblogEdit.title"/>"
                  aria-label="<spring:message code="weblogEdit.title"/>">${fn:escapeXml(bean.title)}</textarea>

        <%-- The page's address, editable in place: the weblog's own root as a
             quiet prefix and the slug as the only thing an author types. A
             page is served at /<handle>/<slug> -- a bare single segment -- so
             there is no "page/" in it; the old form showed one, which is the
             CUSTOM-template route and handed out a URL that 404s. --%>
        <p class="editor-permalink" role="status" aria-live="polite">
            <span class="editor-slug-prefix">${urls.weblogAbsolute(actionWeblog)}</span><input
                    type="text" id="page_bean_slug" name="bean.slug" value="${fn:escapeXml(bean.slug)}"
                    maxlength="255" class="editor-slug"
                    aria-label="<spring:message code='weblogPagesForm.slug'/>"/>
            <%-- Only offered on a published page: a draft's address 404s, so
                 copying it hands someone a broken link. ClipboardJS copies the
                 attribute rather than a selected element, since the slug lives
                 in an input whose value the author may be mid-edit. --%>
            <c:if test="${not empty bean.id and bean.status == 'PUBLISHED'}">
            &#183;
            <button class="clipbutton editor-permalink-copy" type="button"
                    data-clipboard-text="${fn:escapeXml(urls.staticPage(actionWeblog, bean.slug))}"
                    aria-label="<spring:message code='generic.copyToClipboard'/>"><spring:message code="weblogEdit.copyPermalink"/></button>
            </c:if>
        </p>
        <c:if test="${frontDoorPage}">
        <p class="form-text editor-permalink-note"><spring:message code="pageEdit.frontDoorNote"
            arguments="${fn:escapeXml(urls.staticPage(actionWeblog, bean.slug))}"/></p>
        </c:if>

        <%-- The writing surface and its script are shared with the entry
             editor. editorFieldValue is escaped HERE rather than inside the
             include -- see EditorSurface.jsp's header for why. --%>
        <c:set var="editorFieldName" scope="request" value="bean.content"/>
        <c:set var="editorFieldValue" scope="request" value="${fn:escapeXml(bean.content)}"/>
        <c:set var="editorIdField" scope="request" value="bean.id"/>
        <c:url var="editorPreviewUrl" scope="request" value="/pressroom-ui/authoring/pageEdit!preview.rol"/>
        <%-- The shared guide's Ctrl+Enter row reads this key. The page editor
             has no separate publish action -- Ctrl+Enter does the same thing
             #pageSaveButton does -- so it names generic.save, not the entry
             editor's weblogEdit.post. --%>
        <c:set var="editorPrimaryActionKey" scope="request" value="generic.save"/>

        <jsp:include page="/WEB-INF/jsps/editor/EditorSurface.jsp"/>

    </div>

    <%-- ================================================================== --%>
    <%-- The publish rail --%>

    <div class="editor-rail">

        <div class="editor-box">
            <p class="rail-group-label"><spring:message code="weblogEdit.publishGroup"/></p>

            <label class="editor-field-label" for="page_bean_status"><spring:message code="weblogEdit.status"/></label>
            <select id="page_bean_status" name="bean.status" class="form-select">
                <option value="DRAFT" ${bean.status == 'DRAFT' ? 'selected' : ''}><spring:message code="weblogEdit.draft"/></option>
                <option value="PUBLISHED" ${bean.status == 'PUBLISHED' ? 'selected' : ''}><spring:message code="weblogEdit.published"/></option>
            </select>

            <div class="editor-btnrow">
                <button type="submit" id="pageSaveButton" class="btn btn-primary"><spring:message code="generic.save"/></button>
            </div>

            <%-- The reader's own view, opened in a new tab. An href rather
                 than an onclick: fn:escapeXml renders an apostrophe as
                 &#039;, which the HTML parser decodes back to ' BEFORE an
                 onclick compiles as JavaScript -- in an attribute value there
                 is no second parser. --%>
            <c:if test="${not empty bean.id and bean.status == 'PUBLISHED'}">
                <a class="editor-preview-link" target="_blank" rel="noopener"
                   href="${fn:escapeXml(urls.staticPage(actionWeblog, bean.slug))}"><spring:message code="weblogEdit.fullPreviewMode"/></a>
            </c:if>
        </div>

        <div class="editor-box">
            <p class="rail-group-label"><spring:message code="pageEdit.navigationGroup"/></p>

            <div class="form-check editor-quiet-check">
                <%-- The "_showInNav" marker is Spring's documented way to tell
                     a plain HTML checkbox from "not part of this form":
                     PageBean.showInNav defaults to true (matching
                     WeblogPage's own default), so a browser leaving the box
                     unchecked submits no "bean.showInNav" param at all, and
                     without the marker WebDataBinder would fall back to that
                     true default -- meaning nav could be turned on but never
                     off. With the marker present, WebDataBinder treats a
                     missing real value as an explicit false.

                     The marker name is deliberately "_showInNav", NOT
                     "_bean.showInNav" -- BaseController#initBeanBinder sets
                     the binder's *field-default* prefix to "bean." so plain
                     "bean.xxx" params bind by their Struts2-style name, and
                     that rewrite (checkFieldDefaults) runs before the
                     *field-marker* pass (checkFieldMarkers) and only ever
                     touches params that literally start with "bean.". A
                     marker named "_bean.showInNav" starts with "_", not
                     "bean.", so checkFieldDefaults leaves it untouched;
                     checkFieldMarkers then strips only the "_" and looks for
                     a writable property named "bean.showInNav", which does
                     not exist on PageBean, so the marker was silently
                     discarded and an unchecked box never took effect. The
                     marker must name the bean's real property path
                     ("showInNav"), the same path checkFieldDefaults produces
                     for the checked case, not the raw submitted name. --%>
                <input type="hidden" name="_showInNav" value="on"/>
                <label class="form-check-label">
                    <input type="checkbox" class="form-check-input" name="bean.showInNav" value="true" ${bean.showInNav ? 'checked' : ''}/>
                    <spring:message code="weblogPagesForm.showInNav"/>
                </label>
            </div>

            <label class="editor-field-label" for="page_bean_navOrder"><spring:message code="weblogPagesForm.navOrder"/></label>
            <input type="number" id="page_bean_navOrder" name="bean.navOrder" value="${bean.navOrder}" min="0" class="form-control"/>
            <div class="form-text"><spring:message code="weblogPagesForm.navOrder.tip"/></div>
        </div>

        <%-- SEO and social sharing: the same card the entry editor carries,
             behind the same quiet drawer. Field ids/names and the picker JS
             are a browser-test contract -- do not rename. --%>
        <div class="editor-box">
            <a class="editor-drawer collapsed" data-bs-toggle="collapse" data-bs-target="#collapseSeo" href="#">
                <spring:message code="weblogEdit.seoSettings"/>
            </a>
            <div id="collapseSeo" class="collapse">
                <div class="editor-drawer-body">

                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label" for="seo_metaTitle"><spring:message code="weblogEdit.metaTitle"/></label>
                        <div class="col-sm-9">
                            <input type="text" id="seo_metaTitle" name="bean.metaTitle" value="${fn:escapeXml(bean.metaTitle)}" maxlength="255" class="form-control"/>
                        </div>
                    </div>

                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label" for="seo_metaDescription"><spring:message code="weblogEdit.metaDescription"/></label>
                        <div class="col-sm-9">
                            <input type="text" id="seo_metaDescription" name="bean.searchDescription" value="${fn:escapeXml(bean.searchDescription)}" maxlength="255" class="form-control"/>
                        </div>
                    </div>

                    <div class="row mb-3" role="group" aria-labelledby="seo_ogImage_label">
                        <span class="col-sm-3 col-form-label" id="seo_ogImage_label"><spring:message code="weblogEdit.ogImage"/></span>
                        <div class="col-sm-9">
                            <input type="hidden" id="seo_ogImageId" name="bean.ogImageId" value="${fn:escapeXml(bean.ogImageId)}"/>
                            <div class="mb-2">
                                <img id="seo_ogImage_preview" src="${ogImageThumbnailUrl}" alt=""
                                     style="max-height:120px;${empty ogImageThumbnailUrl ? 'display:none;' : ''}"/>
                            </div>
                            <button type="button" class="btn btn-secondary btn-sm" onclick="openImagePicker('ogImage')"><spring:message code="weblogEdit.chooseImage"/></button>
                            <button type="button" class="btn btn-outline-danger btn-sm" id="seo_ogImage_clear"
                                    style="${empty bean.ogImageId ? 'display:none;' : ''}"
                                    onclick="clearPickedImage('ogImage')"><spring:message code="weblogEdit.clearImage"/></button>
                        </div>
                    </div>

                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label" for="seo_canonicalUrl"><spring:message code="weblogEdit.canonicalUrl"/></label>
                        <div class="col-sm-9">
                            <input type="text" id="seo_canonicalUrl" name="bean.canonicalUrl" value="${fn:escapeXml(bean.canonicalUrl)}" maxlength="255" class="form-control"/>
                        </div>
                    </div>

                    <div class="row mb-3">
                        <div class="offset-sm-3 col-sm-9">
                            <div class="form-check">
                                <label class="form-check-label">
                                    <input type="checkbox" class="form-check-input" id="seo_noindex" name="bean.noindex" value="true" ${bean.noindex ? 'checked' : ''}/>
                                    <spring:message code="weblogEdit.noindex"/>
                                </label>
                            </div>
                        </div>
                    </div>

                </div>
            </div>
        </div>

        <c:if test="${not empty bean.id}">
            <%-- delete: a quiet text link, not a red button. id/title ride in
                 data-* attributes rather than an interpolated onclick string --
                 fn:escapeXml renders an apostrophe as &#039;, which the HTML
                 parser decodes back to ' BEFORE the onclick attribute compiles
                 as JavaScript, so a page titled e.g. "Maiia's bio" made this
                 control a permanent SyntaxError. See the delegated handler
                 below (same convention as MediaFileView.jsp:493). --%>
            <button type="button" id="deletePageButton" class="delete-link"
                    data-page-id="${fn:escapeXml(bean.id)}" data-page-title="${fn:escapeXml(bean.title)}"
                    aria-label="<spring:message code='generic.delete'/>: ${fn:escapeXml(bean.title)}"><spring:message code="generic.delete"/></button>
        </c:if>

    </div>

    <sec:csrfInput/>
</form>

</div><%-- /editor-grid --%>

<%-- ====================================================================== --%>
<%-- Delete confirmation. Outside the edit form: it is its own POST with its
     own CSRF token, and forms must not nest. --%>

<div id="delete-page-modal" class="modal" tabindex="-1" role="dialog" aria-modal="true" aria-labelledby="delete-page-modal-title">
    <div class="modal-dialog">
        <div class="modal-content">
            <form action="${pageContext.request.contextPath}/pressroom-ui/authoring/pageRemove.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
                <input type="hidden" name="removeId" value="" id="page-delete-id"/>
                <div class="modal-header">
                    <p id="delete-page-modal-title" class="modal-title"><spring:message code="generic.delete"/>: <span id="page-delete-title"></span></p>
                </div>
                <%-- Dismiss first, destructive last: Bootstrap packs a
                     .modal-footer left-to-right in DOM order, so the reading
                     order IS the markup order (task B7). --%>
                <div class="modal-footer">
                    <button type="button" class="btn btn-secondary" data-bs-dismiss="modal"><spring:message code="generic.cancel"/></button>
                    <button type="submit" class="btn btn-danger"><spring:message code="generic.delete"/></button>
                </div>
                <sec:csrfInput/>
            </form>
        </div>
    </div>
</div>

<%-- ====================================================================== --%>

<jsp:include page="/WEB-INF/jsps/editor/EditorScript.jsp"/>

<script>

    <%-- This page has one Save button, so save and publish are the same
         action -- the same collapse the editor's keydown handling makes.
         Declared at script top level, not inside ready(): EditorScript.jsp
         reads both by name when it mounts the editor, which happens on
         ready. --%>
    function pressroomSavePage() {
        var button = document.getElementById('pageSaveButton');
        if (button) {
            button.click();
        }
    }

    var pressroomSaveDraft = pressroomSavePage;
    var pressroomPublish = pressroomSavePage;

    $(document).ready(function () {

        <%-- Bound once, tracking a dirty flag -- the same fix as
             EntryEditor.jsp, where registering both handlers inside the change
             callback left one submit handler per keystroke on the form about
             to be posted. --%>
        var pressroomPageDirty = false;
        pressroomEditorChangeListeners.push(function () {
            pressroomPageDirty = true;
        });
        $("#pageEditForm").on('input change', function () {
            pressroomPageDirty = true;
        });
        <%-- Namespaced, same reason as EntryEditor.jsp: a bare
             .off("beforeunload") unbinds every handler on the page. --%>
        $(window).on("beforeunload.pressroomLeaveWarning", function (event) {
            if (!pressroomPageDirty) {
                return undefined;
            }
            if (event.originalEvent) {
                event.originalEvent.returnValue = "<spring:message code='weblogEdit.leaveWarning' javaScriptEscape='true'/>";
            }
            return "<spring:message code='weblogEdit.leaveWarning' javaScriptEscape='true'/>";
        });
        $("#pageEditForm").on('submit', function () {
            pressroomPageDirty = false;
            $(window).off("beforeunload.pressroomLeaveWarning");
        });

        if (window.pressroomDraft) {
            window.pressroomDraft.install({
                form: document.getElementById('pageEditForm'),
                key: '${fn:escapeXml(draftKey)}',
                staleKeys: ['${draftNewKey}'],
                bar: document.getElementById('draftRecoveryBar'),
                csrfName: '${_csrf.parameterName}',
                <%-- The page editor's textarea is bean.content, not bean.text.
                     bean.status is deliberately NOT excluded here: it is a
                     visible <select> the author sets and PageBean.copyTo
                     writes it straight through, so it is real content. --%>
                exclude: ['bean.content'],
                getText: pressroomGetEntryText,
                setText: pressroomSetEntryText,
                onEditorChange: function (callback) {
                    pressroomEditorChangeListeners.push(callback);
                }
            });
        }

        <%-- pressroom-draft.js dispatches this on the bar element right after it
             writes a snapshot to localStorage. "Only if still dirty" matters
             at submit time: this page's own submit handler above clears the
             flag BEFORE pressroom-draft.js's runs save() and dispatches -- so a
             real save does not flash "Draft saved locally" a moment before the
             page reloads to "Saved". --%>
        var draftBarForStatus = document.getElementById('draftRecoveryBar');
        if (draftBarForStatus) {
            draftBarForStatus.addEventListener('pressroom-draft:saved', function () {
                if (pressroomPageDirty) {
                    pressroomSetSaveState('savedLocally');
                }
            });
        }
    });

    function showPageDeleteModal(pageId, pageTitle) {
        $('#page-delete-id').val(pageId);
        $('#page-delete-title').text(pageTitle);
        bootstrap.Modal.getOrCreateInstance(document.getElementById('delete-page-modal')).show();
    }

    <%-- The delete button only renders once the page has an id (see the
         c:if above), so this reads the id/title off it directly rather than
         from a form-level dataset lookup. --%>
    var deletePageButton = document.getElementById('deletePageButton');
    if (deletePageButton) {
        deletePageButton.addEventListener('click', function () {
            showPageDeleteModal(this.dataset.pageId, this.dataset.pageTitle);
        });
    }

</script>
