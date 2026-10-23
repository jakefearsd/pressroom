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
    <spring:message code="weblogPagesForm.subtitle" arguments="${actionWeblog.handle}"/>
</p>

<c:url var="addUrl" value="/roller-ui/authoring/pageEdit.rol">
    <c:param name="weblog" value="${actionWeblog.handle}"/>
</c:url>
<%-- The list's one "New ..." action, above the table; an empty list carries
     it in its empty state instead. --%>
<div class="list-actions">
<c:if test="${not empty pages}">
    <a href="${addUrl}" class="btn btn-primary btn-sm">
        <spring:message code="weblogPagesForm.add"/>
    </a>
</c:if>
</div>

<%-- One form around the whole table, following Entries.jsp's pattern: the
     remove action is a submit button carrying its own name/value per row,
     rather than a nested form per row (which is not valid HTML). --%>
<spring:message code="weblogPagesForm.removeConfirm" var="pageDeleteConfirmBase"/>
<form id="pageRemoveForm" method="post"
      action="${pageContext.request.contextPath}/roller-ui/authoring/pageRemove.rol">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <sec:csrfInput/>

    <c:choose>
    <c:when test="${not empty pages}">

    <div class="table-responsive">
    <table class="rollertable table table-striped" width="100%">

            <tr>
                <th scope="col" class="rollertable"><spring:message code="weblogPagesForm.slug"/></th>
                <th scope="col" class="rollertable"><spring:message code="weblogEdit.title"/></th>
                <th scope="col" class="rollertable" width="10%"><spring:message code="weblogEdit.status"/></th>
                <th scope="col" class="rollertable" width="10%"><spring:message code="weblogPagesForm.showInNav"/></th>
                <th scope="col" class="rollertable" width="10%"><spring:message code="weblogPagesForm.navOrder"/></th>
                <th scope="col" class="rollertable row-actions"><span class="visually-hidden"><spring:message code="mainPage.actions"/></span></th>
            </tr>

            <c:forEach items="${pages}" var="p">
                <tr data-page-id="${fn:escapeXml(p.id)}" data-page-slug="${fn:escapeXml(p.slug)}">
                    <td class="data">
                        <c:choose>
                        <c:when test="${p.status.name() == 'PUBLISHED'}">
                            <%-- Only published pages are served; a draft's URL
                                 404s, so linking it would hand someone a
                                 broken link from their own admin screen.

                                 A page is served at /<handle>/<slug> -- a bare
                                 single segment. The "page/" this used to carry
                                 is the CUSTOM-*template* route, so the link was
                                 a 404 of exactly the kind the c:when above
                                 exists to avoid. Same construction as
                                 PageEdit.jsp's own address line, escape
                                 included: a slug is author-controlled and is
                                 restricted only against '/' and reserved names,
                                 never against quote or angle-bracket
                                 characters. --%>
                            <a href="${urls.weblogAbsolute(actionWeblog)}${fn:escapeXml(p.slug)}"
                               target="_blank" rel="noopener">/<c:out value="${p.slug}"/></a>
                        </c:when>
                        <c:otherwise>/<c:out value="${p.slug}"/></c:otherwise>
                        </c:choose>
                    </td>
                    <td>
                        <c:url var="editUrl" value="/roller-ui/authoring/pageEdit.rol">
                            <c:param name="weblog" value="${actionWeblog.handle}"/>
                            <c:param name="id" value="${p.id}"/>
                        </c:url>
                        <a href="${editUrl}"><c:out value="${p.title}"/></a>
                    </td>
                    <td>
                        <c:set var="pillStatus" value="${p.status.name()}" scope="request"/>
                        <c:remove var="pillWhen" scope="request"/>
                        <jsp:include page="/WEB-INF/jsps/editor/StatusPill.jsp"/>
                    </td>
                    <td>${p.showInNav ? '&#10003;' : ''}</td>
                    <td class="data">${p.navOrder}</td>
                    <td class="row-actions">
                        <a href="${editUrl}" class="row-action" aria-label="<spring:message code='generic.edit'/>: ${fn:escapeXml(p.title)}">
                            <span class="bi bi-pencil-square" aria-hidden="true" title="<spring:message code="generic.edit"/>"></span>
                        </a>
                        <%-- A real submit button carrying its own name/value, not
                             a hidden field a delegated click handler had to
                             populate first. data-confirm, not an inline
                             onclick/window.confirm: fn:escapeXml renders an
                             apostrophe as &#039;, which the HTML parser decodes
                             back to ' BEFORE a JS-string position compiles, so a
                             page titled e.g. "Maiia's bio" made the old
                             onclick-string approach a permanent SyntaxError. An
                             HTML attribute has no second parser, so data-confirm
                             is safe with the same escape. --%>
                        <button type="submit" class="row-action row-action-danger page-delete-btn"
                                name="removeId" value="${p.id}"
                                data-confirm="${pageDeleteConfirmBase}: '${fn:escapeXml(p.title)}'?"
                                aria-label="<spring:message code='generic.delete'/>: ${fn:escapeXml(p.title)}">
                            <span class="bi bi-trash" aria-hidden="true" title="<spring:message code="generic.delete"/>"></span>
                        </button>
                    </td>
                </tr>
            </c:forEach>

    </table>
    </div>

    </c:when>
    <c:otherwise>

    <div class="empty-state">
        <p class="empty-state-title"><spring:message code="empty.pages.title"/></p>
        <p class="empty-state-body"><spring:message code="empty.pages.body"/></p>
        <a href="${addUrl}" class="btn btn-primary">
            <spring:message code="weblogPagesForm.add"/>
        </a>
    </div>

    </c:otherwise>
    </c:choose>

</form>
