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


<%-- Titling, processing actions different between add and edit --%>
<c:choose>
    <c:when test="${actionName == 'createUser'}">
        <c:set var="subtitleKey" value="userAdmin.subtitle.createNewUser"/>
    </c:when>
    <c:otherwise>
        <c:set var="subtitleKey" value="userAdmin.subtitle.editUser"/>
    </c:otherwise>
</c:choose>

<p class="subtitle">
    <spring:message code="${subtitleKey}" arguments="${fn:escapeXml(bean.userName)}"/>
</p>

<p class="pagetip">
    <c:if test="${actionName == 'createUser'}">
        <spring:message code="userAdmin.addInstructions"/>
    </c:if>
</p>

<c:choose>
    <c:when test="${actionName == 'createUser'}">
        <c:set var="saveAction" value="/roller-ui/admin/createUser!save.rol"/>
        <c:set var="cancelAction" value="/roller-ui/admin/createUser!cancel.rol"/>
    </c:when>
    <c:otherwise>
        <c:set var="saveAction" value="/roller-ui/admin/modifyUser!save.rol"/>
        <c:set var="cancelAction" value="/roller-ui/admin/modifyUser!cancel.rol"/>
    </c:otherwise>
</c:choose>

<form method="post" action="<c:url value='${saveAction}'/>" class="form-stacked">
    <sec:csrfInput/>
    <c:if test="${actionName == 'modifyUser'}">
        <%-- bean for add does not have a bean id yet --%>
        <input type="hidden" name="bean.id" value="${fn:escapeXml(bean.id)}" />
    </c:if>

    <c:choose>
        <c:when test="${actionName == 'modifyUser'}">
            <div class="row mb-3">
                <label class="col-sm-3 col-form-label" for="bean_userName"><spring:message code="userSettings.username"/></label>
                <div class="col-sm-9">
                    <input type="text" id="bean_userName" name="bean.userName" value="${fn:escapeXml(bean.userName)}"
                           size="30" maxlength="30" onkeyup="formChanged()"
                           readonly="readonly" class="form-control"
                           title="<spring:message code='userSettings.tip.username'/>"/>
                </div>
            </div>
        </c:when>
        <c:otherwise>
            <div class="row mb-3">
                <label class="col-sm-3 col-form-label" for="bean_userName"><spring:message code="userSettings.username"/></label>
                <div class="col-sm-9">
                    <input type="text" id="bean_userName" name="bean.userName" value="${fn:escapeXml(bean.userName)}"
                           size="30" maxlength="30" onkeyup="formChanged()" class="form-control" autofocus
                           title="<spring:message code='userAdmin.tip.userName'/>"/>
                </div>
            </div>
        </c:otherwise>
    </c:choose>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_screenName"><spring:message code="userSettings.screenname"/></label>
        <div class="col-sm-9">
            <input type="text" id="bean_screenName" name="bean.screenName"
                   <c:if test="${actionName == 'modifyUser'}">autofocus</c:if> value="${fn:escapeXml(bean.screenName)}"
                   size="30" maxlength="30" onkeyup="formChanged()" class="form-control"
                   title="<spring:message code='userAdmin.tip.screenName'/>"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_fullName"><spring:message code="userSettings.fullname"/></label>
        <div class="col-sm-9">
            <input type="text" id="bean_fullName" name="bean.fullName" value="${fn:escapeXml(bean.fullName)}"
                   size="30" maxlength="30" onkeyup="formChanged()" class="form-control"
                   title="<spring:message code='userAdmin.tip.fullName'/>"/>
        </div>
    </div>

        <c:if test="${not ssoBound}">
        <div class="row mb-3">
            <label class="col-sm-3 col-form-label" for="bean_password"><spring:message code="userSettings.password"/></label>
            <div class="col-sm-9">
                <input type="password" id="bean_password" name="bean.password" size="30"
                       autocomplete="new-password"
                       onkeyup="formChanged()" class="form-control"
                       title="<spring:message code='userAdmin.tip.password'/>"/>
                <c:if test="${actionName == 'createUser' && mailConfigured}">
                    <div class="form-text"><spring:message code="userAdmin.passwordOptionalWithMail"/></div>
                </c:if>
            </div>
        </div>
        </c:if>


    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_email"><spring:message code="userSettings.email"/></label>
        <div class="col-sm-9">
            <input type="text" id="bean_email" name="bean.emailAddress" value="${fn:escapeXml(bean.emailAddress)}"
                   size="30" maxlength="255" onkeyup="formChanged()" class="form-control"
                   title="<spring:message code='userAdmin.tip.email'/>"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_locale"><spring:message code="userSettings.locale"/></label>
        <div class="col-sm-9">
            <select id="bean_locale" name="bean.locale" class="form-select"
                    title="<spring:message code='userAdmin.tip.locale'/>">
                <c:forEach var="loc" items="${localesList}">
                    <option value="${fn:escapeXml(loc)}"
                        <c:if test="${loc == bean.locale}">selected="selected"</c:if>
                    >${fn:escapeXml(loc.displayName)}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_timeZone"><spring:message code="userSettings.timeZone"/></label>
        <div class="col-sm-9">
            <select id="bean_timeZone" name="bean.timeZone" class="form-select"
                    title="<spring:message code='userAdmin.tip.timeZone'/>">
                <c:forEach var="tz" items="${timeZonesList}">
                    <option value="${fn:escapeXml(tz)}"
                        <c:if test="${tz == bean.timeZone}">selected="selected"</c:if>
                    >${fn:escapeXml(tz)}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_enabled"><spring:message code="userAdmin.enabled"/></label>
        <div class="col-sm-9">
            <input type="checkbox" id="bean_enabled" class="form-check-input" name="bean.enabled" value="true"
                <c:if test="${bean.enabled}">checked="checked"</c:if>
                   title="<spring:message code='userAdmin.tip.enabled'/>"/>
            <div class="form-text"><spring:message code="userAdmin.enabled.signsOutNote"/></div>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_administrator"><spring:message code="userAdmin.userAdmin"/></label>
        <div class="col-sm-9">
            <input type="checkbox" id="bean_administrator" class="form-check-input" name="bean.administrator" value="true"
                <c:if test="${bean.administrator}">checked="checked"</c:if>
                   title="<spring:message code='userAdmin.tip.userAdmin'/>"/>
        </div>
    </div>


    <c:if test="${actionName == 'modifyUser'}">
        <h3 class="section-head"><spring:message code="userAdmin.userWeblogs" /></h3>

        <c:if test="${not empty permissions}">
            <p><spring:message code="userAdmin.userMemberOf" />:</p>
            <%-- rollertable for the token palette and the caps-label header
                 row. That rule keys off the first <tr> of the table, so this
                 table -- which had no header row at all -- needs one, or the
                 first weblog's name would be rendered as the label. --%>
            <table class="rollertable table" style="width: 80%">
                <tr>
                    <th scope="col" width="55%"><spring:message code="generic.name"/></th>
                    <th scope="col" width="15%">&nbsp;</th>
                    <th scope="col" width="15%">&nbsp;</th>
                    <th scope="col" width="15%">&nbsp;</th>
                </tr>
                <c:forEach var="perms" items="${permissions}">
                    <tr>
                        <td width="%30">
                            <a href='${urls.weblogAbsolute(perms.weblog)}'>
                                ${fn:escapeXml(perms.weblog.name)} [${fn:escapeXml(perms.weblog.handle)}]
                            </a>
                        </td>
                        <td width="%15">
                            <c:url var="newEntry" value="/roller-ui/authoring/entryAdd.rol">
                                <c:param name="weblog" value="${perms.weblog.handle}" />
                            </c:url>
                            <span class="bi bi-pencil" aria-hidden="true"></span>
                            <a href='${newEntry}'>
                            <spring:message code="userAdmin.newEntry" /></a>
                        </td>
                        <td width="%15">
                            <c:url var="editEntries" value="/roller-ui/authoring/entries.rol">
                                <c:param name="weblog" value="${perms.weblog.handle}" />
                            </c:url>
                            <span class="bi bi-pencil" aria-hidden="true"></span>
                            <a href='${editEntries}'>
                            <spring:message code="userAdmin.editEntries" /></a>
                        </td>
                        <td width="%15">
                            <c:url var="manageWeblog" value="/roller-ui/authoring/weblogConfig.rol">
                                <c:param name="weblog" value="${perms.weblog.handle}" />
                            </c:url>
                            <span class="bi bi-pencil" aria-hidden="true"></span>
                            <a href='${manageWeblog}'>
                            <spring:message code="userAdmin.manage" /></a>
                        </td>
                    </tr>
                </c:forEach>
            </table>
        </c:if>
        <%-- No action button: an administrator cannot grant weblog membership
             from this form -- that happens on the weblog's own Members page,
             which is per-weblog and needs a weblog picked first. --%>
        <c:if test="${empty permissions}">
            <div class="empty-state">
                <p class="empty-state-title"><spring:message code="empty.userWeblogs.title" /></p>
                <p class="empty-state-body"><spring:message code="userAdmin.userHasNoWeblogs" /></p>
            </div>
        </c:if>
    </c:if>

    <div class="control mt-4">
        <button type="submit" class="btn btn-primary" id="save_button">
            <spring:message code="generic.save"/>
        </button>
        <a href="<c:url value='${cancelAction}'/>" class="btn btn-secondary">
            <spring:message code="generic.cancel"/>
        </a>
    </div>

</form>

<c:if test="${actionName == 'modifyUser' && not empty bean.id && not ssoBound}">
    <%-- A second, sibling form -- HTML does not allow nesting one inside the
         save form above, and this button posts to a different action. --%>
    <spring:message code="userAdmin.sendPasswordLink.confirm"
                    arguments="${fn:escapeXml(bean.userName)},${fn:escapeXml(bean.emailAddress)}"
                    var="sendLinkConfirm"/>
    <form method="post" action="<c:url value='/roller-ui/admin/userEdit!sendPasswordLink.rol'/>"
          class="mt-3">
        <sec:csrfInput/>
        <input type="hidden" name="bean.userName" value="${fn:escapeXml(bean.userName)}"/>
        <%-- Quiet, not a second save: this mails a real person. .delete-link is
             deliberately NOT used -- its red hover means destructive.

             data-confirm on the BUTTON, not on the <form> and not an inline
             onsubmit="return confirm('...')".
             * Not inline, because fn:escapeXml is an HTML escape in a
               JS-string position: an address like o'brien@example.com
               terminated the string, the handler failed to compile, and the
               link was mailed with NO confirmation at all.
             * Not on the form, because roller.js answers a click by walking
               UP from the clicked element -- so a form-level attribute
               prompts once for the click and again for the submit that click
               produces. Two dialogs for one action. The button is the shape
               Maintenance.jsp already uses; keep new prompts on the control.
             See Maintenance.jsp for why this is single-escaped. --%>
        <button type="submit" class="btn btn-link p-0" data-confirm="${sendLinkConfirm}">
            <spring:message code="userAdmin.sendPasswordLink"/>
        </button>
    </form>
</c:if>


<script>

    <%-- A script here used to focus the form's first element on load. The
         first element of this form is the hidden CSRF input, so it focused
         the hidden input -- i.e. nothing. The autofocus attribute on the
         first real field does what it was trying to do. --%>
    let saveButton;

    $( document ).ready(function() {
        saveButton = $("#save_button");
        formChanged()
    });

    function formChanged() {
        // Screen name, full name and email -- the three fields the save button
        // waits on. The username is deliberately NOT among them; it is checked
        // server-side. (An earlier version read the first of these through
        // id="bean_userName", which at the time sat on the SCREEN NAME input.
        // That id belongs to the username field now, so the old note about it
        // would read as a live warning about code that no longer exists.)
        let screenName = $("#bean_screenName:first").val();
        let fullName = $("#bean_fullName:first").val();
        let email = $("#bean_email:first").val();

        let valid = (screenName && screenName.trim().length > 0
            && fullName && fullName.trim().length > 0
            && email && email.trim().length > 0
            && validateEmail(email));

        if (valid) {
            saveButton.prop("disabled", false);
        } else {
            saveButton.prop("disabled", true);
        }
    }

</script>

