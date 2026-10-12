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

<%-- Reader comments. Everything a reader typed -- display name, username and
     body -- goes through fn:escapeXml; the body is plain text shown with
     pre-wrap, never HTML. The entry title is the entry's own displayTitle
     (tags already removed), escaped as well. --%>

<spring:message var="tabPending" code="comments.tab.pending"/>
<spring:message var="tabPublished" code="comments.tab.published"/>
<spring:message var="tabHidden" code="comments.tab.hidden"/>
<nav class="nav nav-tabs" aria-label="<spring:message code='comments.title'/>">
    <c:forEach items="${['PENDING','PUBLISHED','HIDDEN']}" var="tab">
        <c:url var="tabUrl" value="/roller-ui/authoring/comments.rol">
            <c:param name="weblog" value="${actionWeblog.handle}"/>
            <c:param name="status" value="${tab}"/>
        </c:url>
        <a class="nav-link${status == tab ? ' active' : ''}" href="${tabUrl}"
           <c:if test="${status == tab}">aria-current="page"</c:if>>
            <c:choose>
            <c:when test="${tab == 'PENDING'}">${tabPending}</c:when>
            <c:when test="${tab == 'PUBLISHED'}">${tabPublished}</c:when>
            <c:otherwise>${tabHidden}</c:otherwise>
            </c:choose>
        </a>
    </c:forEach>
</nav>
<p class="form-text"><spring:message code="comments.hideVsDelete"/></p>

<nav>
    <div class="d-flex justify-content-between">
        <c:if test="${page > 0}">
            <c:url var="prevUrl" value="/roller-ui/authoring/comments.rol">
                <c:param name="weblog" value="${actionWeblog.handle}"/>
                <c:param name="status" value="${status}"/>
                <c:param name="page" value="${page - 1}"/>
                <c:if test="${not empty entryFilter}"><c:param name="entry" value="${entryFilter}"/></c:if>
            </c:url>
            <a href="${prevUrl}" class="btn btn-outline-secondary previous">
                <span aria-hidden="true">&larr;</span> <spring:message code="pager.newer"/></a>
        </c:if>
        <c:if test="${(page + 1) * 30 < commentCount}">
            <c:url var="nextUrl" value="/roller-ui/authoring/comments.rol">
                <c:param name="weblog" value="${actionWeblog.handle}"/>
                <c:param name="status" value="${status}"/>
                <c:param name="page" value="${page + 1}"/>
                <c:if test="${not empty entryFilter}"><c:param name="entry" value="${entryFilter}"/></c:if>
            </c:url>
            <a href="${nextUrl}" class="btn btn-outline-secondary next ms-auto"><spring:message code="pager.older"/>
                <span aria-hidden="true">&rarr;</span></a>
        </c:if>
    </div>
</nav>

<c:set var="base" value="${pageContext.request.contextPath}/roller-ui/authoring"/>
<spring:message var="deleteConfirm" code="comments.delete.confirm"/>

<form id="commentsModerationForm" method="post" action="${base}/comments!approve.rol">
    <input type="hidden" name="weblog" value="${fn:escapeXml(actionWeblog.handle)}"/>
    <input type="hidden" name="status" value="${fn:escapeXml(status)}"/>
    <sec:csrfInput/>

    <c:choose>
    <c:when test="${not empty comments}">

    <div class="selection-bar" data-selection-bar="commentsModerationForm" hidden>
        <span class="selection-count" data-template="<spring:message code='selection.count'/>"></span>
        <button type="submit" class="btn btn-primary" formaction="${base}/comments!approve.rol">
            <spring:message code="comments.approve"/></button>
        <button type="submit" class="btn btn-secondary" formaction="${base}/comments!hide.rol">
            <spring:message code="comments.hide"/></button>
        <button type="submit" class="btn btn-danger" formaction="${base}/comments!delete.rol"
                data-confirm="${fn:escapeXml(deleteConfirm)}"><spring:message code="comments.delete"/></button>
    </div>

    <table class="rollertable table table-striped" width="100%">
        <tr>
            <th scope="col" class="rollertable" width="3%">
                <input type="checkbox" class="form-check-input" data-select-all
                       title="<spring:message code="weblogEntryQuery.selectAll"/>"/>
            </th>
            <th scope="col" class="rollertable"><spring:message code="comments.column.writer"/></th>
            <th scope="col" class="rollertable"><spring:message code="comments.column.comment"/></th>
            <th scope="col" class="rollertable"><spring:message code="comments.column.entry"/></th>
            <th scope="col" class="rollertable"><spring:message code="comments.column.date"/></th>
            <th scope="col" class="rollertable"></th>
        </tr>

        <c:forEach items="${comments}" var="c">
            <c:set var="tombstone" value="${c.status.name() == 'HIDDEN' and empty c.body}"/>
            <tr>
                <td>
                    <input type="checkbox" class="form-check-input" name="ids"
                           value="${fn:escapeXml(c.id)}" aria-label="${fn:escapeXml(c.author.userName)}"/>
                </td>
                <td>
                    <strong>${fn:escapeXml(c.author.screenName)}</strong>
                    <c:if test="${c.authorIsOwner}"><span class="badge text-bg-secondary"><spring:message code="comments.author"/></span></c:if><br/>
                    <span class="data">${fn:escapeXml(c.author.userName)}</span>
                </td>
                <td>
                    <c:choose>
                    <c:when test="${tombstone}"><em><spring:message code="comments.tombstone"/></em></c:when>
                    <c:otherwise><div style="white-space: pre-wrap;">${fn:escapeXml(c.body)}</div></c:otherwise>
                    </c:choose>
                    <c:if test="${not empty c.reply}">
                        <div class="ms-3 mt-2">
                            <span class="badge text-bg-secondary"><spring:message code="comments.author"/></span>
                            <span style="white-space: pre-wrap;">${fn:escapeXml(c.reply.body)}</span>
                        </div>
                    </c:if>
                    <c:if test="${c.status.name() == 'PUBLISHED' and empty c.replyTo and empty c.reply}">
                        <details>
                            <summary><spring:message code="comments.reply"/></summary>
                            <textarea name="body" form="reply-${fn:escapeXml(c.id)}" class="form-control" rows="3"
                                      maxlength="4000" required
                                      aria-label="<spring:message code='comments.reply'/>"></textarea>
                            <button type="submit" form="reply-${fn:escapeXml(c.id)}" class="btn btn-primary btn-sm">
                                <spring:message code="comments.reply.post"/></button>
                        </details>
                    </c:if>
                </td>
                <td>
                    <c:url var="editUrl" value="/roller-ui/authoring/entryEdit.rol">
                        <c:param name="weblog" value="${actionWeblog.handle}"/>
                        <c:param name="bean.id" value="${c.entry.id}"/>
                    </c:url>
                    <a href="${editUrl}">${fn:escapeXml(c.entry.displayTitle)}</a>
                </td>
                <td class="data"><rc:date value="${c.created}"/></td>
                <td>
                    <c:if test="${c.status.name() != 'PUBLISHED' and not tombstone}">
                        <button type="submit" class="btn btn-outline-primary btn-sm" name="ids"
                                value="${fn:escapeXml(c.id)}" formaction="${base}/comments!approve.rol">
                            <spring:message code="comments.approve"/></button>
                    </c:if>
                    <c:if test="${c.status.name() != 'HIDDEN'}">
                        <button type="submit" class="btn btn-outline-secondary btn-sm" name="ids"
                                value="${fn:escapeXml(c.id)}" formaction="${base}/comments!hide.rol">
                            <spring:message code="comments.hide"/></button>
                    </c:if>
                    <button type="submit" class="btn btn-outline-danger btn-sm" name="ids"
                            value="${fn:escapeXml(c.id)}" formaction="${base}/comments!delete.rol"
                            data-confirm="${fn:escapeXml(deleteConfirm)}">
                        <spring:message code="comments.delete"/></button>
                </td>
            </tr>
        </c:forEach>
    </table>

    </c:when>
    <c:otherwise>
    <div class="empty-state">
        <p class="empty-state-title"><spring:message code="comments.none"/></p>
    </div>
    </c:otherwise>
    </c:choose>
</form>

<%-- One small form per publishable row, outside the table's form (forms do not
     nest); the row's textarea and button attach to it with the form attribute. --%>
<c:forEach items="${comments}" var="c">
    <c:if test="${c.status.name() == 'PUBLISHED' and empty c.replyTo and empty c.reply}">
        <form id="reply-${fn:escapeXml(c.id)}" method="post" action="${base}/comments!reply.rol">
            <input type="hidden" name="weblog" value="${fn:escapeXml(actionWeblog.handle)}"/>
            <input type="hidden" name="status" value="${fn:escapeXml(status)}"/>
            <input type="hidden" name="id" value="${fn:escapeXml(c.id)}"/>
            <sec:csrfInput/>
        </form>
    </c:if>
</c:forEach>
