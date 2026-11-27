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
<%-- The unsubscribe link in a reply email (UnsubscribeController). The signed
     token is the authority: SecurityConfig.isOneClickUnsubscribe exempts
     exactly this POST from CSRF, because a mail client's one-click POST has
     no token. The form still carries one, as every POST form here does
     (JspConsistencyTest); it is simply not required. The token is the
     controller's verified one, escaped as any request value. --%>

<c:url var="unsubscribeUrl" value="/pressroom-ui/unsubscribe.rol"/>
<c:url var="accountUrl" value="/pressroom-ui/account.rol"/>

<div id="unsubscribePage">
    <c:choose>
    <c:when test="${unsubscribeState == 'confirm'}">
        <h2 class="section-head"><spring:message code="unsubscribe.confirm.heading"/></h2>
        <p><spring:message code="unsubscribe.confirm.body"/></p>
        <form id="unsubscribeForm" method="post" action="${unsubscribeUrl}">
            <sec:csrfInput/>
            <input type="hidden" name="t" value="${fn:escapeXml(token)}"/>
            <button type="submit" class="btn btn-primary"><spring:message code="unsubscribe.confirm.button"/></button>
        </form>
    </c:when>
    <c:when test="${unsubscribeState == 'done'}">
        <h2 class="section-head"><spring:message code="unsubscribe.done.heading"/></h2>
        <p><spring:message code="unsubscribe.done.body"/></p>
        <p><a href="${accountUrl}"><spring:message code="unsubscribe.account"/></a></p>
    </c:when>
    <c:when test="${unsubscribeState == 'error'}">
        <div class="empty-state">
            <p class="empty-state-title"><spring:message code="unsubscribe.error"/></p>
        </div>
    </c:when>
    <c:otherwise>
        <div class="empty-state">
            <p class="empty-state-title"><spring:message code="unsubscribe.invalid"/></p>
            <p><a href="${accountUrl}"><spring:message code="unsubscribe.account"/></a></p>
        </div>
    </c:otherwise>
    </c:choose>
</div>
