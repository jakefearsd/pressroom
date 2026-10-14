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

<%-- The one-time reveal (spec 2026-10-13, section 3; docs/design/forms/forms-token-reveal.html).
     The secret arrives only as a one-read flash attribute, the response is
     Cache-Control: no-store, and a second visit says "already shown". The
     secret is only ever a form value here, never part of a link.
     RouteSweepIT finds this page by #tokenRevealPage. --%>
<div id="tokenRevealPage">
<c:choose>
    <c:when test="${not empty revealedToken}">
        <p class="subtitle"><spring:message code="apiTokens.reveal.once"/></p>
        <p><spring:message code="apiTokens.label"/>: <strong>${fn:escapeXml(revealedLabel)}</strong></p>

        <div class="mb-3">
            <label for="revealedToken" class="form-label"><spring:message code="apiTokens.reveal.token"/></label>
            <div class="input-group">
                <input type="text" class="form-control data" id="revealedToken" readonly="readonly"
                       autocomplete="off" value="${fn:escapeXml(revealedToken)}"/>
                <button type="button" class="btn btn-secondary clipbutton" data-clipboard-target="#revealedToken">
                    <spring:message code="apiTokens.reveal.copy"/>
                </button>
            </div>
        </div>

        <div class="mb-3">
            <label for="mcpSnippet" class="form-label"><spring:message code="apiTokens.reveal.mcp"/></label>
            <textarea class="form-control data" id="mcpSnippet" rows="3" readonly="readonly">${fn:escapeXml(mcpSnippet)}</textarea>
            <button type="button" class="btn btn-secondary clipbutton mt-2" data-clipboard-target="#mcpSnippet">
                <spring:message code="apiTokens.reveal.copy"/>
            </button>
        </div>
    </c:when>
    <c:otherwise>
        <div class="empty-state" id="tokenAlreadyShown">
            <p class="empty-state-title"><spring:message code="apiTokens.reveal.alreadyShown"/></p>
            <p class="empty-state-body"><spring:message code="apiTokens.reveal.alreadyShown.body"/></p>
        </div>
    </c:otherwise>
</c:choose>

<c:choose>
    <c:when test="${not empty revealedForAgent}">
        <c:url var="revealDoneUrl" value="/roller-ui/admin/agent.rol">
            <c:param name="userName" value="${revealedForAgent}"/>
        </c:url>
    </c:when>
    <c:otherwise>
        <c:url var="revealDoneUrl" value="/roller-ui/profileTokens.rol"/>
    </c:otherwise>
</c:choose>
<p class="mt-3">
    <a href="${revealDoneUrl}" class="btn btn-primary" id="tokenRevealDone"><spring:message code="apiTokens.reveal.done"/></a>
</p>
</div>

<script>
    $(document).ready(function () {
        var clipboard = new ClipboardJS('.clipbutton');
        clipboard.on('success', function (e) {
            e.trigger.classList.add('copied');
            setTimeout(function () { e.trigger.classList.remove('copied'); }, 1500);
            e.clearSelection();
        });
    });
</script>
