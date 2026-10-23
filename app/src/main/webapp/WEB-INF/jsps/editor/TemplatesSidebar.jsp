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

<div class="sidebar-group">
<p class="sidebar-label"><spring:message code="pagesForm.addNewPage"/></p>

<form id="templateAdd" action="${pageContext.request.contextPath}/roller-ui/authoring/templates!add.rol" method="post" class="form-stacked">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>

    <label for="newTmplName"><spring:message code="generic.name"/></label>
    <input id="newTmplName" type="text" name="newTmplName" value="${fn:escapeXml(newTmplName)}" maxlength="255" class="form-control"/>

    <c:if test="${not empty availableActions}">
        <label for="newTmplAction"><spring:message code="pagesForm.action"/></label>
        <select id="newTmplAction" name="newTmplAction" class="form-select">
<c:forEach items="${availableActions}" var="opt">
<option value="${opt.key}" ${opt.key == newTmplAction ? 'selected' : ''}>${opt.value}</option>
</c:forEach>
</select>
    </c:if>

    <button type="submit" class="btn btn-secondary"><spring:message code="pagesForm.add"/></button>

<sec:csrfInput/>
</form>
</div>

