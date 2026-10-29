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
<%--
This JSP is intended for cannot proceed error messages, perhaps due to lack of system permissions.
To use: addError() messages and they will appear here.
See CreateWeblogController.java for an example of this JSP.
--%>
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<%-- The flash region above carries the specific reason (see
     CreateWeblogController). This body exists so the page is never blank:
     before it, a "weblog creation is disabled" answer rendered one alert on
     an otherwise empty document -- and the alert auto-dismissed. --%>
<div class="empty-state">
    <p class="empty-state-title"><spring:message code="genericError.title"/></p>
    <p class="empty-state-body"><spring:message code="genericError.body"/></p>
    <a class="btn btn-primary" href="<c:url value='/pressroom-ui/menu.rol'/>">
        <spring:message code="genericError.back"/>
    </a>
</div>
