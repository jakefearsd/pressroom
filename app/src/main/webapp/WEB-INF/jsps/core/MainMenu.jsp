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

<%-- PROMPT: Welcome... you have no blog.

     This is the first screen a new account ever lands on, so it is the empty
     state that most has to read as an invitation rather than a shrug: one
     title line, one sentence, one primary action. #createWeblogLink moves onto
     the button rather than being dropped -- nothing in the repo selects on it
     today, but it is the only handle this action has ever had. --%>
<c:if test="${empty existingPermissions}">
    <div class="empty-state">
        <p class="empty-state-title"><spring:message code="empty.weblogs.title" /></p>
        <p class="empty-state-body"><spring:message code="empty.weblogs.body" /></p>
        <a id="createWeblogLink" class="btn btn-primary"
           href="<c:url value='/pressroom-ui/createWeblog.rol'/>">
            <spring:message code="empty.weblogs.action" />
        </a>
    </div>
</c:if>

<%-- PROMPT: default ... select a weblog to edit --%>
<c:if test="${not empty existingPermissions}">
    <p class="subtitle"><spring:message code="yourWebsites.prompt.hasBlog" /></p>
</c:if>

<%-- if we have weblogs, then loop through and list them --%>
<c:if test="${not empty existingPermissions}">

    <c:forEach items="${existingPermissions}" var="perms">

        <%-- A quiet row, not a Bootstrap card with a toolbar. This is the
             first screen an author lands on and its job is to get them into
             one weblog, so the row carries the name (which IS the link to the
             weblog -- the raw absolute URL used to sit under it on its own
             line, saying nothing the link does not), the handle and entry
             count in the mono data face, the role sentence, and one
             recommended action. --%>
        <div class="weblog-row">

            <%-- The weblog's name, at weight 600 in ink: a name to pick, not a
                 caps-label section header in link blue. .mm_weblog_name
                 overrides .section-head's caps role for that (roller.css; the
                 pair is pinned by JspConsistencyTest) and carries the icon/label gap (roller.css) that used to
                 be a hardcoded &nbsp;, and stays regardless because
                 RouteSweepIT identifies menu.rol by "h2.mm_weblog_name"
                 (Routes.java). Rename neither the element nor the class
                 without moving that marker in the same commit. --%>
            <h2 class="mm_weblog_name section-head">
                <span class="bi bi-folder2-open" aria-hidden="true"></span>
                <a href="${fn:escapeXml(urls.weblogAbsolute(perms.weblog))}">${fn:escapeXml(perms.weblog.name)}</a>
                <span class="data">${fn:escapeXml(perms.weblog.handle)}</span>
            </h2>

            <p>${fn:escapeXml(perms.weblog.about)}</p>

            <%-- Humanized, localized labels -- this used to print the raw
                 WeblogPermission action constants (ADMIN/POST/EDIT_DRAFT) in
                 upper case. inviteMember.administrator/.author/.limited
                 already exist (and are already translated in every locale
                 bundle) from the deleted invite screen; reused here rather
                 than adding new keys. This page is their only remaining
                 consumer -- do not delete them as orphans. --%>
            <%-- The sentence around the role list was hardcoded English
                 ("You have ... Permission"), so a translated build printed
                 localized role names inside an English frame. The whole
                 sentence is one parameterized key now, and the joined,
                 localized role list is its single argument. --%>
            <c:set var="permLabels" value=""/>
            <c:if test='${perms.hasAction("admin")}'><spring:message code="inviteMember.administrator" var="roleLabel"/><c:set var="permLabels" value="${roleLabel}"/></c:if>
            <c:if test='${perms.hasAction("post")}'><spring:message code="inviteMember.author" var="roleLabel"/><c:set var="permLabels" value="${empty permLabels ? roleLabel : permLabels.concat(', ').concat(roleLabel)}"/></c:if>
            <c:if test='${perms.hasAction("edit_draft")}'><spring:message code="inviteMember.limited" var="roleLabel"/><c:set var="permLabels" value="${empty permLabels ? roleLabel : permLabels.concat(', ').concat(roleLabel)}"/></c:if>
            <p><spring:message code="yourWebsites.permission.summary" arguments="${[fn:escapeXml(permLabels)]}"/></p>

            <%-- One recommended action, then the quiet ways in. The four
                 equally-loud buttons this replaced were a toolbar: nothing in
                 them said which one an author wanted. The label was the
                 literal string "..." -- a placeholder that shipped -- and now
                 names the weblog its actions act on. --%>
            <div class="weblog-actions" role="group"
                 aria-label="${fn:escapeXml(perms.weblog.name)}">

                <c:url value="/pressroom-ui/authoring/entryAdd.rol" var="newEntry">
                    <c:param name="weblog" value="${perms.weblog.handle}"/>
                </c:url>
                <a href="${newEntry}" class="btn btn-primary btn-sm">
                    <spring:message code="yourWebsites.newEntry"/>
                </a>

                <c:if test='${!perms.hasAction("edit_draft")}'>

                    <%-- Entries, with its count, for users above LIMITED permission --%>
                    <c:url value="/pressroom-ui/authoring/entries.rol" var="editEntries">
                        <c:param name="weblog" value="${perms.weblog.handle}"/>
                    </c:url>
                    <a href="${editEntries}" class="quiet-link">
                        <spring:message code="yourWebsites.editEntries"/>
                        <span class="data">${perms.entryCount}</span>
                    </a>

                </c:if>

                <%-- Comments awaiting approval, for a member who may moderate: one whose
                     row implies the post permission (a creator's row holds admin alone). --%>
                <c:if test='${perms.canModerate and perms.pendingCommentCount > 0}'>
                    <c:url value="/pressroom-ui/authoring/comments.rol" var="pendingComments">
                        <c:param name="weblog" value="${perms.weblog.handle}"/>
                    </c:url>
                    <a href="${pendingComments}" class="status-pill status-pending">
                        <spring:message code="mainMenu.pendingComments" arguments="${perms.pendingCommentCount}"/>
                    </a>
                </c:if>

                <%-- Only admins get access to theme and config settings --%>
                <c:if test='${perms.hasAction("admin")}'>

                    <%-- Choosing among the shared themes is safe and always available.
                         A weblog already on a custom theme links to its own templates
                         instead, and that link stays behind the custom-theme flag --
                         same gate ThemeEditController enforces server-side. --%>
                    <c:choose>
                        <c:when test="${perms.weblog.editorTheme == 'custom'}">
                            <c:if test="${rc:getProp('themes.customtheme.allowed')}">
                                <c:url value="/pressroom-ui/authoring/templates.rol" var="weblogTheme">
                                    <c:param name="weblog" value="${perms.weblog.handle}" />
                                </c:url>
                                <a href='${weblogTheme}' class="quiet-link">
                                    <spring:message code="yourWebsites.theme" />
                                </a>
                            </c:if>
                        </c:when>
                        <c:otherwise>
                            <c:url value="/pressroom-ui/authoring/themeEdit.rol" var="weblogTheme">
                                <c:param name="weblog" value="${perms.weblog.handle}" />
                            </c:url>
                            <a href='${weblogTheme}' class="quiet-link">
                                <spring:message code="yourWebsites.theme" />
                            </a>
                        </c:otherwise>
                    </c:choose>

                    <c:url value="/pressroom-ui/authoring/weblogConfig.rol" var="manageWeblog">
                        <c:param name="weblog" value="${perms.weblog.handle}"/>
                    </c:url>
                    <a href='${manageWeblog}' class="quiet-link">
                        <spring:message code="yourWebsites.manage"/>
                    </a>

                </c:if>

            </div>

        </div>

    </c:forEach>

</c:if>

<%-- The Site section: a global admin's way into the site's own pages (the
     front door, the privacy policy, /about), which live in the site space, a
     hidden weblog no one holds a role on. MainMenuController hands it over as
     siteSpace for a global admin only; it is never one of the rows above.
     It offers exactly the three screens the interceptor lets reach the site
     space -- Pages, Media and Theme -- so no link here leads to a refusal.
     Same row grammar as a weblog, so it reads as one more place to work;
     quiet links only, the primary staying each weblog's New entry. --%>
<c:if test="${not empty siteSpace}">
    <div class="weblog-row site-row">
        <h2 class="mm_weblog_name section-head">
            <span class="bi bi-globe2" aria-hidden="true"></span>
            <spring:message code="mainMenu.site.title"/>
        </h2>
        <p><spring:message code="mainMenu.site.desc"/></p>
        <spring:message code="mainMenu.site.title" var="siteLabel"/>
        <div class="weblog-actions" role="group" aria-label="${fn:escapeXml(siteLabel)}">
            <c:url value="/pressroom-ui/authoring/pages.rol" var="sitePages">
                <c:param name="weblog" value="${siteSpace.handle}"/>
            </c:url>
            <a href="${sitePages}" class="quiet-link">
                <spring:message code="mainMenu.site.pages"/>
            </a>
            <c:url value="/pressroom-ui/authoring/mediaFileView.rol" var="siteMedia">
                <c:param name="weblog" value="${siteSpace.handle}"/>
            </c:url>
            <a href="${siteMedia}" class="quiet-link">
                <spring:message code="mainMenu.site.media"/>
            </a>
            <c:url value="/pressroom-ui/authoring/themeEdit.rol" var="siteTheme">
                <c:param name="weblog" value="${siteSpace.handle}"/>
            </c:url>
            <a href="${siteTheme}" class="quiet-link">
                <spring:message code="mainMenu.site.theme"/>
            </a>
        </div>
    </div>
</c:if>
<%-- end site section --%>
