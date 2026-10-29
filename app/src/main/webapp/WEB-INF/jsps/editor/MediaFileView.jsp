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

<form id="createPostForm" method="post">
<input type="hidden" name="weblog" value='${actionWeblog.handle}'/>
    <input type="hidden" name="selectedImage" id="selectedImage"/>
    <input type="hidden" name="type" id="type"/>
<sec:csrfInput/>
</form>


<%-- ********************************************************************* --%>

<%-- Subtitle and folder path --%>

<c:choose>
<c:when test='${currentDirectory.name.equals("default")}'>

    <p class="subtitle">
        <spring:message code="mediaFileView.subtitle" arguments="${actionWeblog.handle}"/>
    </p>
    <p class="pagetip">
        <spring:message code="mediaFileView.rootPageTip"/>
    </p>

</c:when>
<c:when test='${not empty pager}'>

    <p class="subtitle">
        <spring:message code="mediaFileView.searchTitle"/>
    </p>
    <%-- Result count and the criteria that produced it. A zero-result search
         no longer says its piece here: it gets the .empty-state invitation
         below the form instead, the same shape as the folder-empty path, so
         the two ways this page can come back with nothing look alike. The
         criteria list is a sibling of the paragraph, not a child -- a <ul>
         inside a <p> closes the <p> in every browser anyway. --%>
    <p class="pagetip">
        <c:if test="${fn:length(pager.items) > 0}">
            <spring:message code="mediaFileView.matchingResults" arguments="${fn:length(pager.items)}"/>
        </c:if>
        <spring:message code="mediaFileView.searchInfo"/>
    </p>

    <ul>
        <c:if test="${not empty bean.name}">
            <li>
                <spring:message code="mediaFileView.filesNamed" arguments="${[fn:escapeXml(bean.name)]}"/>
            </li>
        </c:if>
        <c:if test="${bean.size > 0}">
            <li>
                <spring:message code="mediaFileView.filesOfSize" arguments="${bean.sizeFilterTypeLabel},${bean.size},${bean.sizeUnitLabel}"/>
            </li>
        </c:if>
        <c:if test="${not empty bean.type}">
            <li>
                <spring:message code="mediaFileView.filesOfType" arguments="${bean.typeLabel}"/>
            </li>
        </c:if>
        <c:if test="${not empty bean.tags}">
            <li>
                <spring:message code="mediaFileView.filesTagged" arguments="${[fn:escapeXml(bean.tags)]}"/>
            </li>
        </c:if>
    </ul>

</c:when>

<c:otherwise>

    <p class="subtitle">
        <spring:message code="mediaFileView.folderName"/>: ${fn:escapeXml(currentDirectory.name)}
    </p>
    <p class="pagetip">
        <spring:message code="mediaFileView.dirPageTip"/>
    </p>

</c:otherwise>
</c:choose>

<%-- The form renders for every folder view, empty or not, and for a search
     that found something. A zero-result search has its own invitation below.
     It used to render only when the folder had files, so an empty folder --
     the one most worth deleting -- lost both the folder switcher and
     "Delete folder", and the rail link was the only way out. --%>
<c:if test="${empty pager || fn:length(pager.items) > 0}">

    <form id="mediaFileViewForm" name="mediaFileViewForm" action="${pageContext.request.contextPath}/pressroom-ui/authoring/mediaFileView.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
        <input type="hidden" name="directoryId" value="${directoryId}"/>
        <input type="hidden" name="newDirectoryName" value="${fn:escapeXml(newDirectoryName)}"/>
        <input type="hidden" name="mediaFileId" value=""/>

        <%-- Labels above their fields, as on every other admin form. --%>
        <div class="media-view-controls d-flex flex-wrap gap-3 mb-3">

            <c:if test="${not empty allDirectories}">
                <div>
                    <label for="viewDirectoryMenu" class="form-label"><spring:message code="mediaFileView.viewFolder"/></label>
                    <select name="viewDirectoryId" id="viewDirectoryMenu" class="form-select" onchange="onView()">
<c:forEach items="${allDirectories}" var="opt">
<option value="${opt.id}" ${opt.id == viewDirectoryId ? 'selected' : ''}>${fn:escapeXml(opt.name)}</option>
</c:forEach>
</select>
                </div>
            </c:if>

            <%-- Not offered over a search result: submitting this form
                 re-runs the plain directory view and drops the search
                 silently, so the control appeared to do nothing but reset.
                 Nor in an empty folder, where there is nothing to sort. --%>
            <c:if test="${empty pager && not empty childFiles}">
                <div>
                    <label for="sortByMenu" class="form-label"><spring:message code="mediaFileView.sortBy"/></label>
                    <select name="sortBy" id="sortByMenu" class="form-select" onchange="document.mediaFileViewForm.submit();">
<c:forEach items="${sortOptions}" var="opt">
<option value="${opt.key}" ${opt.key == sortBy ? 'selected' : ''}>${opt.value}</option>
</c:forEach>
</select>
                </div>
            </c:if>

        </div>

        <c:choose>
        <c:when test="${empty pager && empty childFiles}">

            <%-- Empty state: no search in progress and this folder has no
                 files. Under the folder switcher, so the author can still
                 move to another folder or delete this one. --%>
            <div class="empty-state">
                <p class="empty-state-title"><spring:message code="empty.media.title"/></p>
                <p class="empty-state-body"><spring:message code="empty.media.body"/></p>
                <c:url var="emptyMediaAddUrl" value="/pressroom-ui/authoring/mediaFileAdd.rol">
                    <c:param name="weblog" value="${actionWeblog.handle}"/>
                    <c:param name="directoryName" value="${directoryName}"/>
                </c:url>
                <a href="${emptyMediaAddUrl}" class="btn btn-primary">
                    <spring:message code="empty.media.action"/>
                </a>
            </div>

        </c:when>
        <c:otherwise>

        <%-- ***************************************************************** --%>

        <%-- Media file folder contents --%>

        <script>
            function highlight(el, flag) {
                if (flag) {
                    $(el).addClass("highlight");
                } else {
                    $(el).removeClass("highlight");
                }
            }
        </script>

        <div id="imageGrid" class="card">
            <div class="card-body">

                <ul>

                        <c:choose>
<c:when test="${empty pager}">

                            <%-- ----------------------------------------------------- --%>

                            <%-- NOT SEARCH RESULTS --%>

                            <%-- List media files --%>

                            <c:forEach items="${childFiles}" var="mediaFile">

                                <li class="align-images">

                                    <%-- id/name ride in data-* attributes, not an inline onclick built by
                                         string concatenation. fn:escapeXml here escapes for the
                                         ATTRIBUTE (the same call this file already makes for the alt/
                                         title attributes below), and the browser hands the exact
                                         original string back through dataset -- no JS string literal is
                                         ever built from author-controlled text, which is what broke
                                         before: the old onclick="onClickEdit('id','name')" fed
                                         fn:escapeXml's output into a JS string literal, and
                                         fn:escapeXml renders an apostrophe as &#039;, which the HTML
                                         parser decodes back to ' BEFORE that onclick attribute is
                                         compiled as JavaScript -- so a filename containing one (e.g.
                                         "Maiia's portrait.jpg") produced a syntax error and made the
                                         tile permanently unclickable, the only route to the alt-text
                                         field. See the click binding below (same data-attribute
                                         convention as EntryEditor.jsp/PageEdit.jsp's data-snippet). --%>
                                    <div class="mediaObject" role="button" tabindex="0"
                                         aria-label="${fn:escapeXml(mediaFile.name)}"
                                         data-media-file-id="${mediaFile.id}"
                                         data-media-file-name="${fn:escapeXml(mediaFile.name)}">

                                        <c:choose>
<c:when test="${mediaFile.imageFile}">
                                            <img src='${urls.mediaThumbnail(mediaFile)}'
                                                 width='${mediaFile.thumbnailWidth}'
                                                 height='${mediaFile.thumbnailHeight}'
                                                 title='${fn:escapeXml(mediaFile.name)}'
                                                 alt='${fn:escapeXml(mediaFile.name)}' />
                                        </c:when>
<c:otherwise>
                                            <span class="bi bi-file-earmark fs-1" aria-hidden="true"></span>
                                        </c:otherwise>
                                        </c:choose>
                                    </div>

                                    <div class="mediaObjectInfo">

                                        <input type="checkbox" class="form-check-input"
                                               name="selectedMediaFiles"
                                               value="${mediaFile.id}"
                                               aria-label="${fn:escapeXml(mediaFile.name)}"/>

                                        ${fn:escapeXml(mediaFile.name)}

                                        <c:if test="${mediaFile.imageFile and empty fn:trim(mediaFile.altText)}">
                                            <button type="button" class="media-alt-missing" data-alt-fix
                                                    data-media-file-id="${mediaFile.id}"
                                                    data-media-file-name="${fn:escapeXml(mediaFile.name)}"
                                                    title="<spring:message code='mediaFileView.altMissing.tip'/>"><spring:message code="mediaFileView.altMissing"/></button>
                                        </c:if>

                                    </div>

                                </li>

                            </c:forEach>

                        </c:when>
<c:otherwise>

                            <%-- ----------------------------------------------------- --%>

                            <%-- SEARCH RESULTS --%>

                            <c:forEach items="${pager.items}" var="mediaFile">

                                <li class="align-images">

                                    <div class="mediaObject" role="button" tabindex="0"
                                         aria-label="${fn:escapeXml(mediaFile.name)}"
                                         data-media-file-id="${mediaFile.id}"
                                         data-media-file-name="${fn:escapeXml(mediaFile.name)}">

                                        <c:choose>
<c:when test="${mediaFile.imageFile}">
                                            <img src='${urls.mediaThumbnail(mediaFile)}'
                                                 width='${mediaFile.thumbnailWidth}'
                                                 height='${mediaFile.thumbnailHeight}'
                                                 title='${fn:escapeXml(mediaFile.name)}'
                                                 alt='${fn:escapeXml(mediaFile.name)}'/>
                                        </c:when>
<c:otherwise>
                                            <span class="bi bi-file-earmark fs-1" aria-hidden="true"></span>
                                        </c:otherwise>
</c:choose></div>

                                    <div class="mediaObjectInfo">

                                        <input type="checkbox" class="form-check-input"
                                               name="selectedMediaFiles"
                                               value="${mediaFile.id}"
                                               aria-label="${fn:escapeXml(mediaFile.name)}"/>

                                        ${fn:escapeXml(mediaFile.name)}

                                        <c:if test="${mediaFile.imageFile and empty fn:trim(mediaFile.altText)}">
                                            <button type="button" class="media-alt-missing" data-alt-fix
                                                    data-media-file-id="${mediaFile.id}"
                                                    data-media-file-name="${fn:escapeXml(mediaFile.name)}"
                                                    title="<spring:message code='mediaFileView.altMissing.tip'/>"><spring:message code="mediaFileView.altMissing"/></button>
                                        </c:if>

                                    </div>

                                </li>

                            </c:forEach>

                        </c:otherwise>
</c:choose></ul>

            </div>
        </div>

        <div style="clear:left;"></div>

            <%-- The selection bar: hidden until pressroom.js's delegated checkbox
                 handler finds a checked box in #mediaFileViewForm. Toggle,
                 move (with its target folder) and "new entry" are secondary;
                 delete is last because it is the one destructive control.
                 There is no folder-level select-all checkbox on this page --
                 Toggle is a button that flips every row via toggleFunction(),
                 not a header checkbox, so onToggle() below fires the recount
                 by hand once it is done. --%>
                <div class="selection-bar" data-selection-bar="mediaFileViewForm" hidden>
                    <span class="selection-count" data-template="<spring:message code='selection.count'/>"></span>

                    <button id="toggleButton" type="button" class="btn btn-secondary"
                            onclick="onToggle()"><spring:message code="generic.toggle"/></button>

                    <button id="moveButton" type="button" class="btn btn-secondary"
                            onclick="onMoveSelected()"><spring:message code="mediaFileView.moveSelected"/></button>

                    <label for="moveTargetMenu" class="visually-hidden"><spring:message code="mediaFileView.moveSelected"/></label>
                    <select name="selectedDirectory" id="moveTargetMenu" class="form-select d-inline-block">
<c:forEach items="${allDirectories}" var="opt">
<option value="${opt.id}" ${opt.id == selectedDirectory ? 'selected' : ''}>${fn:escapeXml(opt.name)}</option>
</c:forEach>
</select>

                    <%-- The one route from "these photos" to "a post about
                         these photos". #createPostForm has sat below,
                         reachable by nothing, since the control that used to
                         drive it was removed. The site space has no posts
                         (its media serve the site's pages), so not there. --%>
                    <c:if test="${not actionWeblog.siteSpace}">
                    <button id="newEntryButton" type="button" class="btn btn-secondary"
                            onclick="onNewEntryWithSelected()"><spring:message code="mediaFileView.newEntryWithSelected"/></button>
                    </c:if>

                    <button id="deleteButton" type="button" class="btn btn-danger"
                            onclick="onDeleteSelected()"
                            data-confirm="<spring:message code='mediaFile.delete.confirm'/>"><spring:message code="mediaFileView.deleteSelected"/></button>
                </div>

        </c:otherwise>
        </c:choose>

        <%-- Folder deletion needs no selection, so it sits outside the
             selection bar entirely, and outside the has-files branch: an
             empty folder is deletable too. --%>
        <c:if test="${not empty currentDirectory && currentDirectory.name != 'default' && empty pager}">
            <div class="d-flex flex-wrap gap-2 align-items-center mb-3">
                <button id="deleteFolderButton" type="button" class="btn btn-secondary"
                        onclick="onDeleteFolder()"
                        data-confirm="<spring:message code='mediaFile.deleteFolder.confirm'/>"><spring:message code="mediaFileView.deleteFolder"/></button>
            </div>
        </c:if>

    <sec:csrfInput/>
</form>

</c:if>

<%-- Empty state, search branch: a search ran and matched nothing. Same shape
     as the folder-empty invitation inside the form above. The action clears
     the search by reloading the page without the query, which is the only
     way back to the whole library from here. --%>
<c:if test="${not empty pager && fn:length(pager.items) == 0}">
    <div class="empty-state">
        <p class="empty-state-title"><spring:message code="empty.mediaSearch.title"/></p>
        <p class="empty-state-body"><spring:message code="mediaFileView.noResults"/></p>
        <c:url var="emptySearchResetUrl" value="/pressroom-ui/authoring/mediaFileView.rol">
            <c:param name="weblog" value="${actionWeblog.handle}"/>
        </c:url>
        <a href="${emptySearchResetUrl}" class="btn btn-primary">
            <spring:message code="empty.mediaSearch.action"/>
        </a>
    </div>
</c:if>


<%-- ================================================================================================ --%>

<%-- private-directory toggle for the current directory: excluded from public
     pages, feeds, the sitemap and [gallery] when checked. Sits outside the
     main media form because this is a separate POST action with its own
     CSRF input. --%>

<c:if test="${not empty currentDirectory && empty pager}">
    <div id="directoryPrivacyCard" class="card mt-3">
        <div class="card-header"><spring:message code="mediaFile.privacy.title"/></div>
        <div class="card-body">

            <form method="post"
                  action="${pageContext.request.contextPath}/pressroom-ui/authoring/mediaFileView!togglePrivate.rol">
                <input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
                <input type="hidden" name="directoryId" value="${directoryId}"/>
                <div class="form-check">
                    <input type="checkbox" class="form-check-input" id="directoryPrivateToggle"
                           onchange="this.form.submit()" ${currentDirectory['private'] ? 'checked' : ''}/>
                    <label class="form-check-label" for="directoryPrivateToggle"><spring:message
                            code="mediaFile.directoryPrivate"/></label>
                </div>
                <sec:csrfInput/>
            </form>

        </div>
    </div>
</c:if>


<%-- ================================================================================================ --%>

<%-- view image modal --%>

<div id="mediafile_edit_lightbox" class="modal" tabindex="-1" role="dialog" aria-modal="true" aria-labelledby="mediafile-edit-lightbox-title">

    <div class="modal-dialog modal-lg">

        <div class="modal-content">

            <div class="modal-header">
                <p id="mediafile-edit-lightbox-title" class="modal-title">
                    <spring:message code="mediaFileEdit.subtitle"/><b><span id="edit-subtitle"></span></b>
                </p>
            </div>

            <div class="modal-body">
                <iframe id="mediaFileEditor"
                        style="visibility:inherit"
                        height="700" <%-- pixels, sigh, this is suboptimal--%>
                        width="100%"
                        frameborder="no"
                        scrolling="auto">
                </iframe>
            </div>

            <div class="modal-footer"></div>

        </div>
    </div>

</div>


<script>
    toggleState = 'Off';

    function onClickEdit(mediaFileId, mediaFileName) {
        <c:url var="mediaFileEditURL" value="/pressroom-ui/authoring/mediaFileEdit.rol">
        <c:param name="weblog" value="${actionWeblog.handle}"/>
        </c:url>
        $('#edit-subtitle').text(mediaFileName);
        $('#mediaFileEditor').attr('src', '${mediaFileEditURL}' + '&mediaFileId=' + mediaFileId);
        bootstrap.Modal.getOrCreateInstance(document.getElementById('mediafile_edit_lightbox')).show();
    }

    function onEditSuccess() {
        onEditCancelled();
        document.mediaFileViewForm.submit();
    }

    function onEditCancelled() {
        bootstrap.Modal.getOrCreateInstance(document.getElementById('mediafile_edit_lightbox')).hide();
        $("#mediaFileEditor").attr('src', 'about:blank');
    }

    function onToggle() {
        if (toggleState === 'Off') {
            toggleState = 'On';
            toggleFunction(true, 'selectedMediaFiles');
            $("#deleteButton").attr('disabled', false);
            $("#moveButton").attr('disabled', false);
            $("#newEntryButton").attr('disabled', false);
            $("#moveTargetMenu").attr('disabled', false);
        } else {
            toggleState = 'Off';
            toggleFunction(false, 'selectedMediaFiles');
            $("#deleteButton").attr('disabled', true);
            $("#moveButton").attr('disabled', true);
            $("#newEntryButton").attr('disabled', true);
            $("#moveTargetMenu").attr('disabled', true);
        }
        <%-- toggleFunction() sets each checkbox's .checked directly and fires
             no 'change' event of its own, so the selection bar in pressroom.js
             -- which only recounts in response to a real checkbox change --
             would never notice a Toggle click. Dispatch one bubbling change
             event on any single row; the delegated handler recounts every
             checkbox in the form, not just the one that dispatched it. --%>
        var toggledCheckbox = document.querySelector("input[name='selectedMediaFiles']");
        if (toggledCheckbox) {
            toggledCheckbox.dispatchEvent(new Event('change', {bubbles: true}));
        }
    }

    // Confirmation is handled upstream now, by data-confirm on each
    // triggering button (see pressroom.js): a click that fails to confirm never
    // reaches here.
    function onDeleteSelected() {
        document.mediaFileViewForm.action = '<c:url value="/pressroom-ui/authoring/mediaFileView!deleteSelected.rol"/>';
        document.mediaFileViewForm.submit();
    }

    function onDeleteFolder() {
        document.mediaFileViewForm.action = '<c:url value="/pressroom-ui/authoring/mediaFileView!deleteFolder.rol"/>';
        document.mediaFileViewForm.submit();
    }

    function onMoveSelected() {
        <%-- No confirm here on purpose: a move is reversible and loses nothing,
             and confirming it trains people to click through the two dialogs
             that do matter (delete file, delete folder). The confirm message it
             used was deleted from all eight bundles along with this call --
             deliberately not named here, since the orphan ratchet counts a bare
             mention in a comment as a live reference. --%>
        document.mediaFileViewForm.action = '<c:url value="/pressroom-ui/authoring/mediaFileView!moveSelected.rol"/>';
        document.mediaFileViewForm.submit();
    }

    function onView() {
        document.mediaFileViewForm.action = "<c:url value='/pressroom-ui/authoring/mediaFileView!view.rol'/>";
        document.mediaFileViewForm.submit();
    }

    <%-- Delegated on the grid container rather than bound per-tile: a tile's
         id/name ride in data-* attributes (see the c:out comment above each
         loop), never in an inline onclick string, so a filename carrying an
         apostrophe cannot break the handler the way it did when the id/name
         were concatenated into onclick="onClickEdit('...','...')". --%>
    $(document).on('click', '.mediaObject', function () {
        onClickEdit(this.dataset.mediaFileId, this.dataset.mediaFileName);
    });

    <%-- A tile is role="button" with tabindex="0", so a keyboard user can
         reach it; Enter and Space activate it the way they activate a real
         button, and focus shows the same highlight as hover. --%>
    $(document).on('keydown', '.mediaObject', function (event) {
        if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            this.click();
        }
    });

    $(document).on('focusin focusout', '.mediaObject', function (event) {
        highlight(this.parentNode, event.type === 'focusin');
    });

    <%-- Hover is delegated from here, never an inline onmouseover: an inline
         handler can fire before this script has defined highlight (a pointer
         already resting on the overlay as it loads threw a ReferenceError). --%>
    $(document).on('mouseenter mouseleave', '.align-images', function (event) {
        highlight(this, event.type === 'mouseenter');
    });

    <%-- The alt-text marker opens the same editor. stopPropagation keeps the
         enclosing .mediaObject handler from firing a second time for the same
         click. --%>
    $(document).on('click', '[data-alt-fix]', function (event) {
        event.stopPropagation();
        onClickEdit(this.dataset.mediaFileId, this.dataset.mediaFileName);
    });

    <%-- Hands the checked ids to entryAddWithMediaFile.rol, which seeds a
         draft with an [image id=".."] shortcode per file. The parameter name
         is selectedImages (plural, repeated) -- the pre-existing
         #createPostForm carries only the singular selectedImage, which that
         controller also accepts but only for one file, so the ids are added
         as fresh inputs rather than stuffed into it. Rebuilt on every click
         so a changed selection cannot leave stale ids behind. --%>
    function onNewEntryWithSelected() {
        var form = document.getElementById('createPostForm');
        form.querySelectorAll("input[name='selectedImages']").forEach(function (el) {
            el.remove();
        });
        var selected = document.querySelectorAll("input[name='selectedMediaFiles']:checked");
        if (selected.length === 0) {
            return;
        }
        selected.forEach(function (checkbox) {
            var hidden = document.createElement('input');
            hidden.type = 'hidden';
            hidden.name = 'selectedImages';
            hidden.value = checkbox.value;
            form.appendChild(hidden);
        });
        form.action = '<c:url value="/pressroom-ui/authoring/entryAddWithMediaFile.rol"/>';
        form.submit();
    }

    <%-- code to toggle buttons on/off as media file/directory selections change --%>

    $(document).ready(function () {
        $("#deleteButton").attr('disabled', true);
        $("#moveButton").attr('disabled', true);
        $("#newEntryButton").attr('disabled', true);
        $("#moveTargetMenu").attr('disabled', true);

        $("input[type=checkbox]").change(function () {
            var count = 0;
            $("input[type=checkbox]").each(function (index, element) {
                if (element.checked) count++;
            });
            if (count === 0) {
                $("#deleteButton").attr('disabled', true);
                $("#moveButton").attr('disabled', true);
                $("#newEntryButton").attr('disabled', true);
                $("#moveTargetMenu").attr('disabled', true);
            } else {
                $("#deleteButton").attr('disabled', false);
                $("#moveButton").attr('disabled', false);
                $("#newEntryButton").attr('disabled', false);
                $("#moveTargetMenu").attr('disabled', false);
            }
        });
    });

</script>