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

<%-- New agent (spec 2026-10-13, sections 1 and 4). An agent never signs in,
     so there is no password; the email is optional and never mailed.
     RouteSweepIT finds this page by its form's action. --%>
<p class="subtitle"><spring:message code="agent.create.subtitle"/></p>

<form method="post" action="<c:url value='/pressroom-ui/admin/createAgent!save.rol'/>" class="form-stacked" id="agentCreateForm">
    <sec:csrfInput/>

    <div class="mb-3">
        <label for="bean_userName" class="form-label"><spring:message code="agent.userName"/></label>
        <input type="text" class="form-control" id="bean_userName" name="bean.userName" maxlength="255" autofocus
               value="${fn:escapeXml(bean.userName)}"/>
        <div class="form-text"><spring:message code="agent.userName.tip"/></div>
    </div>

    <div class="mb-3">
        <label for="bean_name" class="form-label"><spring:message code="agent.name"/></label>
        <input type="text" class="form-control" id="bean_name" name="bean.name" maxlength="255"
               value="${fn:escapeXml(bean.name)}"/>
    </div>

    <div class="mb-3">
        <label for="bean_emailAddress" class="form-label"><spring:message code="agent.email"/></label>
        <input type="text" class="form-control" id="bean_emailAddress" name="bean.emailAddress" maxlength="255"
               value="${fn:escapeXml(bean.emailAddress)}"/>
        <div class="form-text"><spring:message code="agent.email.tip"/></div>
    </div>

    <div class="mb-3">
        <label for="bean_notes" class="form-label"><spring:message code="agent.notes"/></label>
        <textarea class="form-control" id="bean_notes" name="bean.notes" rows="3" maxlength="1000">${fn:escapeXml(bean.notes)}</textarea>
        <div class="form-text"><spring:message code="agent.notes.tip"/></div>
    </div>

    <div class="mb-3 form-check">
        <input type="checkbox" class="form-check-input" id="bean_siteAdmin" name="bean.siteAdmin" value="true"
            <c:if test="${bean.siteAdmin}">checked="checked"</c:if>/>
        <label for="bean_siteAdmin" class="form-check-label"><spring:message code="agent.siteAdmin"/></label>
        <div class="form-text"><spring:message code="agent.siteAdmin.tip"/></div>
    </div>

    <button type="submit" class="btn btn-primary" id="agentCreateButton"><spring:message code="agent.create.button"/></button>
    <a href="<c:url value='/pressroom-ui/admin/userAdmin.rol'/>" class="btn btn-secondary"><spring:message code="generic.cancel"/></a>
</form>
