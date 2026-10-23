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
<%@ include file="/WEB-INF/jsps/tiles/menu-model.jsp" %>
<!DOCTYPE html>
<html lang="${pageContext.response.locale.toLanguageTag()}">
<head>
    <meta charset="utf-8">
    <meta http-equiv="X-UA-Compatible" content="IE=edge">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <link rel="icon" href="<%= request.getContextPath() %>/favicon.svg" type="image/x-icon">
    <%-- The weblog handle belongs in the tab: an admin with four weblogs open
         had four tabs reading "Roller: Entries". Only where the model has it
         -- these layouts serve un-scoped screens (Global Config, User Admin)
         as well. --%>
    <%-- tabTitle, when a controller sets one, names the browser TAB more
         specifically than the visible heading does (the entry editor sets it to
         "<entry title> - Edit entry", so a dozen open tabs are told apart).
         pageTitle is what the <h1 class="roller-page-title"> below renders, so
         it must NOT carry that append -- the two attributes exist to let the
         tab and the heading differ. It is never a message key: the controller
         builds it as plain text (EntriesController.plainTitle) and it is
         escaped exactly once here, as the site's short name is. --%>
    <title>${fn:escapeXml(rc:getProp('site.shortName'))}: <c:choose><c:when test="${not empty tabTitle}">${fn:escapeXml(tabTitle)}</c:when><c:otherwise><spring:message code="${pageTitle}" text="${pageTitle}"/></c:otherwise></c:choose><c:if test="${not empty actionWeblog}"> &#8212; ${fn:escapeXml(actionWeblog.handle)}</c:if></title>
    <jsp:include page="${tile_head}"/>
    <style>
        <jsp:include page="${tile_styles}" />
    </style>
</head>
<body<c:if test="${not empty invalidFieldIds}"> data-invalid-fields="${fn:escapeXml(invalidFieldIds)}"</c:if>>

<jsp:include page="${tile_banner}"/>

<div class="container-fluid">

    <div class="row">

        <div class="col-md-3 roller-column-left">

            <%-- Below md the rail and the page's sidebar fold behind this
                 button: stacked above the content they pushed the page
                 itself a screen and more down (the entry editor's title sat
                 at y=1327 on a phone). From md up .d-md-block keeps the
                 column shown whatever the collapse state, so desktop is
                 unchanged. --%>
            <spring:message code="adminRail.toggle" var="railToggleLabel"/>
            <button type="button" class="btn btn-secondary btn-sm d-md-none rail-toggle"
                    data-bs-toggle="collapse" data-bs-target="#adminRailColumn"
                    aria-expanded="false" aria-controls="adminRailColumn">
                <span class="bi bi-list" aria-hidden="true"></span> ${railToggleLabel}
            </button>
            <div class="collapse d-md-block" id="adminRailColumn">

            <nav class="rail" id="adminRail">

                <c:if test="${not empty authenticatedUser or not empty actionWeblog}">
                    <div class="rail-context">
                        <jsp:include page="${tile_userStatus}"/>
                    </div>
                </c:if>

                <c:if test="${navMenu != null}">
                    <c:forEach items="${navMenu.tabs}" var="tab">
                        <div class="rail-group">
                            <div class="rail-group-label"><spring:message code="${tab.key}"/></div>
                            <c:forEach items="${tab.items}" var="tabItem">
                                <c:choose>
                                    <c:when test="${actionWeblog != null}">
                                        <a class="rail-link${tabItem.selected ? ' rail-active' : ''}"
                                           ${tabItem.selected ? 'aria-current="page"' : ''}
                                           href="<c:url value="/roller-ui/authoring/${tabItem.action}.rol">
                                               <c:param name="weblog" value="${actionWeblog.handle}"/></c:url>">
                                            <spring:message code="${tabItem.key}"/>
                                        </a>
                                    </c:when>
                                    <c:otherwise>
                                        <a class="rail-link${tabItem.selected ? ' rail-active' : ''}"
                                           ${tabItem.selected ? 'aria-current="page"' : ''}
                                           href="<c:url value='/roller-ui/admin/${tabItem.action}.rol'/>">
                                            <spring:message code="${tabItem.key}"/>
                                        </a>
                                    </c:otherwise>
                                </c:choose>
                            </c:forEach>
                        </div>
                    </c:forEach>
                </c:if>

            </nav>

            <c:if test="${tile_sidebar != '/WEB-INF/jsps/tiles/empty.jsp'}">
                <aside class="sidebar" id="adminSidebar">
                    <jsp:include page="${tile_sidebar}"/>
                </aside>
            </c:if>

            </div>

        </div>

        <main class="col-md-9 roller-column-right">
            <div class="card">
                <div class="card-body roller-content-body">

                    <h1 class="roller-page-title"><spring:message code="${pageTitle}" text="${pageTitle}"/></h1>
                    <jsp:include page="${tile_messages}"/>
                    <jsp:include page="${tile_content}"/>

                </div>
            </div>
        </main>


    </div>

</div>

<footer class="footer">
    <div class="container-fluid">
        <jsp:include page="${tile_footer}"/>
    </div>
</footer>

</body>
</html>
