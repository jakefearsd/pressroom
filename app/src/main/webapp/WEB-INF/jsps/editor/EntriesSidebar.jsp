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

<div class="sidebar-group">
<p class="sidebar-label"><spring:message code="weblogEntryQuery.sidebarTitle"/></p>

<p><spring:message code="weblogEntryQuery.sidebarDescription"/></p>

<form action="${pageContext.request.contextPath}/pressroom-ui/authoring/entries.rol" method="get" class="form-stacked">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>

    <%-- ========================================================= --%>
    <%-- filter by category --%>

    <%-- Every field is a div.mb-3 with a .form-label, the one label role:
         only the two dates used to carry it, so they alone rendered bold. --%>
    <div class="mb-3">
    <label for="entries_bean_categoryName" class="form-label"><spring:message code="weblogEntryQuery.label.category"/></label>
    <select id="entries_bean_categoryName" name="bean.categoryName" class="form-select" size="1">
<option value="" ${empty bean.categoryName ? 'selected' : ''}><spring:message code="weblogEntryQuery.label.anyCategory"/></option>
<%-- Category names are author input: escaped in the value and the text
     (CategoryEditController refusing HTML characters today is a rule in
     another file, not this page's defence). --%>
<c:forEach items="${categories}" var="opt">
<option value="${fn:escapeXml(opt.name)}" ${opt.name == bean.categoryName ? 'selected' : ''}>${fn:escapeXml(opt.name)}</option>
</c:forEach>
</select>
    </div>

    <%-- ========================================================= --%>
    <%-- filter by tag --%>

    <div class="mb-3">
        <label for="entries_bean_tagsAsString" class="form-label"><spring:message code="weblogEntryQuery.label.tags"/></label>
        <input type="text" id="entries_bean_tagsAsString" name="bean.tagsAsString" value="${fn:escapeXml(bean.tagsAsString)}" size="14" class="form-control"/>
    </div>

    <%-- ========================================================= --%>
    <%-- filter by text --%>

    <div class="mb-3">
        <label for="entries_bean_text" class="form-label"><spring:message code="weblogEntryQuery.label.text"/></label>
        <input type="text" id="entries_bean_text" name="bean.text" value="${fn:escapeXml(bean.text)}" size="14" class="form-control"/>
    </div>

    <%-- ========================================================= --%>
    <%-- filter by date --%>

    <div class="mb-3">
        <label for="entries_bean_startDateString" class="form-label">
            <spring:message code="weblogEntryQuery.label.startDate"/>
        </label>
        <input type="date" id="entries_bean_startDateString" name="bean.startDateString" value="${fn:escapeXml(bean.startDateString)}" class="form-control"/>
    </div>

    <div class="mb-3">
        <label for="entries_bean_endDateString" class="form-label">
            <spring:message code="weblogEntryQuery.label.endDate"/>
        </label>
        <input type="date" id="entries_bean_endDateString" name="bean.endDateString" value="${fn:escapeXml(bean.endDateString)}" class="form-control"/>
    </div>

    <%-- ========================================================= --%>
    <%-- the status the chips chose --%>

    <%-- Not a control -- the status filter's one control is the chip row on
         the list itself (task B5), and a second control for the same filter
         is how a page ends up showing DRAFT while the sidebar claims ALL.
         This hidden field exists only so that submitting the sidebar (to
         filter, or to change the sort) does not silently discard whichever
         chip the author had chosen. Guarded exactly like Entries.jsp's own
         bulk form: EntriesBean.status defaults to "ALL" and
         EntriesController.execute treats only that literal as "no filter"
         (PubStatus.valueOf(status) otherwise) -- an unconditional empty
         bean.status= would override the "ALL" default and reach
         PubStatus.valueOf("") -> uncaught IllegalArgumentException -> 500. --%>
    <c:if test="${not empty bean.status}">
        <input type="hidden" name="bean.status" value="${fn:escapeXml(bean.status)}"/>
    </c:if>

    <%-- ========================================================= --%>
    <%-- sort by --%>

    <%-- One select rather than a radio per option: sort is a single choice
         among a closed set, and it applies the moment it is made. The submit
         is wired by a delegated listener in pressroom.js keyed off
         data-submit-on-change, never an inline onchange -- behaviour lives
         in the script, the same rule data-confirm follows. --%>
    <div class="mb-3">
    <label for="entries_bean_sortBy" class="form-label"><spring:message code="weblogEntryQuery.label.sortBy"/></label>
    <select id="entries_bean_sortBy" name="bean.sortBy" class="form-select" size="1" data-submit-on-change>
<c:forEach items="${sortByOptions}" var="opt">
<option value="${opt.key}" ${opt.key == bean.sortBy ? 'selected' : ''}>${opt.value}</option>
</c:forEach>
</select>
    </div>

    <%-- ========================================================= --%>
    <%-- filter button --%>

    <button type="submit" class="btn btn-secondary"><spring:message code="weblogEntryQuery.button.query"/></button>

</form>
</div>

