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
    <c:choose>
        <c:when test="${actionWeblog.siteSpace}"><spring:message code="weblogPagesForm.subtitle.site"/></c:when>
        <c:otherwise><spring:message code="weblogPagesForm.subtitle" arguments="${actionWeblog.handle}"/></c:otherwise>
    </c:choose>
</p>

<c:url var="addUrl" value="/pressroom-ui/authoring/pageEdit.rol">
    <c:param name="weblog" value="${actionWeblog.handle}"/>
</c:url>
<%-- The list's one "New ..." action, above the table; an empty list carries
     it in its empty state instead. --%>
<div class="list-actions">
<c:if test="${not empty pages}">
    <a href="${addUrl}" class="btn btn-primary btn-sm">
        <spring:message code="weblogPagesForm.add"/>
    </a>
    <%-- The order is its own form: the rows sit inside the remove form below
         and forms cannot nest, so each row's hidden pageIds names this form
         with the form attribute. The ids post in row order; the button shows
         once the rows no longer match the order the screen loaded with. --%>
    <form id="pagesOrderForm" method="post" class="pages-order-form"
          action="${pageContext.request.contextPath}/pressroom-ui/authoring/pagesOrder.rol">
        <input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
        <sec:csrfInput/>
        <button type="submit" id="pagesOrderSave" class="btn btn-primary btn-sm" hidden>
            <spring:message code="weblogPagesForm.saveOrder"/>
        </button>
    </form>
</c:if>
</div>

<%-- One form around the whole table, following Entries.jsp's pattern: the
     remove action is a submit button carrying its own name/value per row,
     rather than a nested form per row (which is not valid HTML). --%>
<spring:message code="weblogPagesForm.removeConfirm" var="pageDeleteConfirmBase"/>
<spring:message code="weblogPagesForm.moveUp" var="moveUpLabel"/>
<spring:message code="weblogPagesForm.moveDown" var="moveDownLabel"/>
<form id="pageRemoveForm" method="post"
      action="${pageContext.request.contextPath}/pressroom-ui/authoring/pageRemove.rol">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <sec:csrfInput/>

    <c:choose>
    <c:when test="${not empty pages}">

    <div class="table-responsive">
    <table class="pressroomtable table table-striped" width="100%">

            <thead>
            <tr>
                <th scope="col" class="pressroomtable"><spring:message code="weblogPagesForm.slug"/></th>
                <th scope="col" class="pressroomtable"><spring:message code="weblogEdit.title"/></th>
                <th scope="col" class="pressroomtable" width="10%"><spring:message code="weblogEdit.status"/></th>
                <th scope="col" class="pressroomtable" width="10%"><spring:message code="weblogPagesForm.showInNav"/></th>
                <th scope="col" class="pressroomtable" width="10%"><spring:message code="weblogPagesForm.navOrder"/></th>
                <th scope="col" class="pressroomtable row-actions"><span class="visually-hidden"><spring:message code="mainPage.actions"/></span></th>
            </tr>
            </thead>

            <tbody id="pagesList">
            <c:forEach items="${pages}" var="p">
                <tr class="page-row" draggable="true" data-page-id="${fn:escapeXml(p.id)}" data-page-slug="${fn:escapeXml(p.slug)}">
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
                                 exists to avoid. The page form (urls.page),
                                 so the blog's front page links the blog root
                                 it is served at. Escaped like PageEdit.jsp's
                                 own address line: a slug is author-controlled
                                 and is restricted only against '/' and
                                 reserved names, never against quote or
                                 angle-bracket characters. --%>
                            <a href="${fn:escapeXml(urls.page(actionWeblog, p))}"
                               target="_blank" rel="noopener">/<c:out value="${p.slug}"/></a>
                        </c:when>
                        <c:otherwise>/<c:out value="${p.slug}"/></c:otherwise>
                        </c:choose>
                    </td>
                    <td>
                        <c:url var="editUrl" value="/pressroom-ui/authoring/pageEdit.rol">
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
                    <td class="data page-nav-order">${p.navOrder}</td>
                    <td class="row-actions">
                        <input type="hidden" name="pageIds" form="pagesOrderForm" value="${fn:escapeXml(p.id)}"/>
                        <span class="bi bi-grip-vertical page-order-handle" aria-hidden="true"></span>
                        <button type="button" class="row-action page-order-up"
                                aria-label="${fn:escapeXml(moveUpLabel)}: ${fn:escapeXml(p.title)}"><span class="bi bi-arrow-up" aria-hidden="true" title="${fn:escapeXml(moveUpLabel)}"></span></button>
                        <button type="button" class="row-action page-order-down"
                                aria-label="${fn:escapeXml(moveDownLabel)}: ${fn:escapeXml(p.title)}"><span class="bi bi-arrow-down" aria-hidden="true" title="${fn:escapeXml(moveDownLabel)}"></span></button>
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
                             is safe with the same escape.

                             A strand with parts adds how many posts leave it
                             (the posts are kept): strandPartCounts holds
                             only strands that have parts. --%>
                        <c:if test="${not empty strandPartCounts[p.id]}">
                            <spring:message code="pages.deleteStrandConfirm" arguments="${strandPartCounts[p.id]}" var="strandConfirm"/>
                        </c:if>
                        <button type="submit" class="row-action row-action-danger page-delete-btn"
                                name="removeId" value="${p.id}"
                                data-confirm="${pageDeleteConfirmBase}: '${fn:escapeXml(p.title)}'?<c:if test="${not empty strandPartCounts[p.id]}"> ${fn:escapeXml(strandConfirm)}</c:if>"
                                aria-label="<spring:message code='generic.delete'/>: ${fn:escapeXml(p.title)}">
                            <span class="bi bi-trash" aria-hidden="true" title="<spring:message code="generic.delete"/>"></span>
                        </button>
                    </td>
                </tr>
            </c:forEach>
            </tbody>

    </table>
    </div>

    <script src="<c:url value='/theme/scripts/pressroom-reorder.js'/>"></script>
    <script>
        (function () {
            var list = document.getElementById("pagesList");
            var save = document.getElementById("pagesOrderSave");
            if (!list || !save || !window.PressroomReorder) {
                return;
            }
            function order() {
                return Array.prototype.map.call(
                    list.querySelectorAll("input[name='pageIds']"),
                    function (input) { return input.value; }).join(",");
            }
            var loaded = order();
            PressroomReorder.install({
                list: list,
                rowSelector: ".page-row",
                upSelector: ".page-order-up",
                downSelector: ".page-order-down",
                onMove: function () {
                    save.hidden = order() === loaded;
                    // The numbers Save order will store: the rows' positions.
                    Array.prototype.forEach.call(list.querySelectorAll(".page-nav-order"),
                        function (cell, index) { cell.textContent = index; });
                }
            });
        }());
    </script>

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
