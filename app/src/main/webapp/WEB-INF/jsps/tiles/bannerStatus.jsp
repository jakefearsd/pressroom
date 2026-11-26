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


<nav class="navbar navbar-expand-md pressroom-topbar">
    <div class="container-fluid">

        <%-- The brand links home. It was href="#" while a second nav item
             below carried the same destination under site.shortName, so the
             one control every reader expects to be a home link did nothing
             and a redundant one did the job. --%>
        <a class="navbar-brand" href="<c:url value='/'/>">${fn:escapeXml(rc:getProp('site.name'))}</a>

        <button type="button" class="navbar-toggler collapsed"
                data-bs-toggle="collapse" data-bs-target="#navbar" aria-expanded="false" aria-controls="navbar"
                aria-label="<spring:message code='generic.toggle'/>">
            <span class="visually-hidden"><spring:message code="generic.toggle"/></span>
            <span class="navbar-toggler-icon"></span>
        </button>

        <div id="navbar" class="navbar-collapse collapse">

            <ul class="navbar-nav ms-auto">

                <%-- Not for a signed-in reader: the app home refuses an account
                     without the global login action, so the link would only
                     lead to access-denied (BaseController.populateCommonModel
                     sets userIsAuthor). Anonymous visitors keep it; it leads
                     them to log in. --%>
                <c:if test="${empty authenticatedUser or userIsAuthor}">
                    <li class="nav-item">
                        <a class="nav-link" href="<c:url value="/pressroom-ui/app/"/>">
                            <spring:message code="mainPage.mainMenu"/></a>
                    </li>
                </c:if>

                <c:choose>
                    <c:when test="${authenticatedUser != null}">
                        <li class="nav-item">
                            <a class="nav-link" href="<c:url value="/pressroom-ui/logout.rol"/>">
                                <spring:message code="navigationBar.logout"/></a>
                        </li>
                    </c:when>
                    <c:otherwise>
                        <li class="nav-item">
                            <a class="nav-link" href="<c:url value="/pressroom-ui/login-redirect.rol"/>">
                                <spring:message code="navigationBar.login"/></a>
                        </li>

                    </c:otherwise>
                </c:choose>

            </ul>
        </div><!--/.navbar-collapse -->
    </div>
</nav>
