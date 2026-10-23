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

<%-- The reader's account page (AccountController). Everything a reader typed
     -- the display name, its echo in the form, and the comment bodies -- goes
     through fn:escapeXml; a body is plain text shown with pre-wrap, never
     HTML. The entry title is the entry's displayTitle, emitted bare as
     Comments.jsp does: it is stored escaped already. --%>

<c:url var="saveNameUrl" value="/roller-ui/account!saveName.rol"/>
<c:url var="deleteCommentUrl" value="/roller-ui/account!deleteComment.rol"/>
<c:url var="deleteAccountUrl" value="/roller-ui/account!deleteAccount.rol"/>

<div id="accountPage">

    <section class="mb-4">
        <h2 class="section-head"><spring:message code="account.name.heading"/></h2>
        <p><spring:message code="account.name.current"/> <strong>${fn:escapeXml(account.screenName)}</strong></p>
        <form id="accountNameForm" method="post" action="${saveNameUrl}">
            <sec:csrfInput/>
            <div class="mb-3">
                <label class="form-label" for="screenName"><spring:message code="account.name.label"/></label>
                <input type="text" id="screenName" name="screenName" class="form-control"
                       value="${fn:escapeXml(nameValue)}" maxlength="200" required
                       aria-describedby="screenNameHelp"/>
                <div class="form-text" id="screenNameHelp"><spring:message code="account.name.help"/></div>
            </div>
            <button type="submit" class="btn btn-primary"><spring:message code="account.name.save"/></button>
        </form>
    </section>

    <section class="mb-4">
        <h2 class="section-head"><spring:message code="account.comments.heading"/></h2>
        <spring:message var="deleteCommentConfirm" code="account.comment.delete.confirm"/>
        <c:choose>
        <c:when test="${not empty comments}">
        <table class="rollertable table table-striped">
            <tr>
                <th scope="col" class="rollertable"><spring:message code="account.column.status"/></th>
                <th scope="col" class="rollertable"><spring:message code="comments.column.comment"/></th>
                <th scope="col" class="rollertable"><spring:message code="comments.column.entry"/></th>
                <th scope="col" class="rollertable"><spring:message code="comments.column.date"/></th>
                <th scope="col" class="rollertable"></th>
            </tr>
            <c:forEach items="${comments}" var="c">
            <tr>
                <td>
                    <c:choose>
                    <c:when test="${c.status.name() == 'PUBLISHED'}">
                        <span class="status-pill status-published"><spring:message code="account.status.published"/></span>
                    </c:when>
                    <c:when test="${c.status.name() == 'PENDING'}">
                        <span class="status-pill status-pending"><spring:message code="account.status.pending"/></span>
                    </c:when>
                    <c:otherwise>
                        <span class="status-pill status-hidden"><spring:message code="account.status.hidden"/></span>
                    </c:otherwise>
                    </c:choose>
                </td>
                <td><div style="white-space: pre-wrap;">${fn:escapeXml(c.body)}</div></td>
                <td>
                    <a href="${urls.entry(c.entry)}">${c.entry.displayTitle}</a>
                    <div class="form-text">${fn:escapeXml(c.entry.website.name)}</div>
                </td>
                <td class="data"><rc:date value="${c.created}"/></td>
                <td>
                    <form method="post" action="${deleteCommentUrl}">
                        <sec:csrfInput/>
                        <input type="hidden" name="id" value="${fn:escapeXml(c.id)}"/>
                        <button type="submit" class="btn btn-outline-danger btn-sm"
                                data-confirm="${fn:escapeXml(deleteCommentConfirm)}">
                            <spring:message code="account.comment.delete"/></button>
                    </form>
                </td>
            </tr>
            </c:forEach>
        </table>
        </c:when>
        <c:otherwise>
        <div class="empty-state">
            <p class="empty-state-title"><spring:message code="account.comments.none"/></p>
        </div>
        </c:otherwise>
        </c:choose>
    </section>

    <section id="accountDelete">
        <h2 class="section-head"><spring:message code="account.delete.heading"/></h2>
        <c:choose>
        <c:when test="${not empty deleteRefusal}">
            <%-- A weblog member or an administrator: the reason, not the button. --%>
            <p class="form-text"><spring:message code="${deleteRefusal}"/></p>
        </c:when>
        <c:otherwise>
            <p class="form-text"><spring:message code="account.delete.help"/></p>
            <spring:message var="deleteAccountConfirm" code="account.delete.confirm"/>
            <form method="post" action="${deleteAccountUrl}">
                <sec:csrfInput/>
                <button type="submit" class="btn btn-danger" data-confirm="${fn:escapeXml(deleteAccountConfirm)}">
                    <spring:message code="account.delete.button"/></button>
            </form>
        </c:otherwise>
        </c:choose>
    </section>

</div>
