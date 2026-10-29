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

<p class="subtitle"><spring:message code="stylesheetEdit.subtitle"/></p>


<c:choose>
<c:when test="${template != null}">

    <%-- These messages bring their own <p>s (in every bundle that defines
         them), so the page wraps them in nothing; customThemeNoStylesheet
         below has none and gets one here. --%>
    <spring:message code="stylesheetEdit.youCanCustomize"/>

    <form id="stylesheetEditForm" action="${pageContext.request.contextPath}/pressroom-ui/authoring/stylesheetEdit!save.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>

        <jsp:include page="/WEB-INF/jsps/editor/SessionExpiryBar.jsp"/>

        <%-- Stylesheet content area. Escaped: raw, a "</textarea>" in the
             CSS (a comment is enough) ended the field and the rest of the
             stylesheet rendered as page markup. --%>
        <div class="mb-3">
            <label for="stylesheet_contentsStandard" class="form-label"><spring:message code="stylesheetEdit.source"/></label>
            <textarea id="stylesheet_contentsStandard" name="contentsStandard" rows="30" cols="80"
                      class="form-control" spellcheck="false">${fn:escapeXml(contentsStandard)}</textarea>
        </div>

        <%-- Save, Close and Resize text area buttons--%>
        <button type="submit" class="btn btn-primary"><spring:message code="generic.save"/></button>

        <c:if test="${!customTheme}">
            <button type="button" class="btn btn-secondary" onclick="revertStylesheet();"
                    data-confirm="<spring:message code='stylesheetEdit.confirmRevert'/>"><spring:message code="stylesheetEdit.revert"/></button>
        </c:if>

        <%-- Only delete if we have no custom templates ie website.customStylesheetPath=null --%>
        <c:if test="${sharedThemeStylesheet}">
            <button type="button" class="btn btn-danger" onclick="deleteStylesheet();"
                    data-confirm="<spring:message code='stylesheetEdit.confirmDelete'/>"><spring:message code="stylesheetEdit.delete"/></button>
        </c:if>

    <sec:csrfInput/>
</form>

</c:when>
<c:when test="${sharedTheme}">

    <c:choose>
<c:when test="${sharedThemeStylesheet}">

        <spring:message code="stylesheetEdit.sharedThemeWithStylesheet"/>

        <form action="${pageContext.request.contextPath}/pressroom-ui/authoring/stylesheetEdit!copyStylesheet.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
            <button type="submit" class="btn btn-primary"><spring:message code="stylesheetEdit.copyStylesheet"/></button>
        <sec:csrfInput/>
</form>

    </c:when>
<c:otherwise>
        <spring:message code="stylesheetEdit.sharedThemeNoStylesheetSupport"/>
    </c:otherwise>
</c:choose>
</c:when>
<c:otherwise>
    <p><spring:message code="stylesheetEdit.customThemeNoStylesheet"/></p>
</c:otherwise>
</c:choose>

<script type="text/javascript">

    // Confirmation is handled upstream now, by data-confirm on each button
    // (see pressroom.js): a click that fails to confirm never reaches here.
    function revertStylesheet() {
        var form = document.getElementById('stylesheetEditForm');
        form.action = "<c:url value='/pressroom-ui/authoring/stylesheetEdit!revert.rol'/>";
        form.submit();
    };
    <c:if test="${sharedThemeStylesheet}">
        function deleteStylesheet() {
            var form = document.getElementById('stylesheetEditForm');
            form.action = "<c:url value='/pressroom-ui/authoring/stylesheetEdit!delete.rol'/>";
            form.submit();
        };
    </c:if>

</script>

