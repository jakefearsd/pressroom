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

<p class="subtitle"><spring:message code="createWebsite.prompt"/></p>

<form:form modelAttribute="bean" action="${pageContext.request.contextPath}/roller-ui/createWeblog!save.rol"
           method="post" cssClass="validate-form form-stacked">

    <sec:csrfInput/>

    <spring:message code="CreateWeblog.error.nameNull" var="nameRequired"/>
    <div class="row mb-3">
        <spring:message code="generic.name" var="nameLabel"/>
        <label class="col-sm-3 col-form-label" for="name">${nameLabel}</label>
        <div class="col-sm-9">
            <form:input path="name" id="name" autofocus="autofocus" cssClass="form-control" size="30" maxlength="30"
                        data-msg-required="${nameRequired}" required="required"/>
        </div>
    </div>

    <spring:message code="CreateWeblog.error.handleNull" var="handleRequired"/>
    <div class="row mb-3">
        <spring:message code="createWebsite.handle" var="handleLabel"/>
        <label class="col-sm-3 col-form-label" for="handle">${handleLabel}</label>
        <div class="col-sm-9">
            <form:input path="handle" id="handle" cssClass="form-control" size="30" maxlength="30"
                        onkeyup="handlePreview(this)" data-msg-required="${handleRequired}" required="required"/>
        </div>
    </div>

    <%-- A live preview of the URL the handle will produce, not an error:
         --accent, not the red it used to be hardcoded to. Blank until the
         handle has something in it (it printed the English word "handle"). --%>
    <p class="form-text mb-3">
        <spring:message code="createWebsite.weblogUrl" />:
        ${fn:escapeXml(absoluteSiteURL)}/<span id="handlePreview" class="handle-preview">${fn:escapeXml(bean.handle)}</span>
    </p>

    <spring:message code="CreateWeblog.error.emailAddressNull" var="emailRequired"/>
    <spring:message code="CreateWeblog.error.emailAddressInvalid" var="emailInvalid"/>
    <div class="row mb-3">
        <spring:message code="createWebsite.emailAddress" var="emailLabel"/>
        <label class="col-sm-3 col-form-label" for="emailAddress">${emailLabel}</label>
        <div class="col-sm-9">
            <form:input path="emailAddress" id="emailAddress" cssClass="form-control validate-email" size="40" maxlength="255"
                        data-msg="${emailInvalid}" data-msg-required="${emailRequired}" required="required"/>
        </div>
    </div>

    <div class="row mb-3">
        <spring:message code="createWebsite.locale" var="localeLabel"/>
        <label class="col-sm-3 col-form-label" for="locale">${localeLabel}</label>
        <div class="col-sm-9">
            <form:select path="locale" id="locale" items="${localesList}"  itemLabel="displayName" cssClass="form-select"/>
        </div>
    </div>

    <div class="row mb-3">
        <spring:message code="createWebsite.timezone" var="tzLabel"/>
        <label class="col-sm-3 col-form-label" for="timeZone">${tzLabel}</label>
        <div class="col-sm-9">
            <form:select path="timeZone" id="timeZone" items="${timeZonesList}" cssClass="form-select"/>
        </div>
    </div>

    <%-- ng-app/ng-controller removed: no Angular has ever been loaded on
         this page, so both attributes were inert. --%>
    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="theme">
            <spring:message code="createWebsite.theme" />
        </label>
        <div class="col-sm-9">
            <form:select path="theme" id="theme" items="${themes}" itemValue="id" itemLabel="name" cssClass="form-select"
                         onchange="previewImage(this[selectedIndex].value)"/>
            <p id="themedescription"></p>
            <p><img id="themeThumbnail" src="" alt="" class="img-fluid img-thumbnail theme-thumb"/></p>

        </div>
    </div>

    <button type="submit" class="btn btn-primary"><spring:message code="createWebsite.button.save"/></button>

    <a class="btn btn-secondary" href="<c:url value='/roller-ui/menu.rol'/>"><spring:message code="generic.cancel"/></a>

</form:form>

<%-- ============================================================================== --%>

<script>

    <%-- A script here used to focus the form's first element, which is the
         hidden CSRF input -- not the name field. autofocus on the field. --%>
    var saveButton;

    $( document ).ready(function() {

        saveButton = $("button[type='submit']");

        <c:choose>
        <c:when test="${bean.theme == null}">
        previewImage('${themes[0].id}');
        </c:when>
        <c:otherwise>
        previewImage('${bean.theme}');
        </c:otherwise>
        </c:choose>

    });

    function handlePreview(handle) {
        document.getElementById("handlePreview").textContent = handle.value;
    }

    function previewImage(themeId) {
        $.ajax({ url: "${siteURL}/roller-ui/authoring/themedata",
            data: {theme:themeId}, success: function(data) {
                $('#themedescription').html(data.description);
                $('#themeThumbnail').attr('src','${siteURL}' + data.previewPath);
            }
        });
    }

</script>
