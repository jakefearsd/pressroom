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
<%@ page session="false" %>

<%-- The tabbed-menu items themselves now render in the context rail
     (tiles-tabbedpage.jsp / tiles-mainmenupage.jsp), not here -- this bar
     only keeps the pinned, always-present links. --%>

<nav class="navbar navbar-expand-md roller-topbar">
    <div class="container-fluid">

        <%-- The brand links home. It was href="#" while a second nav item
             below carried the same destination under site.shortName, so the
             one control every reader expects to be a home link did nothing
             and a redundant one did the job. --%>
        <a class="navbar-brand" href="<c:url value='/'/>">${rc:getProp('site.name')}</a>

        <%-- Only rendered when populateCommonModel found two or more weblogs
             for the signed-in user -- a one-weblog session's top bar is
             unchanged. The target keeps the rail action the reader is
             already on (BaseController.SWITCHER_ACTIONS via switcherAction),
             falling back to entries.rol for anything off the rail. --%>
        <c:if test="${not empty userWeblogs}">
            <div class="dropdown weblog-switcher">
                <button class="btn btn-secondary btn-sm dropdown-toggle" type="button"
                        id="weblogSwitcher" data-bs-toggle="dropdown" aria-expanded="false"
                        aria-label="<spring:message code='switcher.label'/>">
                    <c:choose>
                        <c:when test="${actionWeblog != null}">${fn:escapeXml(actionWeblog.name)}</c:when>
                        <c:otherwise><spring:message code="switcher.label"/></c:otherwise>
                    </c:choose>
                </button>
                <ul class="dropdown-menu" aria-labelledby="weblogSwitcher">
                    <c:forEach items="${userWeblogs}" var="w">
                        <li>
                            <a class="dropdown-item${actionWeblog != null && w.handle == actionWeblog.handle ? ' active' : ''}"
                               href="<c:url value="/roller-ui/authoring/${switcherAction}.rol">
                                   <c:param name="weblog" value="${w.handle}"/></c:url>">
                                ${fn:escapeXml(w.name)} <span class="data">${fn:escapeXml(w.handle)}</span>
                            </a>
                        </li>
                    </c:forEach>
                </ul>
            </div>
        </c:if>

        <button type="button" class="navbar-toggler collapsed"
                data-bs-toggle="collapse" data-bs-target="#navbar" aria-expanded="false" aria-controls="navbar"
                aria-label="<spring:message code='generic.toggle'/>">
            <span class="visually-hidden"><spring:message code="generic.toggle"/></span>
            <span class="navbar-toggler-icon"></span>
        </button>

        <div id="navbar" class="navbar-collapse collapse">

            <ul class="navbar-nav ms-auto">

                <%-- Not for a signed-in reader: menu.rol refuses an account
                     without the global login action, so the link would only
                     lead to access-denied (BaseController.populateCommonModel
                     sets userIsAuthor). Anonymous visitors keep it; it leads
                     them to log in. --%>
                <c:if test="${empty authenticatedUser or userIsAuthor}">
                    <li class="nav-item">
                        <a class="nav-link" href="<c:url value="/roller-ui/menu.rol"/>">
                            <spring:message code="mainPage.mainMenu"/></a>
                    </li>
                </c:if>

                <c:choose>
                    <c:when test="${authenticatedUser != null}">
                        <li class="nav-item">
                            <a class="nav-link" href="<c:url value="/roller-ui/logout.rol"/>">
                                <spring:message code="navigationBar.logout"/></a>
                        </li>
                    </c:when>
                    <c:otherwise>
                        <li class="nav-item">
                            <a class="nav-link" href="<c:url value="/roller-ui/login-redirect.rol"/>">
                                <spring:message code="navigationBar.login"/></a>
                        </li>

                    </c:otherwise>
                </c:choose>

            </ul>
        </div><!--/.navbar-collapse -->
    </div>
</nav>
