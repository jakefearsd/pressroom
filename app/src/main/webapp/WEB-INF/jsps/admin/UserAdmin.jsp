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

<%-- Admin -> Users (spec 2026-10-13, section 4; docs/design/tables/tables-users.html):
     every account, people and agents, with All / People / Agents tabs and a
     filter the server applies. RouteSweepIT finds this page by #userListPage;
     the ITs open an account by its link's data-username. --%>
<div id="userListPage">

<p class="subtitle"><spring:message code="userAdmin.subtitle.searchUser"/></p>

<div class="d-flex flex-wrap gap-2 mb-3">
    <a class="btn btn-primary btn-sm" id="newPersonLink"
       href="<c:url value='/roller-ui/admin/createUser.rol'/>"><spring:message code="userList.newPerson"/></a>
    <a class="btn btn-secondary btn-sm" id="newAgentLink"
       href="<c:url value='/roller-ui/admin/createAgent.rol'/>"><spring:message code="userList.newAgent"/></a>
</div>

<c:url var="allTabUrl" value="/roller-ui/admin/userAdmin.rol">
    <c:param name="q" value="${textFilter}"/>
</c:url>
<c:url var="peopleTabUrl" value="/roller-ui/admin/userAdmin.rol">
    <c:param name="kind" value="people"/>
    <c:param name="q" value="${textFilter}"/>
</c:url>
<c:url var="agentsTabUrl" value="/roller-ui/admin/userAdmin.rol">
    <c:param name="kind" value="agents"/>
    <c:param name="q" value="${textFilter}"/>
</c:url>
<spring:message code="userList.tabs" var="tabsLabel"/>
<nav class="nav nav-tabs mb-3" aria-label="${fn:escapeXml(tabsLabel)}">
    <a class="nav-link${empty kindFilter ? ' active' : ''}" id="userListTabAll" href="${allTabUrl}"
       <c:if test="${empty kindFilter}">aria-current="page"</c:if>><spring:message code="userList.tab.all"/></a>
    <a class="nav-link${kindFilter == 'people' ? ' active' : ''}" id="userListTabPeople" href="${peopleTabUrl}"
       <c:if test="${kindFilter == 'people'}">aria-current="page"</c:if>><spring:message code="userList.tab.people"/></a>
    <a class="nav-link${kindFilter == 'agents' ? ' active' : ''}" id="userListTabAgents" href="${agentsTabUrl}"
       <c:if test="${kindFilter == 'agents'}">aria-current="page"</c:if>><spring:message code="userList.tab.agents"/></a>
</nav>

<spring:message code="userList.filter" var="filterLabel"/>
<form method="get" action="<c:url value='/roller-ui/admin/userAdmin.rol'/>"
      class="d-flex flex-wrap gap-2 mb-3" id="userListFilterForm">
    <input type="hidden" name="kind" value="${fn:escapeXml(kindFilter)}"/>
    <label for="userListFilter" class="visually-hidden">${fn:escapeXml(filterLabel)}</label>
    <input type="search" class="form-control" id="userListFilter" name="q"
           value="${fn:escapeXml(textFilter)}" placeholder="${fn:escapeXml(filterLabel)}"/>
    <button type="submit" class="btn btn-secondary" id="userListFilterSubmit">
        <spring:message code="userList.filter.apply"/>
    </button>
</form>

<c:choose>
    <c:when test="${not empty accounts}">
        <table class="rollertable table" id="userListTable">
            <tr>
                <th scope="col"><spring:message code="userList.column.account"/></th>
                <th scope="col"><spring:message code="userList.column.kind"/></th>
                <th scope="col"><spring:message code="userList.column.role"/></th>
                <th scope="col"><spring:message code="userList.column.status"/></th>
                <th scope="col"><spring:message code="userList.column.weblogs"/></th>
                <th scope="col"><spring:message code="userList.column.lastActive"/></th>
            </tr>
            <c:forEach var="row" items="${accounts}">
                <c:choose>
                    <c:when test="${row.agent}">
                        <c:url var="accountUrl" value="/roller-ui/admin/agent.rol">
                            <c:param name="userName" value="${row.userName}"/>
                        </c:url>
                    </c:when>
                    <c:otherwise>
                        <c:url var="accountUrl" value="/roller-ui/admin/modifyUser.rol">
                            <c:param name="bean.userName" value="${row.userName}"/>
                        </c:url>
                    </c:otherwise>
                </c:choose>
                <tr>
                    <td>
                        <a href="${accountUrl}" data-username="${fn:escapeXml(row.userName)}">${fn:escapeXml(row.name)}</a>
                        <br/><span class="data">${fn:escapeXml(row.userName)}</span>
                    </td>
                    <td>
                        <c:choose>
                            <c:when test="${row.agent}"><spring:message code="userList.kind.agent"/></c:when>
                            <c:otherwise><spring:message code="userList.kind.person"/></c:otherwise>
                        </c:choose>
                    </td>
                    <td>
                        <c:choose>
                            <c:when test="${row.role == 'SITE_ADMIN'}"><spring:message code="userList.role.siteAdmin"/></c:when>
                            <c:when test="${row.role == 'AUTHOR'}"><spring:message code="userList.role.author"/></c:when>
                            <c:otherwise><spring:message code="userList.role.reader"/></c:otherwise>
                        </c:choose>
                    </td>
                    <td>
                        <c:choose>
                            <c:when test="${row.enabled}"><span class="status-pill status-enabled"><spring:message code="userList.status.enabled"/></span></c:when>
                            <c:otherwise><span class="status-pill status-disabled"><spring:message code="userList.status.disabled"/></span></c:otherwise>
                        </c:choose>
                    </td>
                    <td class="data">${row.weblogCount}</td>
                    <td class="data"><rc:date value="${row.lastActive}"/></td>
                </tr>
            </c:forEach>
        </table>
    </c:when>
    <c:otherwise>
        <div class="empty-state">
            <p class="empty-state-title"><spring:message code="userList.empty.title"/></p>
            <p class="empty-state-body"><spring:message code="userList.empty.body"/></p>
        </div>
    </c:otherwise>
</c:choose>

</div>
