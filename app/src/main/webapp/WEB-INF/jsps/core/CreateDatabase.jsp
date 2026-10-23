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

<c:choose>
<c:when test="${error}">

    <h1 class="roller-page-title"><spring:message code="installer.errorCreatingTables" /></h1>
    <p><spring:message code="installer.errorCreatingTablesExplanation" /></p>
<pre>
<c:forEach items="${messages}" var="msg">${fn:escapeXml(msg)}<br /></c:forEach>
</pre>

</c:when>
<c:when test="${success}">

    <h1 class="roller-page-title"><spring:message code="installer.tablesCreated" /></h1>

    <p><spring:message code="installer.tablesCreatedExplanation" /></p>

<pre>
<c:forEach items="${messages}" var="msg">${fn:escapeXml(msg)}<br /></c:forEach>
</pre>

    <jsp:include page="/WEB-INF/jsps/core/InstallStart.jsp"/>

</c:when>
<c:otherwise>

    <h1 class="roller-page-title"><spring:message code="installer.noDatabaseTablesFound" /></h1>

    <p>
        <spring:message code="installer.noDatabaseTablesExplanation" arguments="${databaseProductName}" />
    </p>
    <p><spring:message code="installer.createTables" /></p>

    <form action="${pageContext.request.contextPath}/roller-ui/install/install!create.rol" method="post">
        <sec:csrfInput/>
        <spring:message code="installer.yesCreateTables" var="createTablesLabel"/>
        <button type="submit" class="btn btn-primary">${createTablesLabel}</button>
    </form>

</c:otherwise>
</c:choose>

<div class="mb-4"></div>
