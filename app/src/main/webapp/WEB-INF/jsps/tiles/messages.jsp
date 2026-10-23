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
<%@ page session="false" %>

<script>
    $(document).ready(function () {
        // Auto-dismiss SUCCESS messages only, after 10 seconds. Errors stay:
        // a banner that vanishes while it is being read is worse than none,
        // and GenericError's whole body is this region.
        // The success region IS the .alert element (#messages.alert); the
        // descendant half of the selector keeps working if it ever wraps.
        $("#messages.alert, #messages .alert").delay(10000).slideUp(200, function() {
            $(this).remove();
        });
    });
</script>

<%-- Success Messages --%>
<c:if test="${not empty messages}">
    <div id="messages" class="alert alert-success alert-dismissible fade show"
         role="status" aria-live="polite">
        <button type="button" class="btn-close" data-bs-dismiss="alert"
                aria-label="<spring:message code='generic.close'/>"></button>
        <ul>
            <c:forEach items="${messages}" var="msg">
                <li>${msg}</li>
            </c:forEach>
        </ul>
    </div>
</c:if>

<%-- Error Messages --%>
<c:if test="${not empty errors}">
    <div id="errors" class="alert alert-danger alert-dismissible fade show" role="alert">
        <button type="button" class="btn-close" data-bs-dismiss="alert"
                aria-label="<spring:message code='generic.close'/>"></button>
        <ul>
            <c:forEach items="${errors}" var="error">
                <li><c:out value="${error}" escapeXml="false"/></li>
            </c:forEach>
        </ul>
    </div>
</c:if>
