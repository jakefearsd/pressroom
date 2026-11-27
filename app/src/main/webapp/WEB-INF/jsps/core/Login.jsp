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

<%-- Body of the login page, invoked from login.jsp --%>
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>
<jsp:include page="/WEB-INF/jsps/tiles/passwordFields.jsp"/>




<%-- One centred column for the form and the ways around it (SSO, a lost
     password), so the links line up with the fields rather than the page
     edge. --%>
<div class="form-signin">
<%-- Reader sign-in on (0.18.0): the page is the readers' door. The heading
     (the page title, "Sign in to {site}", from LoginController) has one line
     under it, the provider's button comes first, and the password form waits
     in a collapsed "Author sign-in" section that a failed password, ?author,
     or this browser's last password sign-in (the script below) opens. The
     button follows Google's branding (white, the G, "Continue with Google")
     only when the provider is Google: the label and the mark are the
     provider's. With SSO off none of this renders and the page is 0.17.1's
     (LoginPageSsoOffCharacterisationTest). --%>
<c:if test="${ssoEnabled}">
    <p class="login-reader-tagline"><spring:message code="login.reader.tagline"/></p>
    <c:url var="ssoStartUrl" value="/pressroom-ui/sso/start">
        <c:if test="${not empty returnPath}"><c:param name="return" value="${returnPath}"/></c:if>
    </c:url>
    <a class="sso-continue" id="ssoSignIn" href="${fn:escapeXml(ssoStartUrl)}"
       data-umami-event="sign-in" data-umami-event-via="login-page">${ssoMark}<span><spring:message code="login.reader.continueWith" arguments="${ssoLabelArgs}"/></span></a>
    <details id="authorSignIn" class="author-sign-in"<c:if test="${authorSignInOpen}"> open</c:if>>
        <summary><spring:message code="login.reader.authorSignIn"/></summary>
</c:if>
        <form method="post" id="loginForm"
              action="<c:url value='/pressroom_j_security_check'/>">

            <%-- .section-head, not <legend>: a legend outside a fieldset is
                 invalid anyway, and the browser's default sizing for it put a
                 heading well above the 20px page title on the one page a
                 first-time visitor sees. With SSO on the section's summary
                 names the form instead. --%>
            <c:if test="${not ssoEnabled}">
            <h2 class="section-head"><spring:message code="loginPage.prompt"/></h2>
            </c:if>

            <div class="mb-3">
                <label for="j_username" class="form-label"> <spring:message code="loginPage.userName"/> </label>
                <input type="text" class="form-control" name="j_username" id="j_username"
                       autocomplete="username"/>
            </div>

            <div class="mb-3">
                <label for="j_password" class="form-label"> <spring:message code="loginPage.password"/> </label>
                <div class="input-group">
                    <input type="password" class="form-control" name="j_password" id="j_password"
                           autocomplete="current-password" data-reveal/>
                </div>
            </div>

            <c:if test="${rememberMeEnabled}">
                <div class="mb-3 form-check">
                    <input type="checkbox" class="form-check-input" name="_spring_security_remember_me" id="_spring_security_remember_me"/>
                    <label class="form-check-label" for="_spring_security_remember_me" > <spring:message code="loginPage.rememberMe"/> </label>
                </div>
            </c:if>

            <sec:csrfInput/>
            <%-- The page the visitor signed in from (LoginController keeps only a
                 path under the context); ReturnPathLoginSuccessHandler goes back to it. --%>
            <c:if test="${not empty returnPath}">
                <input type="hidden" name="return" value="${fn:escapeXml(returnPath)}"/>
            </c:if>
            <div class="mb-3">
                <button class="btn btn-primary" type="submit" name="login" id="login">
                    <spring:message code='loginPage.login'/>
                </button>

            </div>

        </form>

<p class="mt-3"><a href="<c:url value='/pressroom-ui/forgotPassword.rol'/>">
    <spring:message code="loginPage.forgotPassword"/></a></p>
<c:if test="${ssoEnabled}">
    </details>
</c:if>
</div><%-- .form-signin --%>

<c:if test="${ssoEnabled}">
<script>
    // The third way the Author sign-in section opens: this browser last signed
    // in with a password (the flag is set on that form's submit). Storage that
    // cannot be read or written leaves the section as the server rendered it.
    (function () {
        var author = document.getElementById("authorSignIn");
        try {
            if (!author.open && window.localStorage.getItem("pressroom.authorSignIn") === "1") {
                author.open = true;
            }
        } catch (e) {
            // storage refused
        }
        document.getElementById("loginForm").addEventListener("submit", function () {
            try {
                window.localStorage.setItem("pressroom.authorSignIn", "1");
            } catch (e) {
                // storage refused: the section stays collapsed next time
            }
        });
    })();
</script>
</c:if>
<script>

    // There used to be a second, OpenID login form on this page, and
    // focusToUsernamePasswordForm() decided which of the two deserved focus.
    // With OpenID gone there is only one form, so focus is unconditional.
    //
    // getCookie/setCookie come from theme/scripts/pressroom.js. Remembering the
    // username is a convenience, so it must never be the reason the login page
    // throws: guard on the helpers being there rather than assuming a load
    // order between an external script and this inline one.
<c:if test="${ssoEnabled}"><%-- the fields of a closed Author sign-in section take no focus --%>
    if (document.getElementById("authorSignIn").open) {
</c:if>
    if (typeof getCookie === "function" && getCookie("username") != null) {
        document.getElementById("j_username").value = getCookie("username");
        document.getElementById("j_password").focus();
    } else {
        document.getElementById("j_username").focus();
    }
<c:if test="${ssoEnabled}">
    }
</c:if>

    function saveUsername(theForm) {
        if (typeof setCookie !== "function") {
            // Same reasoning as above, and more pointed: this runs from the
            // form's submit, so throwing here would abort the sign-in.
            return;
        }
        var expires = new Date();
        expires.setTime(expires.getTime() + 24 * 30 * 60 * 60 * 1000); // sets it for approx 30 days.
        setCookie("username", theForm.j_username.value, expires);
        setCookie("favorite_authentication_method", "username");
    }

    <%-- addEventListener, not an inline onsubmit="saveUsername(this)": this
         page carries no confirmation of its own, but every form on this admin
         UI now wires its submit behavior the same way rather than through an
         inline attribute, so there is exactly one idiom to check for. --%>
    document.getElementById("loginForm").addEventListener("submit", function () {
        saveUsername(this);
    });
</script>
