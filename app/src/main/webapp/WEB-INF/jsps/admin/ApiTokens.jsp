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

<%-- Admin -> API tokens (spec 2026-10-13, section 3; criterion 13): every live
     token on the site; the toggle adds revoked and expired ones. Revoke asks
     first. RouteSweepIT finds this page by #apiTokensPage. --%>
<div id="apiTokensPage">

<p class="subtitle"><spring:message code="apiTokens.admin.subtitle"/></p>

<c:choose>
    <c:when test="${showAll}">
        <c:url var="toggleUrl" value="/pressroom-ui/admin/apiTokens.rol"/>
        <p><a href="${toggleUrl}" id="apiTokensToggle"><spring:message code="apiTokens.admin.showLive"/></a></p>
    </c:when>
    <c:otherwise>
        <c:url var="toggleUrl" value="/pressroom-ui/admin/apiTokens.rol">
            <c:param name="all" value="true"/>
        </c:url>
        <p><a href="${toggleUrl}" id="apiTokensToggle"><spring:message code="apiTokens.admin.showAll"/></a></p>
    </c:otherwise>
</c:choose>

<c:choose>
    <c:when test="${not empty tokenRows}">
        <spring:message code="apiTokens.revoke.confirm" var="revokeConfirm"/>
        <div class="table-responsive">
        <table class="pressroomtable table" id="apiTokensTable">
            <tr>
                <th scope="col"><spring:message code="apiTokens.owner"/></th>
                <th scope="col"><spring:message code="userList.column.kind"/></th>
                <th scope="col"><spring:message code="apiTokens.label"/></th>
                <th scope="col"><spring:message code="apiTokens.role"/></th>
                <th scope="col"><spring:message code="apiTokens.weblog"/></th>
                <th scope="col"><spring:message code="apiTokens.created"/></th>
                <th scope="col"><spring:message code="apiTokens.lastUsed"/></th>
                <th scope="col"><spring:message code="apiTokens.expiry"/></th>
                <th scope="col"><spring:message code="apiTokens.state"/></th>
            </tr>
            <c:forEach var="row" items="${tokenRows}">
                <tr>
                    <td>
                        ${fn:escapeXml(row.token.user.userName)}
                        <%-- Issued by is usually the owner again; a column of
                             its own for that squeezed the table. --%>
                        <c:if test="${not empty row.issuedBy and row.issuedBy != row.token.user.userName}">
                            <div class="entry-meta"><spring:message code="apiTokens.issuedBy"/>: <span class="data">${fn:escapeXml(row.issuedBy)}</span></div>
                        </c:if>
                    </td>
                    <td>
                        <c:choose>
                            <c:when test="${row.token.user.agent}"><spring:message code="userList.kind.agent"/></c:when>
                            <c:otherwise><spring:message code="userList.kind.person"/></c:otherwise>
                        </c:choose>
                    </td>
                    <td>${fn:escapeXml(row.token.label)}</td>
                    <td>
                        <c:choose>
                            <c:when test="${row.token.scopeRole == 'READ'}"><spring:message code="apiTokens.role.read"/></c:when>
                            <c:when test="${row.token.scopeRole == 'ADMIN'}"><spring:message code="apiTokens.role.admin"/></c:when>
                            <c:otherwise><spring:message code="apiTokens.role.write"/></c:otherwise>
                        </c:choose>
                    </td>
                    <td>
                        <c:choose>
                            <c:when test="${empty row.token.scopeWeblog}"><spring:message code="apiTokens.weblog.all"/></c:when>
                            <c:otherwise><span class="data">${fn:escapeXml(row.token.scopeWeblog)}</span></c:otherwise>
                        </c:choose>
                    </td>
                    <td class="data text-nowrap"><rc:date value="${row.token.created}"/></td>
                    <td class="data text-nowrap">
                        <c:choose>
                            <c:when test="${empty row.token.lastUsedAt}"><span class="data-none"><spring:message code="apiTokens.lastUsed.never"/></span></c:when>
                            <c:otherwise><rc:date value="${row.token.lastUsedAt}"/></c:otherwise>
                        </c:choose>
                    </td>
                    <td class="data text-nowrap">
                        <c:choose>
                            <c:when test="${empty row.token.expiresAt}"><span class="data-none"><spring:message code="apiTokens.expiry.never"/></span></c:when>
                            <c:otherwise><rc:date value="${row.token.expiresAt}"/></c:otherwise>
                        </c:choose>
                    </td>
                    <td>
                        <c:choose>
                            <c:when test="${not empty row.token.revokedAt}">
                                <spring:message code="apiTokens.state.revoked"/> <rc:date value="${row.token.revokedAt}"/>
                            </c:when>
                            <c:when test="${not row.live}">
                                <spring:message code="apiTokens.state.expired"/>
                            </c:when>
                            <c:otherwise>
                                <form method="post" action="<c:url value='/pressroom-ui/admin/apiTokens!revoke.rol'/>">
                                    <sec:csrfInput/>
                                    <input type="hidden" name="id" value="${fn:escapeXml(row.token.id)}"/>
                                    <c:if test="${showAll}"><input type="hidden" name="all" value="true"/></c:if>
                                    <button type="submit" class="btn btn-secondary btn-sm" data-confirm="${revokeConfirm}">
                                        <spring:message code="apiTokens.revoke"/>
                                    </button>
                                </form>
                            </c:otherwise>
                        </c:choose>
                    </td>
                </tr>
            </c:forEach>
        </table>
        </div>
    </c:when>
    <c:otherwise>
        <div class="empty-state">
            <p class="empty-state-title"><spring:message code="apiTokens.admin.none"/></p>
        </div>
    </c:otherwise>
</c:choose>

</div>
