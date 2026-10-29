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

    <h1 class="pressroom-page-title"><spring:message code="installer.errorUpgradingTables"/></h1>
    <p><spring:message code="installer.errorUpgradingTablesExplanation"/></p>
<pre>
<c:forEach items="${messages}" var="msg">${fn:escapeXml(msg)}<br/></c:forEach>
</pre>

</c:when>
<c:when test="${upgradeRequired}">

    <h1 class="pressroom-page-title"><spring:message code="installer.databaseUpgradeNeeded"/></h1>

    <p>
        <spring:message code="installer.databaseUpgradeNeededExplanation" arguments="${databaseProductName}"/>
    </p>
    <p><spring:message code="installer.upgradeTables"/></p>

    <form action="${pageContext.request.contextPath}/pressroom-ui/install/install!upgrade.rol" method="post">
        <sec:csrfInput/>
        <spring:message code="installer.yesUpgradeTables" var="upgradeTablesLabel"/>
        <button type="submit" class="btn btn-primary">${upgradeTablesLabel}</button>
    </form>

</c:when>
<c:otherwise>

    <h1 class="pressroom-page-title"><spring:message code="installer.tablesUpgraded"/></h1>

    <p><spring:message code="installer.tablesUpgradedExplanation"/></p>

<pre>
<c:forEach items="${messages}" var="msg">${fn:escapeXml(msg)}<br/></c:forEach>
</pre>

    <jsp:include page="/WEB-INF/jsps/core/InstallStart.jsp"/>

</c:otherwise>
</c:choose>
