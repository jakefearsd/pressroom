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

<p class="subtitle"><spring:message code="businesses.subtitle"/></p>

<div id="businesses-list-marker">

<c:url var="newBusinessUrl" value="/pressroom-ui/admin/businesses!edit.rol"/>
<c:choose>
<c:when test="${empty businesses}">
    <%-- An invitation: the one action lives inside the empty state, and
         #business-new (BusinessProfileIT) is on whichever button renders. --%>
    <div class="empty-state">
        <p class="empty-state-title"><spring:message code="businesses.empty"/></p>
        <p class="empty-state-body"><spring:message code="businesses.empty.body"/></p>
        <a href="${newBusinessUrl}" class="btn btn-primary" id="business-new"><spring:message code="businesses.new"/></a>
    </div>
</c:when>
<c:otherwise>
<div class="list-actions">
    <a href="${newBusinessUrl}" class="btn btn-primary btn-sm" id="business-new"><spring:message code="businesses.new"/></a>
</div>
<spring:message code="generic.edit" var="editLabel"/>
<spring:message code="generic.delete" var="deleteLabel"/>
<spring:message code="mainPage.actions" var="actionsLabel"/>
<div class="table-responsive">
<table class="pressroomtable table table-striped" width="100%">
    <tr>
        <th scope="col"><spring:message code="businesses.name"/></th>
        <th scope="col"><spring:message code="businesses.type"/></th>
        <th scope="col"><spring:message code="businesses.usedBy"/></th>
        <th scope="col" class="row-actions"><span class="visually-hidden">${actionsLabel}</span></th>
    </tr>
    <c:forEach var="b" items="${businesses}">
    <tr>
        <td>${fn:escapeXml(b.name)}</td>
        <td><spring:message code="businesses.type.${b.businessType}"/></td>
        <td>${fn:escapeXml(usage[b.id])}</td>
        <td class="row-actions">
            <c:url var="editBusinessUrl" value="/pressroom-ui/admin/businesses!edit.rol">
                <c:param name="id" value="${b.id}"/>
            </c:url>
            <a href="${editBusinessUrl}" class="row-action"
               aria-label="${editLabel}: ${fn:escapeXml(b.name)}"><span class="bi bi-pencil-square" aria-hidden="true"></span></a>
            <%-- The prompt names the business: formatted with the RAW name,
                 then escaped once for the attribute (see pressroom.js on why
                 data-confirm is safe with fn:escapeXml). --%>
            <spring:message code="businesses.delete.confirm" arguments="${[b.name]}" var="businessDeleteConfirm"/>
            <form method="post" action="<c:url value='/pressroom-ui/admin/businesses!delete.rol'/>">
                <sec:csrfInput/>
                <input type="hidden" name="id" value="${fn:escapeXml(b.id)}"/>
                <button type="submit" class="row-action row-action-danger"
                        data-confirm="${fn:escapeXml(businessDeleteConfirm)}"
                        aria-label="${deleteLabel}: ${fn:escapeXml(b.name)}"><span class="bi bi-trash" aria-hidden="true"></span></button>
            </form>
        </td>
    </tr>
    </c:forEach>
</table>
</div>
</c:otherwise>
</c:choose>

</div>
