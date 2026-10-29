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

<%-- The Weblogs section of an account's page, person or agent (spec
     2026-10-13, section 4; docs/design/forms/forms-agent-page.html), included
     with jsp:include. The page's model carries permissions (the rows) and
     UserWeblogsModel's weblogsAccount, groupBloggingEnabled and
     availableWeblogs. Levels and labels are the Members page's own. The
     weblog field is "handle", never "weblog". Remove asks first. --%>
<section id="userWeblogsSection" class="mt-4">
<h2 class="section-head"><spring:message code="userAdmin.userWeblogs"/></h2>

<c:choose>
    <c:when test="${not empty permissions}">
        <div class="table-responsive">
        <table class="pressroomtable table" id="userWeblogsTable">
            <tr>
                <th scope="col"><spring:message code="generic.name"/></th>
                <th scope="col"><spring:message code="userWeblogs.level"/></th>
                <th scope="col"><span class="visually-hidden"><spring:message code="userWeblogs.remove"/></span></th>
            </tr>
            <c:forEach var="perms" items="${permissions}">
                <c:choose>
                    <c:when test="${perms.hasAction('admin')}"><c:set var="currentLevel" value="admin"/></c:when>
                    <c:when test="${perms.hasAction('post')}"><c:set var="currentLevel" value="post"/></c:when>
                    <c:otherwise><c:set var="currentLevel" value="edit_draft"/></c:otherwise>
                </c:choose>
                <tr>
                    <td>
                        <a href="${urls.weblogAbsolute(perms.weblog)}">${fn:escapeXml(perms.weblog.name)}</a>
                        <span class="data">${fn:escapeXml(perms.weblog.handle)}</span>
                    </td>
                    <td>
                        <form method="post" action="<c:url value='/pressroom-ui/admin/userWeblogs!change.rol'/>"
                              class="d-flex flex-wrap gap-2 align-items-center">
                            <sec:csrfInput/>
                            <input type="hidden" name="userName" value="${fn:escapeXml(weblogsAccount)}"/>
                            <input type="hidden" name="handle" value="${fn:escapeXml(perms.weblog.handle)}"/>
                            <label class="visually-hidden" for="level-${fn:escapeXml(perms.weblog.handle)}"><spring:message code="userWeblogs.level"/></label>
                            <select class="form-select form-select-sm" name="level" id="level-${fn:escapeXml(perms.weblog.handle)}">
                                <option value="admin" <c:if test="${currentLevel == 'admin'}">selected="selected"</c:if>><spring:message code="memberPermissions.administrator"/></option>
                                <option value="post" <c:if test="${currentLevel == 'post'}">selected="selected"</c:if>><spring:message code="memberPermissions.author"/></option>
                                <option value="edit_draft" <c:if test="${currentLevel == 'edit_draft'}">selected="selected"</c:if>><spring:message code="memberPermissions.limited"/></option>
                            </select>
                            <button type="submit" class="btn btn-secondary btn-sm"><spring:message code="userWeblogs.change"/></button>
                        </form>
                    </td>
                    <td>
                        <form method="post" action="<c:url value='/pressroom-ui/admin/userWeblogs!remove.rol'/>">
                            <sec:csrfInput/>
                            <input type="hidden" name="userName" value="${fn:escapeXml(weblogsAccount)}"/>
                            <input type="hidden" name="handle" value="${fn:escapeXml(perms.weblog.handle)}"/>
                            <spring:message code="userWeblogs.remove.confirm"
                                            arguments="${fn:escapeXml(perms.weblog.handle)}" var="removeConfirm"/>
                            <button type="submit" class="btn btn-secondary btn-sm" data-confirm="${removeConfirm}">
                                <spring:message code="userWeblogs.remove"/>
                            </button>
                        </form>
                    </td>
                </tr>
            </c:forEach>
        </table>
        </div>
    </c:when>
    <c:otherwise>
        <div class="empty-state">
            <p class="empty-state-title"><spring:message code="empty.userWeblogs.title"/></p>
            <%-- One sentence, pointing at the add form below; when there is no
                 form, the note below it says why, so the body says nothing. --%>
            <c:if test="${groupBloggingEnabled and not empty availableWeblogs}">
                <p class="empty-state-body"><spring:message code="userWeblogs.empty.body"/></p>
            </c:if>
        </div>
    </c:otherwise>
</c:choose>

<c:choose>
    <c:when test="${not groupBloggingEnabled}">
        <p class="form-text" id="userWeblogsGroupBloggingOff"><spring:message code="userWeblogs.groupBloggingOff"/></p>
    </c:when>
    <c:when test="${empty availableWeblogs}">
        <p class="form-text"><spring:message code="userWeblogs.noOtherWeblogs"/></p>
    </c:when>
    <c:otherwise>
        <form method="post" action="<c:url value='/pressroom-ui/admin/userWeblogs!grant.rol'/>"
              class="d-flex flex-wrap align-items-end gap-2 mt-3" id="userWeblogsAddForm">
            <sec:csrfInput/>
            <input type="hidden" name="userName" value="${fn:escapeXml(weblogsAccount)}"/>
            <div>
                <label for="userWeblogsAddHandle" class="form-label"><spring:message code="userWeblogs.weblog"/></label>
                <select class="form-select" name="handle" id="userWeblogsAddHandle">
                    <c:forEach var="choice" items="${availableWeblogs}">
                        <option value="${fn:escapeXml(choice.handle)}">${fn:escapeXml(choice.name)} (${fn:escapeXml(choice.handle)})</option>
                    </c:forEach>
                </select>
            </div>
            <div>
                <label for="userWeblogsAddLevel" class="form-label"><spring:message code="userWeblogs.level"/></label>
                <select class="form-select" name="level" id="userWeblogsAddLevel">
                    <option value="admin"><spring:message code="memberPermissions.administrator"/></option>
                    <option value="post" selected="selected"><spring:message code="memberPermissions.author"/></option>
                    <option value="edit_draft"><spring:message code="memberPermissions.limited"/></option>
                </select>
            </div>
            <button type="submit" class="btn btn-secondary" id="userWeblogsAddButton"><spring:message code="userWeblogs.add"/></button>
        </form>
    </c:otherwise>
</c:choose>
</section>
