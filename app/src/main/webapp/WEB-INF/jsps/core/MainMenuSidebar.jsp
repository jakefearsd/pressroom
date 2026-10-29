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

<%-- The four nested wrappers that used to sit here (.sidebarFade,
     .menu-tr, .menu-tl, .sidebarInner) were a pre-Bootstrap rounded-corner
     scheme; none of the four has a CSS rule any more. --%>
<%-- Edit profile --%>

<div class="sidebar-group">
<p class="sidebar-label"><span class="bi bi-person" aria-hidden="true"></span>
<a href="<c:url value='/pressroom-ui/profile.rol'/>"><spring:message code="yourWebsites.editProfile" /></a></p>
<p><spring:message code="yourWebsites.editProfile.desc" /></p>
</div>

<%-- Create weblog --%>

<c:if test="${rc:getBooleanProp('site.allowUserWeblogCreation') && (rc:getBooleanProp('groupblogging.enabled') || empty existingPermissions)}">
    <div class="sidebar-group">
    <p class="sidebar-label"><span class="bi bi-plus-lg" aria-hidden="true"></span>
    <a href="<c:url value='/pressroom-ui/createWeblog.rol'/>"><spring:message code="yourWebsites.createWeblog" /></a></p>
    <p><spring:message code="yourWebsites.createWeblog.desc" /></p>
    </div>
</c:if>

<c:if test="${userIsAdmin}">

    <%-- Roller settings --%>

    <div class="sidebar-group">
    <p class="sidebar-label"><span class="bi bi-wrench" aria-hidden="true"></span>
    <a href="<c:url value='/pressroom-ui/admin/globalConfig.rol'/>"><spring:message code="yourWebsites.globalAdmin" /></a></p>
    <p><spring:message code="yourWebsites.globalAdmin.desc" /></p>
    </div>

</c:if>
