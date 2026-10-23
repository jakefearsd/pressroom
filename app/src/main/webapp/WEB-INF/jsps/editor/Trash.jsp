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
    <spring:message code="trash.subtitle" arguments="${actionWeblog.handle}"/>
</p>

<%-- What "trash" actually promises, in the three cases the retention
     property has. Without this the page says an entry is recoverable but
     never says for how long -- and at -1 (the value that keeps everything)
     it never says that either, which is the case people most want to know
     about before they rely on it. --%>
<p class="pagetip">
    <c:choose>
    <c:when test="${trashRetentionDays lt 0}">
        <spring:message code="trash.retention.forever"/>
    </c:when>
    <c:when test="${trashRetentionDays eq 0}">
        <spring:message code="trash.retention.nextSweep"/>
    </c:when>
    <c:otherwise>
        <spring:message code="trash.retention.days" arguments="${trashRetentionDays}"/>
    </c:otherwise>
    </c:choose>
</p>

<%-- One form around the whole table, following Entries.jsp's pattern: every
     row's Restore/Delete forever control is a submit button carrying its own
     name/value and formaction, not a form of its own (a form nested inside
     another is not valid HTML). trash-list-marker wraps the list region in
     BOTH states (table or empty-state invitation) the same way
     entries-list-marker does on Entries.jsp, so the route sweep has something
     to find whichever one rendered. --%>
<spring:message code="trash.deleteForeverConfirm" var="trashDeleteForeverConfirm"/>
<spring:message code="trash.emptyConfirm" arguments="${fn:length(trashedEntries)}" var="trashEmptyConfirm"/>
<div id="trash-list-marker">
<form id="trashForm" method="post"
      action="${pageContext.request.contextPath}/roller-ui/authoring/trash!restore.rol">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
<sec:csrfInput/>

<c:if test="${not empty trashedEntries}">
<div class="table-responsive">
<table class="rollertable table table-striped" width="100%">

<tr>
    <th scope="col" class="rollertable"><spring:message code="trash.column.title"/></th>
    <th scope="col" class="rollertable" width="15%"><spring:message code="trash.column.trashed"/></th>
    <th scope="col" class="rollertable" width="15%"><spring:message code="trash.column.category"/></th>
    <th scope="col" class="rollertable" width="10%"> </th>
    <th scope="col" class="rollertable" width="10%"> </th>
</tr>

<c:forEach items="${trashedEntries}" var="entry">
    <tr>
        <td>${entry.displayTitle}</td>
        <td class="data"><rc:date value="${entry.trashedAt}"/></td>
        <td>${fn:escapeXml(entry.category.name)}</td>
        <td>
            <%-- The clicked submit button is the only one whose name/value is
                 sent, so restoreId identifies this row without a hidden field
                 per row -- same reasoning as Entries.jsp's duplicateId. --%>
            <button type="submit" name="restoreId" value="${entry.id}"
                    class="btn btn-outline-secondary btn-sm"
                    formaction="${pageContext.request.contextPath}/roller-ui/authoring/trash!restore.rol">
                <spring:message code="trash.restore"/>
            </button>
        </td>
        <td>
            <button type="submit" name="deleteId" value="${entry.id}"
                    class="btn btn-link p-0 align-baseline border-0 text-danger"
                    formaction="${pageContext.request.contextPath}/roller-ui/authoring/trash!delete.rol"
                    data-confirm="${trashDeleteForeverConfirm}">
                <spring:message code="trash.deleteForever"/>
            </button>
        </td>
    </tr>
</c:forEach>

</table>
</div>
</c:if>

<c:if test="${not empty trashedEntries}">
    <div class="d-flex justify-content-end mb-3">
        <button type="submit" class="btn btn-danger"
                formaction="${pageContext.request.contextPath}/roller-ui/authoring/trash!empty.rol"
                data-confirm="${trashEmptyConfirm}">
            <spring:message code="trash.empty"/>
        </button>
    </div>
</c:if>

<%-- This is the one empty state in the app that is not an invitation: there
     is nothing here to invite -- an empty trash is just empty, so no action
     button. --%>
<c:if test="${empty trashedEntries}">
    <div class="empty-state">
        <p class="empty-state-title"><spring:message code="trash.none.title"/></p>
        <p class="empty-state-body"><spring:message code="trash.none.body"/></p>
    </div>
</c:if>

</form>
</div>
