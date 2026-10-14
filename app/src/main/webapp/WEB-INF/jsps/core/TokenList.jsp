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

<%-- An account's live tokens (spec 2026-10-13, section 3), included by the
     agent page and Profile -> API tokens. The controller sets tokens,
     tokenRevokeAction and tokenFormOwner. Revoke asks first. --%>
<c:choose>
    <c:when test="${not empty tokens}">
        <spring:message code="apiTokens.revoke.confirm" var="revokeConfirm"/>
        <table class="rollertable table" id="tokenListTable">
            <tr>
                <th scope="col"><spring:message code="apiTokens.label"/></th>
                <th scope="col"><spring:message code="apiTokens.role"/></th>
                <th scope="col"><spring:message code="apiTokens.weblog"/></th>
                <th scope="col"><spring:message code="apiTokens.created"/></th>
                <th scope="col"><spring:message code="apiTokens.lastUsed"/></th>
                <th scope="col"><spring:message code="apiTokens.expiry"/></th>
                <th scope="col"><span class="visually-hidden"><spring:message code="apiTokens.revoke"/></span></th>
            </tr>
            <c:forEach var="token" items="${tokens}">
                <tr>
                    <td>${fn:escapeXml(token.label)}</td>
                    <td>
                        <c:choose>
                            <c:when test="${token.scopeRole == 'READ'}"><spring:message code="apiTokens.role.read"/></c:when>
                            <c:when test="${token.scopeRole == 'ADMIN'}"><spring:message code="apiTokens.role.admin"/></c:when>
                            <c:otherwise><spring:message code="apiTokens.role.write"/></c:otherwise>
                        </c:choose>
                    </td>
                    <td>
                        <c:choose>
                            <c:when test="${empty token.scopeWeblog}"><spring:message code="apiTokens.weblog.all"/></c:when>
                            <c:otherwise><span class="data">${fn:escapeXml(token.scopeWeblog)}</span></c:otherwise>
                        </c:choose>
                    </td>
                    <td class="data"><rc:date value="${token.created}"/></td>
                    <td class="data"><rc:date value="${token.lastUsedAt}"/></td>
                    <td class="data">
                        <c:choose>
                            <c:when test="${empty token.expiresAt}"><spring:message code="apiTokens.expiry.never"/></c:when>
                            <c:otherwise><rc:date value="${token.expiresAt}"/></c:otherwise>
                        </c:choose>
                    </td>
                    <td>
                        <form method="post" action="<c:url value='${tokenRevokeAction}'/>">
                            <sec:csrfInput/>
                            <c:if test="${not empty tokenFormOwner}">
                                <input type="hidden" name="userName" value="${fn:escapeXml(tokenFormOwner)}"/>
                            </c:if>
                            <input type="hidden" name="id" value="${fn:escapeXml(token.id)}"/>
                            <button type="submit" class="btn btn-secondary btn-sm" data-confirm="${revokeConfirm}">
                                <spring:message code="apiTokens.revoke"/>
                            </button>
                        </form>
                    </td>
                </tr>
            </c:forEach>
        </table>
    </c:when>
    <c:otherwise>
        <div class="empty-state" id="tokenListEmpty">
            <p class="empty-state-title"><spring:message code="apiTokens.none.title"/></p>
            <p class="empty-state-body"><spring:message code="apiTokens.none.body"/></p>
        </div>
    </c:otherwise>
</c:choose>
