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

<p class="subtitle">
    <spring:message code="submissions.subtitle" arguments="${actionWeblog.handle}"/>
</p>

<%-- ============================================================= --%>
<%-- Next / previous links --%>

<%-- Same pager markup as Entries.jsp and Comments.jsp; pageSize comes from
     the controller, so the next-page test cannot drift from the query. --%>
<c:if test="${page > 0 or (page + 1) * pageSize < submissionCount}">
<nav aria-label="<spring:message code='pager.label'/>">
    <div class="d-flex justify-content-between">
        <c:if test="${page > 0}">
            <c:url var="prevUrl" value="/roller-ui/authoring/submissions.rol">
                <c:param name="weblog" value="${actionWeblog.handle}"/>
                <c:param name="page" value="${page - 1}"/>
            </c:url>
            <a href="${prevUrl}" class="btn btn-outline-secondary previous">
                <span aria-hidden="true">&larr;</span> <spring:message code="pager.newer"/></a>
        </c:if>
        <c:if test="${(page + 1) * pageSize < submissionCount}">
            <c:url var="nextUrl" value="/roller-ui/authoring/submissions.rol">
                <c:param name="weblog" value="${actionWeblog.handle}"/>
                <c:param name="page" value="${page + 1}"/>
            </c:url>
            <a href="${nextUrl}" class="btn btn-outline-secondary next ms-auto"><spring:message code="pager.older"/>
                <span aria-hidden="true">&rarr;</span></a>
        </c:if>
    </div>
</nav>
</c:if>

<%-- One form around the whole table, following the Entries.jsp/Pages.jsp
     pattern: the row checkboxes and the delete-selected button both post
     through it, rather than a form per row (which is not valid HTML).

     Every author-controlled field below -- name, email, subject, message,
     pageSlug/entryAnchor -- comes from an anonymous, unauthenticated visitor
     via ContactController, so each one is wrapped in <c:out>/fn:escapeXml,
     including inside the mailto href. --%>
<form id="submissionsDeleteForm" method="post"
      action="${pageContext.request.contextPath}/roller-ui/authoring/submissions!delete.rol">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <sec:csrfInput/>

    <c:choose>
    <c:when test="${not empty submissions}">

    <%-- The selection bar: hidden until roller.js's delegated checkbox
         handler finds a checked row in #submissionsDeleteForm. Inquiries are
         deleted permanently -- there is no trash for them -- so the one
         action here is destructive and the button opens the confirmation
         modal below rather than submitting directly. --%>
    <div class="selection-bar" data-selection-bar="submissionsDeleteForm" hidden>
        <span class="selection-count" data-template="<spring:message code='selection.count'/>"></span>
        <button type="button" class="btn btn-danger" id="submissionsDeleteSelected">
            <spring:message code="generic.delete.selected"/>
        </button>
    </div>

    <table class="rollertable table table-striped" width="100%">

            <tr>
                <th scope="col" class="rollertable" width="3%">
                    <input type="checkbox" id="selectAllSubmissions" class="form-check-input" data-select-all
                           title="<spring:message code="weblogEntryQuery.selectAll"/>"/>
                </th>
                <th scope="col" class="rollertable"><spring:message code="submissions.column.received"/></th>
                <th scope="col" class="rollertable"><spring:message code="submissions.column.from"/></th>
                <th scope="col" class="rollertable"><spring:message code="submissions.column.subject"/></th>
                <th scope="col" class="rollertable"><spring:message code="submissions.column.message"/></th>
                <th scope="col" class="rollertable"><spring:message code="submissions.column.source"/></th>
            </tr>

            <c:forEach items="${submissions}" var="s">
                <tr>
                    <td>
                        <input type="checkbox" class="form-check-input submission-select"
                               name="deleteIds" value="${fn:escapeXml(s.id)}"
                               aria-label="${fn:escapeXml(s.subject)}"/>
                    </td>
                    <td class="data"><rc:date value="${s.created}"/></td>
                    <td>
                        <c:out value="${s.name}"/><br/>
                        <a href="mailto:${fn:escapeXml(s.email)}"><c:out value="${s.email}"/></a>
                    </td>
                    <td><c:out value="${s.subject}"/></td>
                    <td>
                        <c:choose>
                        <c:when test="${fn:length(s.message) > 140}">
                            <c:out value="${fn:substring(s.message, 0, 140)}"/>&hellip;
                            <details>
                                <summary><spring:message code="submissions.showFullMessage"/></summary>
                                <p><c:out value="${s.message}"/></p>
                            </details>
                        </c:when>
                        <c:otherwise>
                            <c:out value="${s.message}"/>
                        </c:otherwise>
                        </c:choose>
                    </td>
                    <td>
                        <c:choose>
                        <c:when test="${not empty s.entryAnchor}">
                            <c:out value="${s.entryAnchor}"/>
                        </c:when>
                        <c:when test="${not empty s.pageSlug}">
                            /<c:out value="${s.pageSlug}"/>
                        </c:when>
                        <c:otherwise>&mdash;</c:otherwise>
                        </c:choose>
                    </td>
                </tr>
            </c:forEach>

    </table>

    </c:when>
    <c:otherwise>

    <div class="empty-state">
        <p class="empty-state-title"><spring:message code="empty.inquiries.title"/></p>
        <p class="empty-state-body"><spring:message code="empty.inquiries.body"/></p>
    </div>

    </c:otherwise>
    </c:choose>

</form>

<%-- Confirmation for the bulk delete, ported from Entries.jsp: a modal
     rather than window.confirm, because the native dialog cannot say how
     many inquiries are about to go. --%>
<div id="submissions-delete-modal" class="modal" tabindex="-1" role="dialog" aria-modal="true" aria-labelledby="submissions-delete-modal-title">
    <div class="modal-dialog">
        <div class="modal-content">
            <div class="modal-header">
                <p id="submissions-delete-modal-title" class="modal-title"><spring:message code="submissions.bulkDeleteConfirm"/></p>
            </div>
            <div class="modal-body">
                <p><spring:message code="submissions.bulkDeleteWarning"/></p>
                <p id="submissionsDeleteCount" class="data" data-template="<spring:message code='selection.count'/>"></p>
            </div>
            <div class="modal-footer">
                <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">
                    <spring:message code="generic.cancel"/>
                </button>
                <%-- Outside the form, so form= names the one carrying the
                     selection (same shape as Entries.jsp's confirm). --%>
                <button type="submit" class="btn btn-danger" id="submissionsDeleteConfirm"
                        form="submissionsDeleteForm">
                    <spring:message code="generic.delete"/>
                </button>
            </div>
        </div>
    </div>
</div>

<script>
    (function () {
        var selectAll = document.getElementById('selectAllSubmissions');
        if (!selectAll) {
            return;
        }
        var rows = function () {
            return document.querySelectorAll('.submission-select');
        };
        selectAll.addEventListener('change', function () {
            var checked = this.checked;
            rows().forEach(function (cb) {
                cb.checked = checked;
            });
        });

        // A row unchecked by hand must not leave the header claiming all are
        // selected -- that is the state that gets someone to delete more than
        // they meant to. Same sync Entries.jsp carries.
        rows().forEach(function (cb) {
            cb.addEventListener('change', function () {
                selectAll.checked = document.querySelectorAll('.submission-select:checked')
                        .length === rows().length;
            });
        });

        var trigger = document.getElementById('submissionsDeleteSelected');
        if (trigger) {
            trigger.addEventListener('click', function () {
                var count = document.getElementById('submissionsDeleteCount');
                count.textContent = count.dataset.template.replace('{0}',
                        document.querySelectorAll('.submission-select:checked').length);
                bootstrap.Modal.getOrCreateInstance(
                        document.getElementById('submissions-delete-modal')).show();
            });
        }
    })();
</script>
