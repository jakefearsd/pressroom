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

<%-- Every word and fact comes from one Colophon record, the same one the
     reader-page footer renders (docs/dev/admin-ui.md, "Footer"). Colophon.of
     never throws: a display path degrades to omitting a clause. The
     Weblogger is looked up here, defensively: before bootstrap (install,
     upgrade and database-error pages carry this footer too) the provider bean
     throws, and the footer then gets null, which Colophon.of answers from the
     packaged version file. weblog is null on admin screens, so the copyright
     holder is the site name. --%>
<% com.wikantik.pressroom.business.Weblogger rollerTier = null;
   try {
       rollerTier = org.springframework.web.context.support.WebApplicationContextUtils
           .getRequiredWebApplicationContext(application)
           .getBean(com.wikantik.pressroom.business.WebloggerProvider.class)
           .getWeblogger();
   } catch (RuntimeException notBootstrapped) {
       rollerTier = null; // pre-bootstrap: version facts only
   }
   request.setAttribute("colophon", com.wikantik.pressroom.business.Colophon.of(
       rollerTier, null, java.time.Clock.systemDefaultZone())); %>

<%-- All Colophon strings are raw, so each is escaped here. Arguments are nested
     spring:argument tags, not an "arguments" string: spring:message splits that
     on commas, and a copyright holder can contain one. spring:message does not
     escape arguments itself. A bare "${colophon.copyrightYear}" would format as
     "2,026" through MessageFormat; fn:escapeXml makes it the string "2026". --%>
<div class="colophon">
<c:if test="${not empty colophon.copyrightText or not empty colophon.copyrightHolder}">
<p class="colophon-copyright">
<c:choose>
<c:when test="${not empty colophon.copyrightText}">${fn:escapeXml(colophon.copyrightText)}</c:when>
<c:otherwise><spring:message code="footer.copyright.default"><spring:argument value="${fn:escapeXml(colophon.copyrightYear)}"/><spring:argument value="${fn:escapeXml(colophon.copyrightHolder)}"/></spring:message></c:otherwise>
</c:choose>
</p>
</c:if>
<p class="colophon-meta">
<c:if test="${not empty colophon.privacyUrl}"><span class="colophon-privacy"><a href="${fn:escapeXml(colophon.privacyUrl)}"><spring:message code="footer.privacy"/></a></span></c:if>
<c:if test="${not empty colophon.version}"><span class="colophon-version"><c:choose>
<c:when test="${not empty colophon.shortRevision}"><spring:message code="footer.version.revision"><spring:argument value="${fn:escapeXml(colophon.version)}"/><spring:argument value="${fn:escapeXml(colophon.shortRevision)}"/></spring:message></c:when>
<c:otherwise><spring:message code="footer.version"><spring:argument value="${fn:escapeXml(colophon.version)}"/></spring:message></c:otherwise>
</c:choose></span></c:if>
<c:if test="${not empty colophon.releaseDate}"><span class="colophon-released"><spring:message code="footer.released"><spring:argument value="${fn:escapeXml(colophon.releaseDate)}"/></spring:message></span></c:if>
</p>
</div>
