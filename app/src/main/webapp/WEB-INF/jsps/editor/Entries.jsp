<!--
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
-->
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<p class="subtitle">
    <spring:message code="weblogEntryQuery.subtitle" arguments="${actionWeblog.handle}"/>
</p>
<p class="pagetip">
    <spring:message code="weblogEntryQuery.tip"/>
</p>


<%-- ============================================================= --%>
<%-- Next / previous links --%>

<%-- Rendered only when there is somewhere to go: an empty <nav> is still a
     landmark a screen reader announces. The same markup as the bottom
     pager, Submissions.jsp and Comments.jsp. --%>
<c:if test="${pager.prevLink != null or pager.nextLink != null}">
<nav aria-label="<spring:message code='pager.label'/>">
    <div class="d-flex justify-content-between">
        <c:if test="${pager.prevLink != null}">
            <a href="${fn:escapeXml(pager.prevLink)}" class="btn btn-outline-secondary previous">
                <span aria-hidden="true">&larr;</span> <spring:message code="pager.newer"/></a>
        </c:if>
        <c:if test="${pager.nextLink != null}">
            <a href="${fn:escapeXml(pager.nextLink)}" class="btn btn-outline-secondary next ms-auto"><spring:message code="pager.older"/>
                <span aria-hidden="true">&rarr;</span></a>
        </c:if>
    </div>
</nav>
</c:if>


<%-- ============================================================= --%>
<%-- Entry table--%>

<%-- Legend, table and bulk-action bar are all gated on there being entries
     to show -- an empty weblog must never render a bare table strip above
     the empty-state invitation below. The <form> itself stays unconditional
     (unchanged id/name) because the bulk-delete confirmation modal further
     down references #entriesBulkForm via form="..."; only its contents are
     conditional. --%>
<%-- One form around the whole table. The row checkboxes, the duplicate
     button and the bulk action bar all post through it, which is why the
     duplicate control is a submit button with its own formaction rather than
     a form of its own: a form nested inside another is not valid HTML and
     browsers drop the inner one. --%>
<%-- entries-list-marker wraps the list region in BOTH states (table or
     empty-state invitation): the browser ITs identify this page by
     "#entries-list-marker, table.rollertable", and gating the table on
     emptiness left an empty weblog's Entries page with neither match. --%>
<div id="entries-list-marker">
<form id="entriesBulkForm" method="post"
      action="${pageContext.request.contextPath}/roller-ui/authoring/entries!bulkPublish.rol">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
<sec:csrfInput/>

<%-- The filter, carried through the POST. The controller returns to the
     filtered list after a bulk action, but it can only do that from what the
     POST body binds -- and this form is separate from the GET filter form in
     the sidebar, so without these the bean arrives empty and the redirect
     lands on the unfiltered list of everything. bean.sortBy and bean.page are
     deliberately absent: sortBy has a non-null default that would pin
     whatever was showing, and returning to page 4 of a list that just got
     shorter is worse than returning to page 1. --%>
<c:if test="${not empty bean.status}">
    <input type="hidden" name="bean.status" value="${fn:escapeXml(bean.status)}"/>
</c:if>
<c:if test="${not empty bean.categoryName}">
    <input type="hidden" name="bean.categoryName" value="${fn:escapeXml(bean.categoryName)}"/>
</c:if>
<c:if test="${not empty bean.tagsAsString}">
    <input type="hidden" name="bean.tagsAsString" value="${fn:escapeXml(bean.tagsAsString)}"/>
</c:if>
<c:if test="${not empty bean.text}">
    <input type="hidden" name="bean.text" value="${fn:escapeXml(bean.text)}"/>
</c:if>
<c:if test="${not empty bean.startDateString}">
    <input type="hidden" name="bean.startDateString" value="${fn:escapeXml(bean.startDateString)}"/>
</c:if>
<c:if test="${not empty bean.endDateString}">
    <input type="hidden" name="bean.endDateString" value="${fn:escapeXml(bean.endDateString)}"/>
</c:if>

<%-- Status filter as link-chips, and the ONE status control on this screen --
     the sidebar's radio set is gone, because two controls for one filter is
     how a list ends up showing DRAFT while the sidebar claims ALL.

     The hrefs come from the controller (statusChipUrls), not from a <c:url>
     here. A chip has to carry the author's whole filter -- text, tags,
     category, date range, sort -- with only the status swapped, and listing
     those fields by hand in the JSP means the one that gets forgotten is
     dropped silently on every chip click. The controller builds them from
     the same filterParams the pager's base url and the bulk redirect use.

     fn:escapeXml on the href is not decoration: it is markup-injection
     defence for the attribute context this value is printed into.
     URLUtilities.getQueryString already url-encodes both the key and the
     value of every parameter it composes (see its javadoc, and CLAUDE.md's
     Admin UI note) -- a value like bean.text cannot break out of the query
     string on its own -- but the finished URL still lands inside an
     href="..." attribute, and fn:escapeXml is what keeps that attribute well
     formed regardless. The two encodings answer different questions:
     url-encoding protects the query string, fn:escapeXml protects the HTML
     attribute around it. --%>
<nav class="entries-status-chips d-flex flex-wrap gap-2 mb-3" aria-label="<spring:message code='weblogEdit.status'/>">
    <c:forEach items="${statusOptions}" var="opt">
        <%-- A blank bean.status means the same thing as ALL, so both mark
             the ALL chip -- otherwise the unfiltered default list shows no
             chip active at all. --%>
        <c:set var="chipActive" value="${opt.key == bean.status
                or (opt.key == 'ALL' and empty bean.status)}"/>
        <a class="btn btn-sm ${chipActive ? 'btn-secondary' : 'btn-outline-secondary'}"
           href="${fn:escapeXml(statusChipUrls[opt.key])}" ${chipActive ? 'aria-current="page"' : ''}>${opt.value}</a>
    </c:forEach>
</nav>

<%-- The selection bar: hidden until roller.js's delegated checkbox handler
     finds a checked row in #entriesBulkForm, then shows the count and these
     three actions. Publish/submit and the tag-add control are secondary here
     -- only one primary action lives on this screen at a time, and while the
     bar is showing that is the empty-state's Add link, not a bulk action --
     with delete last because it is the one destructive control. --%>
<c:if test="${not empty pager.items}">
    <div class="selection-bar" data-selection-bar="entriesBulkForm" hidden>
        <span class="selection-count" data-template="<spring:message code='selection.count'/>"></span>

        <button type="submit" class="btn btn-secondary"
                formaction="${pageContext.request.contextPath}/roller-ui/authoring/entries!bulkPublish.rol">
            <c:choose>
                <c:when test="${userAnAuthor}">
                    <spring:message code="weblogEntryQuery.bulkPublish"/>
                </c:when>
                <c:otherwise>
                    <spring:message code="weblogEntryQuery.bulkSubmit"/>
                </c:otherwise>
            </c:choose>
        </button>

        <div class="input-group" style="width: 22em">
            <input type="text" name="bulkTag" id="bulkTag" class="form-control"
                   placeholder="<spring:message code="weblogEntryQuery.bulkTagPlaceholder"/>"/>
            <button type="submit" class="btn btn-outline-secondary"
                    formaction="${pageContext.request.contextPath}/roller-ui/authoring/entries!bulkTag.rol">
                <spring:message code="weblogEntryQuery.bulkTagAdd"/>
            </button>
        </div>

        <button type="button" class="btn btn-danger" id="bulkDeleteButton">
            <spring:message code="weblogEntryQuery.bulkDelete"/>
        </button>
    </div>
</c:if>

<c:if test="${not empty pager.items}">
<div class="table-responsive">
<table class="rollertable table table-striped" width="100%">

<tr>
    <th scope="col" class="rollertable" width="3%">
        <input type="checkbox" id="selectAllEntries" class="form-check-input" data-select-all
               title="<spring:message code="weblogEntryQuery.selectAll"/>"/>
    </th>
    <th scope="col" class="rollertable" width="7%">
        <spring:message code="weblogEntryQuery.pubTime"/>
    </th>
    <th scope="col" class="rollertable" width="7%">
        <spring:message code="weblogEntryQuery.updateTime"/>
    </th>
    <th scope="col" class="rollertable">
        <spring:message code="weblogEntryQuery.title"/>
    </th>
    <th scope="col" class="rollertable" width="10%">
        <spring:message code="weblogEdit.status"/>
    </th>
    <th scope="col" class="rollertable" width="15%">
        <spring:message code="weblogEntryQuery.category"/>
    </th>
    <th scope="col" class="rollertable" width="3%"> </th>
    <th scope="col" class="rollertable" width="3%"> </th>
</tr>

<c:forEach items="${pager.items}" var="post">
    <tr>
    <td>
        <input type="checkbox" class="form-check-input entry-select"
               name="selectedEntries" value="${post.id}"
               aria-label="${fn:escapeXml(entryTitles[post.id])}"/>
    </td>

    <%-- <rc:date/> renders nothing at all for a null value and formats in
         the WEBLOG's timezone, which is the clock an entry's pubtime has
         always meant (see DateTag). The <c:if>s these cells used to carry
         and the message-bundle date pattern they used to resolve are both
         the tag's job now. --%>
    <td class="data text-nowrap"><rc:date value="${post.pubTime}"/></td>

    <td class="data text-nowrap"><rc:date value="${post.updateTime}"/></td>

    <%-- The title is the row's primary target and it opens the EDITOR. This
         is the authoring surface: clicking a post's name here means "open
         this to work on it". The published page is still one click away as
         the quiet secondary link below -- demoted, not lost -- and the
         pencil column that existed only to reach the editor is gone, since
         the title now does its whole job.

         entryTitles[post.id] is the controller's plain-text label
         (EntriesController.plainTitle), escaped here exactly once -- in this
         link and in every aria-label and data-* attribute below. Neither
         stored form will do: a title is stored HTML-escaped, so
         fn:escapeXml(post.title) renders &amp;amp; (and the delete modal,
         filled through .text(), showed "Tom &amp; Jerry"); and an untitled
         entry's displayTitle is its body with tags stripped, unescaped, so
         printing it bare was markup injection. post.anchor is machine-derived from the title
         (createAnchorBase strips every non-alphanumeric character) and so
         cannot carry markup today -- it is escaped anyway, because that
         property is an invariant of one method somewhere else, not of this
         page. --%>
    <td class="entry-cell">
        <c:url var="editUrl" value="/roller-ui/authoring/entryEdit.rol">
            <c:param name="weblog" value="${actionWeblog.handle}"/>
            <c:param name="bean.id" value="${post.id}"/>
        </c:url>
        <a class="entry-title" href="${editUrl}">${fn:escapeXml(entryTitles[post.id])}</a>
        <div class="entry-meta">
            <span class="data">${fn:escapeXml(post.anchor)}</span><c:if test="${post.status.name() == 'PUBLISHED'}"> &#183;
            <a class="quiet-link" href="${fn:escapeXml(urls.entry(post))}" target="_blank" rel="noopener"><spring:message code="generic.view"/></a></c:if>
        </div>
    </td>

    <%-- The status pill, not just a row tint: colour alone is not information
         a screen reader or a colour-blind reader receives. Same component as
         Pages.jsp. pillWhen must be explicitly cleared on the non-Scheduled
         branch -- request scope survives across this forEach's iterations,
         so a Scheduled row's pillWhen would otherwise leak onto every
         following row that has no pubTime of its own to show. --%>
    <td>
        <c:set var="pillStatus" value="${post.status.name()}" scope="request"/>
        <c:choose>
        <c:when test="${post.status.name() == 'SCHEDULED'}">
            <c:set var="pillWhen" value="${post.pubTime}" scope="request"/>
        </c:when>
        <c:otherwise>
            <c:remove var="pillWhen" scope="request"/>
        </c:otherwise>
        </c:choose>
        <jsp:include page="/WEB-INF/jsps/editor/StatusPill.jsp"/>
    </td>

    <td>
        ${fn:escapeXml(post.category.name)}
    </td>

    <td>
        <%-- A POST, not a link: duplicating writes a new draft, so it needs
             the CSRF token and must not be reachable by a prefetch or a
             crawler following hrefs. The clicked submit button is the only one
             whose name/value is sent, so duplicateId identifies this row
             without a hidden field per row. --%>
        <button type="submit" name="duplicateId" value="${post.id}"
                class="btn btn-link p-0 align-baseline border-0"
                aria-label="<spring:message code='generic.duplicate'/>: ${fn:escapeXml(entryTitles[post.id])}"
                formaction="${pageContext.request.contextPath}/roller-ui/authoring/entries!duplicate.rol">
            <span class="bi bi-files" aria-hidden="true"
                  title="<spring:message code="generic.duplicate"/>">
            </span>
        </button>
    </td>

    <td>
        <%-- id/title ride in data-* attributes, not an interpolated onclick
             string -- fn:escapeXml renders an apostrophe as &#039;, which the
             HTML parser decodes back to ' BEFORE the onclick attribute
             compiles as JavaScript, so an entry titled e.g. "Maiia's trip"
             made this control a permanent SyntaxError. Delegated handler
             below (same convention as MediaFileView.jsp:493). --%>
        <button type="button" class="btn btn-link p-0 align-baseline border-0 entry-delete-btn"
                data-entry-id="${post.id}" data-entry-title="${fn:escapeXml(entryTitles[post.id])}"
                aria-label="<spring:message code='generic.delete'/>: ${fn:escapeXml(entryTitles[post.id])}">
            <span class="bi bi-trash" aria-hidden="true"
                  title="<spring:message code="generic.delete"/>">
            </span>
        </button>
    </td>

    </tr>
</c:forEach>

</table>
</div>
</c:if>

</form>


<%-- ============================================================= --%>
<%-- Next / previous links --%>

<c:if test="${pager.prevLink != null or pager.nextLink != null}">
<nav aria-label="<spring:message code='pager.label'/>">
    <div class="d-flex justify-content-between">
        <c:if test="${pager.prevLink != null}">
            <a href="${fn:escapeXml(pager.prevLink)}" class="btn btn-outline-secondary previous">
                <span aria-hidden="true">&larr;</span> <spring:message code="pager.newer"/></a>
        </c:if>
        <c:if test="${pager.nextLink != null}">
            <a href="${fn:escapeXml(pager.nextLink)}" class="btn btn-outline-secondary next ms-auto"><spring:message code="pager.older"/>
                <span aria-hidden="true">&rarr;</span></a>
        </c:if>
    </div>
</nav>
</c:if>

<%-- Two empty states. A filter (status chip, category, tag, text, dates)
     that matches nothing is not an empty weblog, and inviting a first post
     there is wrong; the controller's "filtered" says which this is. Clear
     filters goes to the bare list. --%>
<c:if test="${empty pager.items}">
    <div class="empty-state">
        <c:choose>
        <c:when test="${filtered}">
            <p class="empty-state-title"><spring:message code="empty.entries.filtered.title"/></p>
            <p class="empty-state-body"><spring:message code="empty.entries.filtered.body"/></p>
            <c:url var="clearFiltersUrl" value="/roller-ui/authoring/entries.rol">
                <c:param name="weblog" value="${actionWeblog.handle}"/>
            </c:url>
            <a href="${clearFiltersUrl}" class="btn btn-secondary">
                <spring:message code="empty.entries.filtered.action"/>
            </a>
        </c:when>
        <c:otherwise>
            <p class="empty-state-title"><spring:message code="empty.entries.title"/></p>
            <p class="empty-state-body"><spring:message code="empty.entries.body"/></p>
            <c:url var="emptyEntriesAddUrl" value="/roller-ui/authoring/entryAdd.rol">
                <c:param name="weblog" value="${actionWeblog.handle}"/>
            </c:url>
            <a href="${emptyEntriesAddUrl}" class="btn btn-primary">
                <spring:message code="empty.entries.action"/>
            </a>
        </c:otherwise>
        </c:choose>
    </div>
</c:if>
</div>


<div id="delete-entry-modal" class="modal delete-entry-modal" tabindex="-1" role="dialog" aria-modal="true" aria-labelledby="delete-entry-modal-title">

    <div class="modal-dialog modal-lg">

        <div class="modal-content">

            <c:set var="deleteAction">entryRemoveViaList!remove</c:set>
            
            <form class="form-stacked" action="${pageContext.request.contextPath}/roller-ui/authoring/${deleteAction}.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
                <input type="hidden" name="removeId" value="${fn:escapeXml(removeId)}" id="removeId"/>

                <%-- One title in the header (a <p class="modal-title">, the
                     caps-label role); the entry's name and the sentence are
                     the body. The answers name the action. No entry-id row:
                     an internal UUID tells an author nothing. --%>
                <div class="modal-header">
                    <p id="delete-entry-modal-title" class="modal-title"><spring:message code="weblogEntryRemove.removeWeblogEntry"/></p>
                </div>

                <div class="modal-body">
                    <p><strong id="postTitleLabel"></strong></p>
                    <p><spring:message code="weblogEntryRemove.areYouSure"/></p>
                </div>

                <div class="modal-footer">
                    <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">
                        <spring:message code="generic.cancel"/>
                    </button>
                    <button type="submit" class="btn btn-danger"><spring:message code="weblogEntryRemove.moveToTrash"/></button>
                </div>

            <sec:csrfInput/>
</form>
            
        </div>

    </div> 
    
</div>

<%-- Confirmation for the bulk delete. A modal rather than window.confirm:
     the native dialog blocks the page for automated tests and cannot say
     how many entries are about to go. --%>
<div id="bulk-delete-modal" class="modal" tabindex="-1" role="dialog" aria-modal="true" aria-labelledby="bulk-delete-modal-title">
    <div class="modal-dialog">
        <div class="modal-content">
            <div class="modal-header">
                <p id="bulk-delete-modal-title" class="modal-title"><spring:message code="weblogEntryQuery.bulkDeleteConfirm"/></p>
            </div>
            <%-- The count is the selection bar's own "{0} selected" message,
                 filled in by the script below: one translatable string with
                 its placeholder, never a number glued onto a fragment. --%>
            <div class="modal-body">
                <p><spring:message code="weblogEntryQuery.bulkDeleteWarning"/></p>
                <p id="bulkDeleteCount" class="data" data-template="<spring:message code='selection.count'/>"></p>
            </div>
            <div class="modal-footer">
                <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">
                    <spring:message code="generic.cancel"/>
                </button>
                <%-- Submits the table's form, which is where the selection
                     lives; this button is outside it, hence the form= --%>
                <button type="submit" class="btn btn-danger" id="bulkDeleteConfirm"
                        form="entriesBulkForm"
                        formaction="${pageContext.request.contextPath}/roller-ui/authoring/entries!bulkDelete.rol">
                    <spring:message code="weblogEntryRemove.moveToTrash"/>
                </button>
            </div>
        </div>
    </div>
</div>

<script>
    function showDeleteModal( postId, postTitle ) {
        $('#postTitleLabel').text(postTitle);
        $('#removeId').val(postId);
        bootstrap.Modal.getOrCreateInstance(document.getElementById('delete-entry-modal')).show();
    }

    <%-- Delegated on the table body: a row's id/title ride in data-*
         attributes (see the comment above the button), never in an inline
         onclick string. --%>
    $(document).on('click', '.entry-delete-btn', function () {
        showDeleteModal(this.dataset.entryId, this.dataset.entryTitle);
    });

    $(function () {
        var selection = function () {
            return $(".entry-select:checked");
        };

        $("#selectAllEntries").on('change', function () {
            $(".entry-select").prop('checked', this.checked);
        });

        // A row unchecked by hand must not leave the header claiming all are
        // selected, which is the state that gets someone to delete more than
        // they meant to.
        $(".entry-select").on('change', function () {
            $("#selectAllEntries").prop('checked',
                    selection().length === $(".entry-select").length);
        });

        $("#bulkDeleteButton").on('click', function () {
            var count = document.getElementById('bulkDeleteCount');
            count.textContent = count.dataset.template.replace('{0}', selection().length);
            bootstrap.Modal.getOrCreateInstance(document.getElementById('bulk-delete-modal')).show();
        });
    });
</script>
