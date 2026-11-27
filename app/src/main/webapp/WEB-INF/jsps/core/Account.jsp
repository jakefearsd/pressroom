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

<%-- The reader's account page (AccountController), "Your account": the
     sections in the spec's order (0.18.0, Part E), each its own anchor the
     blog header's "You" menu links to: #saved, #following, #history,
     #comments, #email, #name, #delete. Everything a reader typed
     -- the display name, its echo in the form, and the comment bodies -- goes
     through fn:escapeXml; a body is plain text shown with pre-wrap, never
     HTML. The entry title is the controller's plain-text entryTitles label,
     escaped once as Comments.jsp does: the stored title is already escaped,
     and an untitled entry's displayTitle is raw body text. --%>

<c:url var="saveNameUrl" value="/pressroom-ui/account!saveName.rol"/>
<c:url var="deleteCommentUrl" value="/pressroom-ui/account!deleteComment.rol"/>
<c:url var="deleteAccountUrl" value="/pressroom-ui/account!deleteAccount.rol"/>
<c:url var="saveEmailUrl" value="/pressroom-ui/account!saveEmailSetting.rol"/>
<c:url var="unsaveUrl" value="/pressroom-ui/account!unsave.rol"/>
<c:url var="unfollowUrl" value="/pressroom-ui/account!unfollow.rol"/>
<c:url var="subscribeUrl" value="/pressroom-ui/account!subscribe.rol"/>
<c:url var="clearHistoryUrl" value="/pressroom-ui/account!clearHistory.rol"/>
<c:url var="saveHistoryUrl" value="/pressroom-ui/account!saveHistorySetting.rol"/>

<div id="accountPage">

    <%-- Saved, Following and Reading history (0.18.0): the reader's own
         lists, newest first, kept by ReaderStateService. Entry labels are the
         controller's plain-text entryTitles, escaped once; blog names are
         stored raw and escaped here. --%>
    <section id="saved" class="mb-4">
        <h2 class="section-head"><spring:message code="account.saved.heading"/></h2>
        <c:choose>
        <c:when test="${not empty saved}">
        <ul class="list-unstyled account-list">
            <c:forEach items="${saved}" var="e">
            <li class="account-list-item">
                <span><a href="${urls.entry(e)}">${fn:escapeXml(entryTitles[e.id])}</a>
                <span class="form-text">${fn:escapeXml(e.website.name)}</span></span>
                <form method="post" action="${unsaveUrl}">
                    <sec:csrfInput/>
                    <input type="hidden" name="entry" value="${fn:escapeXml(e.id)}"/>
                    <button type="submit" class="btn btn-secondary btn-sm"><spring:message code="account.saved.remove"/></button>
                </form>
            </li>
            </c:forEach>
        </ul>
        </c:when>
        <c:otherwise>
        <div class="empty-state">
            <p class="empty-state-title"><spring:message code="account.saved.none"/></p>
        </div>
        </c:otherwise>
        </c:choose>
    </section>

    <section id="following" class="mb-4">
        <h2 class="section-head"><spring:message code="account.following.heading"/></h2>
        <c:choose>
        <c:when test="${not empty following}">
        <ul class="list-unstyled account-list">
            <c:forEach items="${following}" var="w">
            <li class="account-list-item">
                <span><a href="${urls.weblog(w)}">${fn:escapeXml(w.name)}</a></span>
                <span class="account-list-actions">
                <c:if test="${newsletterOffered[w.id]}">
                <form method="post" action="${subscribeUrl}">
                    <sec:csrfInput/>
                    <input type="hidden" name="weblog" value="${fn:escapeXml(w.id)}"/>
                    <button type="submit" class="btn btn-secondary btn-sm"><spring:message code="account.following.subscribe"/></button>
                </form>
                </c:if>
                <form method="post" action="${unfollowUrl}">
                    <sec:csrfInput/>
                    <input type="hidden" name="weblog" value="${fn:escapeXml(w.id)}"/>
                    <button type="submit" class="btn btn-secondary btn-sm"><spring:message code="account.following.unfollow"/></button>
                </form>
                </span>
            </li>
            </c:forEach>
        </ul>
        </c:when>
        <c:otherwise>
        <div class="empty-state">
            <p class="empty-state-title"><spring:message code="account.following.none"/></p>
        </div>
        </c:otherwise>
        </c:choose>
    </section>

    <section id="history" class="mb-4">
        <h2 class="section-head"><spring:message code="account.history.heading"/></h2>
        <form id="accountHistoryForm" method="post" action="${saveHistoryUrl}" class="mb-3">
            <sec:csrfInput/>
            <div class="form-check form-switch mb-2">
                <input class="form-check-input" type="checkbox" role="switch" id="keepHistory"
                       name="keepHistory" value="true" aria-describedby="keepHistoryHelp"
                       <c:if test="${keepHistory}">checked</c:if>/>
                <label class="form-check-label" for="keepHistory"><spring:message code="account.history.keep.label"/></label>
            </div>
            <div class="form-text mb-2" id="keepHistoryHelp"><spring:message code="account.history.keep.help"/></div>
            <button type="submit" class="btn btn-secondary btn-sm"><spring:message code="account.history.save"/></button>
        </form>
        <c:choose>
        <c:when test="${not empty history}">
        <ul class="list-unstyled account-list">
            <c:forEach items="${history}" var="e">
            <li class="account-list-item">
                <span><a href="${urls.entry(e)}">${fn:escapeXml(entryTitles[e.id])}</a>
                <span class="form-text">${fn:escapeXml(e.website.name)}</span></span>
            </li>
            </c:forEach>
        </ul>
        <spring:message var="clearHistoryConfirm" code="account.history.clear.confirm"/>
        <form method="post" action="${clearHistoryUrl}">
            <sec:csrfInput/>
            <button type="submit" class="btn btn-danger btn-sm" data-confirm="${fn:escapeXml(clearHistoryConfirm)}">
                <spring:message code="account.history.clear"/></button>
        </form>
        </c:when>
        <c:otherwise>
        <div class="empty-state">
            <p class="empty-state-title"><c:choose><c:when test="${keepHistory}"><spring:message code="account.history.none"/></c:when><c:otherwise><spring:message code="account.history.off"/></c:otherwise></c:choose></p>
        </div>
        </c:otherwise>
        </c:choose>
    </section>

    <section id="comments" class="mb-4">
        <h2 class="section-head"><spring:message code="account.comments.heading"/></h2>
        <spring:message var="deleteCommentConfirm" code="account.comment.delete.confirm"/>
        <c:choose>
        <c:when test="${not empty comments}">
        <div class="table-responsive">
        <table class="pressroomtable table table-striped">
            <tr>
                <th scope="col" class="pressroomtable"><spring:message code="account.column.status"/></th>
                <th scope="col" class="pressroomtable"><spring:message code="comments.column.comment"/></th>
                <th scope="col" class="pressroomtable"><spring:message code="comments.column.entry"/></th>
                <th scope="col" class="pressroomtable"><spring:message code="comments.column.date"/></th>
                <th scope="col" class="pressroomtable row-actions"><span class="visually-hidden"><spring:message code="mainPage.actions"/></span></th>
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
                <td><div class="pre-wrap">${fn:escapeXml(c.body)}</div>
                    <c:if test="${not empty c.reply and c.reply.status.name() == 'PUBLISHED' and not empty c.reply.author}">
                    <div class="form-text account-answered"><spring:message code="account.comments.answeredBy"><spring:argument value="${fn:escapeXml(c.reply.author.screenName)}"/></spring:message></div>
                    </c:if></td>
                <td>
                    <a href="${urls.entry(c.entry)}">${fn:escapeXml(entryTitles[c.entry.id])}</a>
                    <div class="form-text">${fn:escapeXml(c.entry.website.name)}</div>
                </td>
                <td class="data"><rc:date value="${c.created}"/></td>
                <td class="row-actions">
                    <form method="post" action="${deleteCommentUrl}">
                        <sec:csrfInput/>
                        <input type="hidden" name="id" value="${fn:escapeXml(c.id)}"/>
                        <button type="submit" class="row-action row-action-danger"
                                data-confirm="${fn:escapeXml(deleteCommentConfirm)}"
                                aria-label="<spring:message code='account.comment.delete'/>"><span class="bi bi-trash" aria-hidden="true"></span></button>
                    </form>
                </td>
            </tr>
            </c:forEach>
        </table>
        </div>
        </c:when>
        <c:otherwise>
        <div class="empty-state">
            <p class="empty-state-title"><spring:message code="account.comments.none"/></p>
        </div>
        </c:otherwise>
        </c:choose>
    </section>

    <%-- The Email section: self-contained (its own form, URL and messages), so
         it moves as one block when the page is rebuilt. The menu's "Email
         settings" link and the unsubscribe page point at #email. --%>
    <section id="email" class="mb-4">
        <h2 class="section-head"><spring:message code="account.email.heading"/></h2>
        <form id="accountEmailForm" method="post" action="${saveEmailUrl}">
            <sec:csrfInput/>
            <div class="form-check form-switch mb-2">
                <input class="form-check-input" type="checkbox" role="switch" id="notifyReplies"
                       name="notifyReplies" value="true" aria-describedby="notifyRepliesHelp"
                       <c:if test="${notifyReplies}">checked</c:if>/>
                <label class="form-check-label" for="notifyReplies"><spring:message code="account.email.replies.label"/></label>
            </div>
            <div class="form-text mb-3" id="notifyRepliesHelp">
                <c:choose>
                <c:when test="${empty account.emailAddress}"><spring:message code="account.email.noAddress"/></c:when>
                <c:otherwise><spring:message code="account.email.replies.help"/></c:otherwise>
                </c:choose>
            </div>
            <button type="submit" class="btn btn-primary"><spring:message code="account.email.save"/></button>
        </form>
    </section>

    <section id="name" class="mb-4">
        <h2 class="section-head"><spring:message code="account.name.heading"/></h2>
        <p><spring:message code="account.name.current"/> <strong>${fn:escapeXml(account.screenName)}</strong></p>
        <form id="accountNameForm" class="form-stacked" method="post" action="${saveNameUrl}">
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

    <section id="delete">
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
