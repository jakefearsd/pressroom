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

<p class="subtitle"><spring:message code="yourProfile.subtitle"/></p>



<form:form modelAttribute="bean" action="${pageContext.request.contextPath}/roller-ui/profile!save.rol" method="post" cssClass="form-stacked">
    <sec:csrfInput/>

    <div class="row mb-3">
        <spring:message code="userSettings.username" var="usernameLabel"/>
        <label class="col-sm-3 col-form-label" for="userName">${usernameLabel}</label>
        <div class="col-sm-9">
            <form:input path="userName" id="userName" cssClass="form-control" size="30" maxlength="30" readonly="true"
                        onchange="formChanged()" onkeyup="formChanged()"/>
        </div>
    </div>

    <div class="row mb-3">
        <spring:message code="userSettings.screenname" var="screennameLabel"/>
        <label class="col-sm-3 col-form-label" for="screenName">${screennameLabel}</label>
        <div class="col-sm-9">
            <form:input path="screenName" id="screenName" autofocus="autofocus"
                        cssClass="form-control" size="30" maxlength="30"
                        onchange="formChanged()" onkeyup="formChanged()"/>
        </div>
    </div>

    <div class="row mb-3">
        <spring:message code="userSettings.fullname" var="fullnameLabel"/>
        <label class="col-sm-3 col-form-label" for="fullName">${fullnameLabel}</label>
        <div class="col-sm-9">
            <form:input path="fullName" id="fullName" cssClass="form-control" size="30" maxlength="30"
                        onchange="formChanged()" onkeyup="formChanged()"/>
        </div>
    </div>

    <div class="row mb-3">
        <spring:message code="userSettings.email" var="emailLabel"/>
        <label class="col-sm-3 col-form-label" for="emailAddress">${emailLabel}</label>
        <div class="col-sm-9">
            <form:input path="emailAddress" id="emailAddress" cssClass="form-control" size="40" maxlength="255"
                        onchange="formChanged()" onkeyup="formChanged()"/>
        </div>
    </div>

        <c:if test="${not ssoBound}">
        <div class="row mb-3">
            <spring:message code="userSettings.password" var="passwordLabel"/>
            <label class="col-sm-3 col-form-label" for="passwordText">${passwordLabel}</label>
            <div class="col-sm-9">
                <form:password path="passwordText" id="passwordText" autocomplete="new-password"
                               cssClass="form-control" size="20"
                               onchange="formChanged()" onkeyup="formChanged()"/>
            </div>
        </div>

        <div class="row mb-3">
            <spring:message code="userSettings.passwordConfirm" var="passwordConfirmLabel"/>
            <label class="col-sm-3 col-form-label" for="passwordConfirm">${passwordConfirmLabel}</label>
            <div class="col-sm-9">
                <form:password path="passwordConfirm" id="passwordConfirm" autocomplete="new-password"
                               cssClass="form-control" size="20"
                               onchange="formChanged()" onkeyup="formChanged()"/>
            </div>
        </div>
        </c:if>


    <div class="row mb-3">
        <spring:message code="userSettings.locale" var="localeLabel"/>
        <label class="col-sm-3 col-form-label" for="locale">${localeLabel}</label>
        <div class="col-sm-9">
            <form:select path="locale" id="locale" items="${localesList}"  itemLabel="displayName" cssClass="form-select"/>
        </div>
    </div>

    <div class="row mb-3">
        <spring:message code="userSettings.timeZone" var="tzLabel"/>
        <label class="col-sm-3 col-form-label" for="timeZone">${tzLabel}</label>
        <div class="col-sm-9">
            <form:select path="timeZone" id="timeZone" items="${timeZonesList}" cssClass="form-select"/>
        </div>
    </div>

    <button type="submit" id="saveButton" class="btn btn-primary"><spring:message code="generic.save"/></button>

    <a class="btn btn-secondary" href="<c:url value='/roller-ui/menu.rol'/>"><spring:message code="generic.cancel"/></a>

</form:form>

<p class="mt-4">
    <a href="<c:url value='/roller-ui/profileTokens.rol'/>" id="profileTokensLink"><spring:message code="apiTokens.profile.link"/></a>
</p>

<%-- -------------------------------------------------------- --%>

<script type="text/javascript">

    var saveButton;

    $(document).ready(function () {
        saveButton = $("#saveButton");
        formChanged();
    });

    function formChanged() {
        var valid = false;

        var screenName = $("[name='screenName']").val();
        var fullName = $("[name='fullName']").val();
        var email = $("[name='emailAddress']").val();
        var password = $("[name='passwordText']").val();
        var passwordConfirm = $("[name='passwordConfirm']").val();

        if (screenName && screenName.trim().length > 0
            && fullName && fullName.trim().length > 0
            && email && email.trim().length > 0 && validateEmail(email)) {
            valid = true;

        } else {
            valid = false;
        }

        if ((password && password.trim().length) || (passwordConfirm && passwordConfirm.trim().length > 0)) {
            if (password !== passwordConfirm) {
                valid = false;
            }
        }

        if (valid) {
            saveButton.prop("disabled", false);
        } else {
            saveButton.prop("disabled", true);
        }

    }

</script>
