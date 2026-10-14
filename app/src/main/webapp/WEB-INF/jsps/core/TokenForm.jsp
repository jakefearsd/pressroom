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

<%-- The token form (spec 2026-10-13, section 3; docs/design/forms/forms-token-reveal.html),
     included by the agent page and Profile -> API tokens. The controller sets
     tokenFormAction, tokenFormOwner (an agent's username, or nothing),
     pinChoices and tokenFormValues. The weblog field is "pin", never
     "weblog": RollerHandlerInterceptor reads that as the action weblog. --%>
<form method="post" action="<c:url value='${tokenFormAction}'/>" class="form-stacked" id="tokenForm">
    <sec:csrfInput/>
    <c:if test="${not empty tokenFormOwner}">
        <input type="hidden" name="userName" value="${fn:escapeXml(tokenFormOwner)}"/>
    </c:if>

    <div class="mb-3">
        <label for="tokenLabel" class="form-label"><spring:message code="apiTokens.label"/></label>
        <input type="text" class="form-control" id="tokenLabel" name="label" maxlength="255"
               value="${fn:escapeXml(tokenFormValues.label)}"/>
    </div>

    <div class="mb-3">
        <label for="tokenRole" class="form-label"><spring:message code="apiTokens.role"/></label>
        <select class="form-select" id="tokenRole" name="role">
            <option value="READ" <c:if test="${tokenFormValues.role == 'READ'}">selected="selected"</c:if>><spring:message code="apiTokens.role.read"/></option>
            <option value="POST" <c:if test="${tokenFormValues.role == 'POST'}">selected="selected"</c:if>><spring:message code="apiTokens.role.write"/></option>
            <option value="ADMIN" <c:if test="${tokenFormValues.role == 'ADMIN'}">selected="selected"</c:if>><spring:message code="apiTokens.role.admin"/></option>
        </select>
    </div>

    <div class="mb-3">
        <label for="tokenPin" class="form-label"><spring:message code="apiTokens.weblog"/></label>
        <select class="form-select" id="tokenPin" name="pin">
            <option value=""><spring:message code="apiTokens.weblog.all"/></option>
            <c:forEach var="choice" items="${pinChoices}">
                <option value="${fn:escapeXml(choice.handle)}"
                    <c:if test="${choice.handle == tokenFormValues.pin}">selected="selected"</c:if>
                >${fn:escapeXml(choice.name)} (${fn:escapeXml(choice.handle)})</option>
            </c:forEach>
        </select>
    </div>

    <div class="mb-3">
        <label for="tokenExpiry" class="form-label"><spring:message code="apiTokens.expiry"/></label>
        <select class="form-select" id="tokenExpiry" name="expiry">
            <option value="30" <c:if test="${tokenFormValues.expiry == '30'}">selected="selected"</c:if>><spring:message code="apiTokens.expiry.30"/></option>
            <option value="90" <c:if test="${tokenFormValues.expiry == '90'}">selected="selected"</c:if>><spring:message code="apiTokens.expiry.90"/></option>
            <option value="365" <c:if test="${tokenFormValues.expiry == '365'}">selected="selected"</c:if>><spring:message code="apiTokens.expiry.365"/></option>
            <option value="never" <c:if test="${tokenFormValues.expiry == 'never'}">selected="selected"</c:if>><spring:message code="apiTokens.expiry.never"/></option>
        </select>
    </div>

    <button type="submit" class="btn btn-secondary" id="tokenCreateButton"><spring:message code="apiTokens.create"/></button>
</form>
