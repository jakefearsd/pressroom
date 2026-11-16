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
    <c:choose>
        <c:when test="${actionWeblog.siteSpace}"><spring:message code="pages.trash.subtitle.site"/></c:when>
        <c:otherwise><spring:message code="pages.trash.subtitle" arguments="${actionWeblog.handle}"/></c:otherwise>
    </c:choose>
</p>

<%-- How long a trashed page lasts, in the three cases the retention setting
     has, as Trash.jsp says it for entries. --%>
<p class="pagetip">
    <c:choose>
    <c:when test="${pageTrashRetentionDays lt 0}">
        <spring:message code="pages.trash.retention.forever"/>
    </c:when>
    <c:when test="${pageTrashRetentionDays eq 0}">
        <spring:message code="pages.trash.retention.nextSweep"/>
    </c:when>
    <c:otherwise>
        <spring:message code="pages.trash.retention.days" arguments="${pageTrashRetentionDays}"/>
    </c:otherwise>
    </c:choose>
</p>

<c:url var="pagesUrl" value="/pressroom-ui/authoring/pages.rol">
    <c:param name="weblog" value="${actionWeblog.handle}"/>
</c:url>
<div class="list-actions">
    <a href="${pagesUrl}" class="btn btn-outline-secondary btn-sm"><spring:message code="pages.trash.backToPages"/></a>
</div>

<%-- One form around the whole table, as on Trash.jsp: each row's control is
     a submit button carrying its own value and formaction. The marker wraps
     the list in both states (table or empty), so the route sweep finds it
     whichever rendered. --%>
<spring:message code="pages.trash.deleteForeverConfirm" var="pageDeleteForeverConfirm"/>
<spring:message code="pages.trash.deleteForeverStrandConfirm" var="pageDeleteForeverStrandConfirm"/>
<div id="pages-trash-list-marker">
<form id="pagesTrashForm" method="post"
      action="${pageContext.request.contextPath}/pressroom-ui/authoring/pagesTrash!restore.rol">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
<sec:csrfInput/>

<c:if test="${not empty trashedPages}">
<div class="table-responsive">
<table class="pressroomtable table table-striped" width="100%">

<thead>
<tr>
    <th scope="col" class="pressroomtable"><spring:message code="weblogPagesForm.slug"/></th>
    <th scope="col" class="pressroomtable"><spring:message code="weblogEdit.title"/></th>
    <th scope="col" class="pressroomtable" width="15%"><spring:message code="trash.column.trashed"/></th>
    <th scope="col" class="pressroomtable" width="20%"><spring:message code="pages.trash.column.purged"/></th>
    <th scope="col" class="pressroomtable" width="10%"> </th>
    <th scope="col" class="pressroomtable" width="10%"> </th>
</tr>
</thead>

<tbody>
<c:forEach items="${trashedPages}" var="p">
    <tr class="trashed-page-row" data-page-slug="${fn:escapeXml(p.slug)}">
        <%-- Never a link: a trashed page is served nowhere. --%>
        <td class="data">/<c:out value="${p.slug}"/></td>
        <td><c:out value="${p.title}"/></td>
        <td class="data"><rc:date value="${p.trashedAt}"/></td>
        <td class="data">
            <c:choose>
            <c:when test="${not empty purgeDates[p.id]}"><rc:date value="${purgeDates[p.id]}"/></c:when>
            <c:otherwise><spring:message code="pages.trash.keptForever"/></c:otherwise>
            </c:choose>
        </td>
        <td>
            <button type="submit" name="pageId" value="${fn:escapeXml(p.id)}"
                    class="btn btn-outline-secondary btn-sm page-restore-btn"
                    formaction="${pageContext.request.contextPath}/pressroom-ui/authoring/pagesTrash!restore.rol">
                <spring:message code="pages.trash.restore"/>
            </button>
        </td>
        <td>
            <%-- data-confirm, never an inline confirm: see Pages.jsp. A
                 strand's prompt adds what happens to its posts. --%>
            <button type="submit" name="pageId" value="${fn:escapeXml(p.id)}"
                    class="btn btn-link p-0 align-baseline border-0 text-danger page-delete-forever-btn"
                    formaction="${pageContext.request.contextPath}/pressroom-ui/authoring/pagesTrash!delete.rol"
                    data-confirm="${fn:escapeXml(pageDeleteForeverConfirm)}: '${fn:escapeXml(p.title)}'?<c:if test="${p.strand}"> ${fn:escapeXml(pageDeleteForeverStrandConfirm)}</c:if>">
                <spring:message code="pages.trash.deleteForever"/>
            </button>
        </td>
    </tr>
</c:forEach>
</tbody>

</table>
</div>
</c:if>

<c:if test="${empty trashedPages}">
    <div class="empty-state">
        <p class="empty-state-title"><spring:message code="pages.trash.none.title"/></p>
        <p class="empty-state-body"><spring:message code="pages.trash.none.body"/></p>
    </div>
</c:if>

</form>
</div>
