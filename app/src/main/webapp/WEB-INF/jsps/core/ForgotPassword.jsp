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

<%-- Body of the forgot-password page, invoked from PasswordResetController.
     The confirmation message (rendered by the shared "messages" tile) is
     deliberately identical whether the identifier names an account or not --
     see PasswordResetController for why. --%>
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<p><spring:message code="forgotPassword.prompt"/></p>

<form method="post" id="forgotPasswordForm" class="form-signin"
      action="<c:url value='/pressroom-ui/forgotPassword!send.rol'/>">

    <div class="mb-3">
        <label for="identifier" class="form-label"><spring:message code="forgotPassword.identifier"/></label>
        <input type="text" class="form-control" name="identifier" id="identifier" autofocus/>
    </div>

    <sec:csrfInput/>
    <div class="mb-3">
        <button class="btn btn-primary" type="submit" id="send">
            <spring:message code="forgotPassword.send"/>
        </button>
    </div>

</form>
