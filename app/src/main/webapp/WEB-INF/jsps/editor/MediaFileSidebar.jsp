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
<p class="sidebar-label"><spring:message code="mediaFileSidebar.actions"/></p>

<c:url var="mediaFileAddURL" value="/roller-ui/authoring/mediaFileAdd.rol">
    <c:param name="weblog" value="${actionWeblog.handle}"/>
    <c:param name="directoryName" value="${directoryName}"/>
</c:url>
<a href='<c:out value="${mediaFileAddURL}" escapeXml="false"/>'
        class="${actionName.equals('mediaFileAdd') ? 'sidebar-current-action' : ''}">
    <span class="bi bi-image" aria-hidden="true"></span>
    <spring:message code="mediaFileSidebar.add"/>
</a>
</div>

<c:if test="${empty pager}">
    <%-- Only show Create New Directory control when NOT showing search results.
         Its own tiny form -- the sidebar is included into MediaFileView.jsp, but
         that view's own mediaFileViewForm doesn't render at all in an empty
         library (see the childFiles/pager guard in MediaFileView.jsp), so this
         control cannot depend on it. --%>

    <div class="sidebar-group">
    <p class="sidebar-label">
        <span class="bi bi-folder2-open" aria-hidden="true"></span>
        <spring:message code="mediaFileView.addDirectory"/>
    </p>

    <form id="createDirectoryForm" method="post"
          action="<c:url value='/roller-ui/authoring/mediaFileView!createNewDirectory.rol'/>">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>

        <label for="newDirectoryName">
            <spring:message code="mediaFileView.directoryName"/>
        </label>
        <div class="input-group">
            <input type="text" id="newDirectoryName" name="newDirectoryName" maxlength="255" class="form-control"/>
            <button type="submit" id="newDirectoryButton" class="btn btn-secondary">
                <spring:message code="mediaFileView.create"/>
            </button>
        </div>

    <sec:csrfInput/>
    </form>
    </div>
</c:if>

<div class="sidebar-group">
<p class="sidebar-label"><spring:message code="mediaFileView.search"/></p>

<form id="mediaFileSearchForm" name="mediaFileSearchForm" action="${pageContext.request.contextPath}/roller-ui/authoring/mediaFileView!search.rol" method="post" class="form-stacked">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <input type="hidden" name="mediaFileId" value=""/>

    <label for="beanName"><spring:message code="generic.name"/></label>
    <input type="text" name="bean.name" value="${fn:escapeXml(bean.name)}" id="beanName" maxlength="255" class="form-control"/>

    <label for="beanType"><spring:message code="mediaFileView.type"/></label>
    <select name="bean.type" id="beanType" class="form-select">
<c:forEach items="${fileTypes}" var="opt">
<option value="${opt.key}" ${opt.key == bean.type ? 'selected' : ''}>${opt.value}</option>
</c:forEach>
</select>

    <%-- "Larger than" replaces a three-part size-comparison row (equals/at
         least/at most/...) with the one comparison an author actually reaches
         for -- finding the handful of oversized files in a directory. The
         filter type is fixed server-side to "greater than"
         (MediaFileViewController.getSizeFilterTypes()'s "mediaFileView.gt"
         key) rather than offered as a control. --%>
    <label for="beanSize"><spring:message code="mediaFileView.largerThan"/></label>
    <div class="input-group">
        <input type="number" min="0" name="bean.size" value="${bean.size}" id="beanSize" class="form-control"/>
        <select name="bean.sizeUnit" class="form-select">
<c:forEach items="${sizeUnits}" var="opt">
<option value="${opt.key}" ${opt.key == bean.sizeUnit ? 'selected' : ''}>${opt.value}</option>
</c:forEach>
</select>
    </div>
    <input type="hidden" name="bean.sizeFilterType" value="mediaFileView.gt"/>

    <label for="beanTags"><spring:message code="mediaFileView.tags"/></label>
    <input type="text" name="bean.tags" value="${fn:escapeXml(bean.tags)}" id="beanTags" maxlength="50" class="form-control"/>

    <button type="submit" id="searchButton" class="btn btn-primary"><spring:message code="mediaFileView.search"/></button>

    <c:if test="${not empty pager}">
        <button id="resetButton" type="button" class="btn btn-secondary">
            <spring:message code="mediaFileView.reset"/>
        </button>
    </c:if>

<sec:csrfInput/>
</form>
</div>


<script>

    $(document).ready(function () {
        $("#newDirectoryName").on("keyup", maintainDirectoryButtonState);
        $("#newDirectoryButton").prop("disabled", true);
    });

    function maintainDirectoryButtonState(e) {
        if ($("#newDirectoryName").get(0).value.trim().length === 0) {
            $("#newDirectoryButton").prop("disabled", true);
        } else {
            $("#newDirectoryButton").prop("disabled", false);
        }
    }

    $(document).ready(function () {

        maintainSearchButtonState();
        $("input").on("keyup", maintainSearchButtonState);
        $("select").on("change", maintainSearchButtonState);

        $("#resetButton").on("click", function () {
            <c:url var="mediaFileViewURL" value="/roller-ui/authoring/mediaFileView.rol">
            <c:param name="weblog" value="${actionWeblog.handle}"/>
            </c:url>
            window.location = '${mediaFileViewURL}';
        });
    });

    function maintainSearchButtonState(e) {
        var beanSize = $("#beanSize").get(0).value;
        var beanType = $("#beanType").get(0).value;

        if ($("#beanName").get(0).value.trim().length === 0
            && $("#beanTags").get(0).value.trim().length === 0
            && (beanSize.trim().length === 0 || beanSize === 0)
            && (beanType.length === 0 || beanType === "mediaFileView.any")) {
            $("#searchButton").prop("disabled", true);
        } else {
            $("#searchButton").prop("disabled", false);
        }
    }

</script>
