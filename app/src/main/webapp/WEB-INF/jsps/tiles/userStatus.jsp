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

<%-- The rail's context header: which weblog you are working in (falls back
     to the signed-in user when there is no weblog in play, e.g. the main
     menu / global admin screens). The "Logged in as" / "Editing weblog"
     labels stay for assistive tech only -- the visible rail is just the
     name, the mono handle, and (for a weblog) a status dot. --%>

<c:choose>
    <c:when test="${actionWeblog != null}">
        <span class="visually-hidden"><spring:message code="mainPage.currentWebsite"/>:</span>
        <div class="rail-context-name">
            <a href="${fn:escapeXml(urls.weblogAbsolute(actionWeblog))}">${fn:escapeXml(actionWeblog.name)}</a>
        </div>
        <div class="rail-context-handle">
            <c:if test="${actionWeblog.visible}">
                <span class="rail-status-dot" aria-hidden="true"></span>
            </c:if>
            ${actionWeblog.handle}
        </div>
    </c:when>

    <c:when test="${authenticatedUser != null}">
        <span class="visually-hidden"><spring:message code="mainPage.loggedInAs"/>:</span>
        <div class="rail-context-name">
            <a href="<c:url value="/roller-ui/profile.rol"/>">${fn:escapeXml(authenticatedUser.userName)}</a>
        </div>
    </c:when>
</c:choose>
