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

<%-- Body of the reset-password page, invoked from PasswordResetController.
     One view, two states, chosen by ${resetTokenValid}: a valid, unused,
     unexpired token shows the form (with the raw token re-echoed into a
     hidden field); anything else -- garbage, expired, already-used -- shows
     the generic "invalid" message so a probed token cannot be distinguished
     from a used or expired one. --%>
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<c:choose>
    <c:when test="${resetTokenValid}">
        <form method="post" id="resetPasswordForm" class="form-signin"
              action="<c:url value='/pressroom-ui/resetPassword!save.rol'/>">

            <input type="hidden" name="token" value="${fn:escapeXml(resetToken)}"/>

            <div class="mb-3">
                <label for="passwordText" class="form-label"><spring:message code="resetPassword.newPassword"/></label>
                <input type="password" class="form-control" name="passwordText" id="passwordText"
                       autocomplete="new-password" autofocus/>
            </div>

            <div class="mb-3">
                <label for="passwordConfirm" class="form-label"><spring:message code="resetPassword.confirmPassword"/></label>
                <input type="password" class="form-control" name="passwordConfirm" id="passwordConfirm"
                       autocomplete="new-password"/>
            </div>

            <sec:csrfInput/>
            <div class="mb-3">
                <button class="btn btn-primary" type="submit" id="save">
                    <spring:message code="resetPassword.save"/>
                </button>
            </div>

        </form>
    </c:when>
    <c:otherwise>
        <p id="resetPasswordInvalid"><spring:message code="resetPassword.invalid"/></p>
        <p><a href="<c:url value='/pressroom-ui/forgotPassword.rol'/>">
            <spring:message code="forgotPassword.title"/></a></p>
    </c:otherwise>
</c:choose>
