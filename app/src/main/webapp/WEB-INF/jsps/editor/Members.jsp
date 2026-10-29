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

<p class="subtitle">
    <spring:message code="memberPermissions.subtitle" arguments="${actionWeblog.handle}"/>
</p>

<p><spring:message code="memberPermissions.description"/></p>

<%-- Grants an existing account access to this weblog immediately -- no
     invitation, no acceptance step. Its own form/CSRF, separate from the
     table below's.

     Hidden when groupblogging.enabled is off, matching the refusal
     MembersController.grant() enforces server-side; the table below stays so
     an operator who turns the setting off can still revoke the members it
     left behind. This page is reachable by URL even with the setting off --
     the menu gate only hides the tab -- so the form has to be gated here too,
     not merely left off the menu. --%>
<c:if test="${rc:getBooleanProp('groupblogging.enabled')}">
<form class="d-flex flex-wrap align-items-end gap-2 mb-4"
      action="${pageContext.request.contextPath}/pressroom-ui/authoring/members!grant.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <div>
        <label for="grantUserName" class="form-label">
            <spring:message code="memberPermissions.userName"/>
        </label>
        <input type="text" class="form-control" name="userName" id="grantUserName"
               size="30" maxlength="255"/>
    </div>
    <div>
        <label for="grantPermission" class="form-label">
            <spring:message code="yourWebsites.permission"/>
        </label>
        <select class="form-select" name="permissionString" id="grantPermission">
            <%-- The house order, the table's: Administrator, Author, Limited. --%>
            <option value="admin">
                <spring:message code="memberPermissions.administrator"/>
            </option>
            <option value="post" selected>
                <spring:message code="memberPermissions.author"/>
            </option>
            <option value="edit_draft">
                <spring:message code="memberPermissions.limited"/>
            </option>
        </select>
    </div>
    <button type="submit" class="btn btn-secondary"><spring:message code="memberPermissions.add"/></button>
<sec:csrfInput/>
</form>
</c:if>

<%-- data-confirm on the FORM, not a control: whether removing anyone is even
     happening depends on which of several radios across the whole table is
     checked, which no single control can answer for itself. data-confirm-when
     is pressroom.js's submit-handler extension for exactly this case -- prompt
     only while the form currently has a match for the selector -- so saving
     with no "-1" (Remove) radio checked needs no confirmation at all. --%>
<spring:message code="memberPermissions.confirmRemove" var="memberRemovalConfirm"/>
<form id="memberPermissionsForm"
      data-confirm="${memberRemovalConfirm}" data-confirm-when="input[value='-1']:checked"
      action="${pageContext.request.contextPath}/pressroom-ui/authoring/members!save.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>

    <%-- Table is gated on there being members to show; the <form>
         itself stays unconditional because RouteSweepIT identifies this route
         by "form[action$='/pressroom-ui/authoring/members!save.rol']"
         (Routes.java). --%>
    <c:if test="${not empty weblogPermissions}">

    <div class="table-responsive">
    <table class="pressroomtable table table-striped">
        <tr>
           <th scope="col" class="pressroomtable" width="20%">
               <spring:message code="memberPermissions.userName"/>
           </th>
           <th scope="col" class="pressroomtable" width="20%">
               <spring:message code="memberPermissions.administrator"/>
           </th>
           <th scope="col" class="pressroomtable" width="20%">
               <spring:message code="memberPermissions.author"/>
           </th>
           <th scope="col" class="pressroomtable" width="20%">
               <spring:message code="memberPermissions.limited"/>
           </th>
           <th scope="col" class="pressroomtable" width="20%">
               <spring:message code="memberPermissions.remove"/>
           </th>
        </tr>
        <c:forEach items="${weblogPermissions}" var="perm">
                <%-- The caller's own row: "you are not allowed to demote or
                     remove yourself" (the save refuses it), so only the admin
                     radio stays live. It must: a disabled radio is not
                     submitted, and the save needs the one admin. --%>
                <c:set var="selfRow" value="${perm.user.userName == authenticatedUser.userName}"/>
                <tr>
                <td class="pressroomtable">
                    <span class="bi bi-person" aria-hidden="true"></span>
	                ${fn:escapeXml(perm.user.userName)}
                </td>
                <td class="pressroomtable">
                    <input type="radio" 
                        <c:if test='${perm.hasAction("admin")}'>checked</c:if>
                        name='perm-${perm.user.id}' value="admin"
                        aria-label="<spring:message code='memberPermissions.administrator'/>: ${fn:escapeXml(perm.user.userName)}" />
                </td>
                <td class="pressroomtable">
	                <input type="radio" 
                        <c:if test='${perm.hasAction("post")}'>checked</c:if>
                        <c:if test="${selfRow}">disabled</c:if>
                        name='perm-${perm.user.id}' value="post"
                        aria-label="<spring:message code='memberPermissions.author'/>: ${fn:escapeXml(perm.user.userName)}" />
                </td>                
                <td class="pressroomtable">
                    <input type="radio" 
                        <c:if test='${perm.hasAction("edit_draft")}'>checked</c:if>
                        <c:if test="${selfRow}">disabled</c:if>
                        name='perm-${perm.user.id}' value="edit_draft"
                        aria-label="<spring:message code='memberPermissions.limited'/>: ${fn:escapeXml(perm.user.userName)}" />
                </td>                
                <td class="pressroomtable">
                    <input type="radio"
                        <c:if test="${selfRow}">disabled</c:if>
                        name='perm-${perm.user.id}' value="-1"
                        aria-label="<spring:message code='memberPermissions.remove'/>: ${fn:escapeXml(perm.user.userName)}" />
                </td>
           </tr>
       </c:forEach>
    </table>
    </div>

    <div class="control">
       <button type="submit" class="btn btn-primary"><spring:message code="generic.save"/></button>
    </div>

    </c:if>

    <c:if test="${empty weblogPermissions}">
        <div class="empty-state">
            <p class="empty-state-title"><spring:message code="empty.members.title"/></p>
            <p class="empty-state-body"><spring:message code="empty.members.body"/></p>
        </div>
    </c:if>


<sec:csrfInput/>
</form>
