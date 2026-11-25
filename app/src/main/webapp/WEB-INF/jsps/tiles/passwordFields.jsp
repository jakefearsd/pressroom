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
<%-- The one place the password-field script is loaded, with its four
     labels. Any page with a password field includes this; the fields opt in
     with data-reveal (show/hide button) and, on a confirm field,
     data-match="<id of the new-password field>" (the live match line).
     On a <form:password> write data-reveal="true": Spring's form tags drop
     an empty dynamic attribute. AdminJspHygieneTest pins all of this. --%>
<spring:message code="passwordField.show" var="passwordFieldShow"/>
<spring:message code="passwordField.hide" var="passwordFieldHide"/>
<spring:message code="passwordField.match" var="passwordFieldMatch"/>
<spring:message code="passwordField.mismatch" var="passwordFieldMismatch"/>
<script src="<c:url value='/theme/scripts/pressroom-password.js'/>"
        data-show-label="${fn:escapeXml(passwordFieldShow)}"
        data-hide-label="${fn:escapeXml(passwordFieldHide)}"
        data-match-label="${fn:escapeXml(passwordFieldMatch)}"
        data-mismatch-label="${fn:escapeXml(passwordFieldMismatch)}"></script>
