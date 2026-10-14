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

<%-- An agent's page (spec 2026-10-13, section 4; docs/design/forms/forms-agent-page.html).
     An agent never signs in: there is no password here and no set-password
     link. Disable and Enable ask first. RouteSweepIT finds this page by
     #agentPage. --%>
<div id="agentPage">

<p class="subtitle"><spring:message code="agent.subtitle" arguments="${fn:escapeXml(bean.userName)}"/></p>

<h3 class="section-head"><spring:message code="agent.details"/></h3>
<form method="post" action="<c:url value='/roller-ui/admin/agent!save.rol'/>" class="form-stacked" id="agentDetailsForm">
    <sec:csrfInput/>
    <input type="hidden" name="bean.userName" value="${fn:escapeXml(bean.userName)}"/>

    <div class="mb-3">
        <label for="agentUserName" class="form-label"><spring:message code="agent.userName"/></label>
        <input type="text" class="form-control data" id="agentUserName" readonly="readonly"
               value="${fn:escapeXml(bean.userName)}"/>
    </div>

    <div class="mb-3">
        <label for="bean_name" class="form-label"><spring:message code="agent.name"/></label>
        <input type="text" class="form-control" id="bean_name" name="bean.name" maxlength="255"
               value="${fn:escapeXml(bean.name)}"/>
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

    <button type="submit" class="btn btn-primary" id="agentSaveButton"><spring:message code="generic.save"/></button>
</form>

<h3 class="section-head"><spring:message code="agent.status"/></h3>
<form method="post" action="<c:url value='/roller-ui/admin/agent!setEnabled.rol'/>"
      class="d-flex flex-wrap align-items-center gap-3" id="agentEnabledForm">
    <sec:csrfInput/>
    <input type="hidden" name="userName" value="${fn:escapeXml(bean.userName)}"/>
    <c:choose>
        <c:when test="${agentEnabled}">
            <span class="status-pill status-enabled"><spring:message code="userList.status.enabled"/></span>
            <input type="hidden" name="enabled" value="false"/>
            <spring:message code="agent.disable.confirm" arguments="${fn:escapeXml(bean.userName)}" var="disableConfirm"/>
            <button type="submit" class="btn btn-danger" id="agentDisableButton" data-confirm="${disableConfirm}">
                <spring:message code="agent.disable"/>
            </button>
            <span class="form-text"><spring:message code="agent.enabledNote"/></span>
        </c:when>
        <c:otherwise>
            <span class="status-pill status-disabled"><spring:message code="userList.status.disabled"/></span>
            <input type="hidden" name="enabled" value="true"/>
            <spring:message code="agent.enable.confirm" arguments="${fn:escapeXml(bean.userName)}" var="enableConfirm"/>
            <button type="submit" class="btn btn-secondary" id="agentEnableButton" data-confirm="${enableConfirm}">
                <spring:message code="agent.enable"/>
            </button>
            <span class="form-text"><spring:message code="agent.disabledNote"/></span>
        </c:otherwise>
    </c:choose>
</form>

<jsp:include page="/WEB-INF/jsps/admin/UserWeblogs.jsp"/>

<h3 class="section-head"><spring:message code="agent.tokens"/></h3>
<jsp:include page="/WEB-INF/jsps/core/TokenList.jsp"/>

<h3 class="section-head"><spring:message code="apiTokens.new"/></h3>
<jsp:include page="/WEB-INF/jsps/core/TokenForm.jsp"/>

</div>
