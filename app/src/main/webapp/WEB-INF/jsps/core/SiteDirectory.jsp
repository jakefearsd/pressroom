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
<%-- The site's public home while no weblog is its front page
     (SiteDirectoryController). The description, weblog names and taglines
     are all stored as typed, so each is escaped here. --%>
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>
<%@ page session="false" %>
<%-- session="false" here and on every tile the simple page includes: a JSP
     with the default session="true" calls request.getSession() just to
     render, so the public site root handed every anonymous reader (and
     crawler) a JSESSIONID it never needed. Nothing these pages render reads
     the session; a signed-in user's existing session is untouched. --%>

<div id="siteDirectory">

    <c:set var="siteDescription" value="${rc:getProp('site.description')}"/>
    <c:if test="${not empty siteDescription}">
        <p>${fn:escapeXml(siteDescription)}</p>
    </c:if>

    <c:choose>
        <c:when test="${empty weblogs}">
            <p><spring:message code="siteDirectory.empty"/></p>
        </c:when>
        <c:otherwise>
            <%-- One quiet row per weblog, as on Main Menu: the name at weight
                 600 (emphasis is weight, never size), its tagline under it. --%>
            <ul class="list-unstyled site-directory">
                <c:forEach var="weblog" items="${weblogs}">
                    <li>
                        <a class="site-directory-name" href="${fn:escapeXml(urls.weblogAbsolute(weblog))}">${fn:escapeXml(weblog.name)}</a>
                        <c:if test="${not empty weblog.tagline}">
                            <div class="site-directory-tagline">${fn:escapeXml(weblog.tagline)}</div>
                        </c:if>
                    </li>
                </c:forEach>
            </ul>
        </c:otherwise>
    </c:choose>

    <c:if test="${userIsAdmin}">
        <p class="mt-4 mb-0">
            <a href="<c:url value='/pressroom-ui/setup.rol'/>"><spring:message code="siteDirectory.chooseFrontPage"/></a>
        </p>
    </c:if>

</div>
