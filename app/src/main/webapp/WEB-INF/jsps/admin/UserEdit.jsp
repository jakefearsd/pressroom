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

<c:if test="${actionName == 'createUser'}">
    <p class="pagetip"><spring:message code="userAdmin.addInstructions"/></p>
</c:if>

<c:if test="${ssoBound}">
    <p class="form-text" id="ssoBoundMarker"><spring:message code="userAdmin.ssoBound"/></p>
</c:if>

<c:choose>
    <c:when test="${actionName == 'createUser'}">
        <c:set var="saveAction" value="/pressroom-ui/admin/createUser!save.rol"/>
        <c:set var="cancelAction" value="/pressroom-ui/admin/createUser!cancel.rol"/>
    </c:when>
    <c:otherwise>
        <c:set var="saveAction" value="/pressroom-ui/admin/modifyUser!save.rol"/>
        <c:set var="cancelAction" value="/pressroom-ui/admin/modifyUser!cancel.rol"/>
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
                           size="30" maxlength="30"
                           readonly="readonly" class="form-control"
                           aria-describedby="bean_userName_help"/>
                    <div class="form-text" id="bean_userName_help"><spring:message code="userSettings.tip.username"/></div>
                </div>
            </div>
        </c:when>
        <c:otherwise>
            <div class="row mb-3">
                <label class="col-sm-3 col-form-label" for="bean_userName"><spring:message code="userSettings.username"/></label>
                <div class="col-sm-9">
                    <input type="text" id="bean_userName" name="bean.userName" value="${fn:escapeXml(bean.userName)}"
                           size="30" maxlength="30" class="form-control" autofocus
                           aria-describedby="bean_userName_help"/>
                    <div class="form-text" id="bean_userName_help"><spring:message code="userAdmin.tip.userName"/></div>
                </div>
            </div>
        </c:otherwise>
    </c:choose>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_screenName"><spring:message code="userSettings.screenname"/></label>
        <div class="col-sm-9">
            <input type="text" id="bean_screenName" name="bean.screenName"
                   <c:if test="${actionName == 'modifyUser'}">autofocus</c:if> value="${fn:escapeXml(bean.screenName)}"
                   size="30" maxlength="30" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_fullName"><spring:message code="userSettings.fullname"/></label>
        <div class="col-sm-9">
            <input type="text" id="bean_fullName" name="bean.fullName" value="${fn:escapeXml(bean.fullName)}"
                   size="30" maxlength="30" class="form-control"/>
        </div>
    </div>

    <c:if test="${not ssoBound}">
        <%-- Spec 2026-10-13, section 4: a new person either gets an emailed
             set-password link -- offered, and the default, only while mail can
             deliver one -- or a password set now. --%>
        <c:if test="${actionName == 'createUser'}">
            <c:choose>
                <c:when test="${mailConfigured}">
                    <fieldset class="row mb-3">
                        <legend class="col-sm-3 col-form-label"><spring:message code="personForm.password"/></legend>
                        <div class="col-sm-9">
                            <div class="form-check">
                                <input type="radio" class="form-check-input" id="bean_passwordModeLink"
                                       name="bean.passwordMode" value="link"
                                       <c:if test="${bean.passwordMode != 'now'}">checked="checked"</c:if>/>
                                <label class="form-check-label" for="bean_passwordModeLink"><spring:message code="personForm.password.link"/></label>
                            </div>
                            <div class="form-check">
                                <input type="radio" class="form-check-input" id="bean_passwordModeNow"
                                       name="bean.passwordMode" value="now"
                                       <c:if test="${bean.passwordMode == 'now'}">checked="checked"</c:if>/>
                                <label class="form-check-label" for="bean_passwordModeNow"><spring:message code="personForm.password.now"/></label>
                            </div>
                        </div>
                    </fieldset>
                </c:when>
                <c:otherwise>
                    <input type="hidden" name="bean.passwordMode" value="now"/>
                </c:otherwise>
            </c:choose>
        </c:if>
        <div class="row mb-3">
            <label class="col-sm-3 col-form-label" for="bean_password"><spring:message code="userSettings.password"/></label>
            <div class="col-sm-9">
                <input type="password" id="bean_password" name="bean.password" size="30"
                       autocomplete="new-password" class="form-control"/>
                <c:choose>
                    <c:when test="${actionName == 'createUser'}">
                        <div class="form-text"><spring:message code="personForm.password.tip"/></div>
                    </c:when>
                    <c:otherwise>
                        <div class="form-text"><spring:message code="userAdmin.tip.password"/></div>
                    </c:otherwise>
                </c:choose>
            </div>
        </div>
    </c:if>


    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_email"><spring:message code="userSettings.email"/></label>
        <div class="col-sm-9">
            <input type="text" id="bean_email" name="bean.emailAddress" value="${fn:escapeXml(bean.emailAddress)}"
                   size="30" maxlength="255" class="form-control"/>
            <div class="form-text"><spring:message code="userAdmin.tip.email"/></div>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_locale"><spring:message code="userSettings.locale"/></label>
        <div class="col-sm-9">
            <select id="bean_locale" name="bean.locale" class="form-select">
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
            <select id="bean_timeZone" name="bean.timeZone" class="form-select">
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
                <c:if test="${bean.enabled}">checked="checked"</c:if>/>
            <div class="form-text"><spring:message code="userAdmin.enabled.signsOutNote"/></div>
        </div>
    </div>

    <c:if test="${not ssoBound}">
    <c:choose>
    <c:when test="${actionName == 'createUser'}">
    <%-- Spec 2026-10-13, section 4: Author or Site admin, Author by default.
         Both are the editor role; Site admin adds admin. --%>
    <fieldset class="row mb-3">
        <legend class="col-sm-3 col-form-label"><spring:message code="personForm.role"/></legend>
        <div class="col-sm-9">
            <div class="form-check">
                <input type="radio" class="form-check-input" id="bean_roleAuthor" name="bean.administrator" value="false"
                    <c:if test="${not bean.administrator}">checked="checked"</c:if>/>
                <label class="form-check-label" for="bean_roleAuthor"><spring:message code="personForm.role.author"/></label>
            </div>
            <div class="form-check">
                <input type="radio" class="form-check-input" id="bean_roleAdmin" name="bean.administrator" value="true"
                    <c:if test="${bean.administrator}">checked="checked"</c:if>/>
                <label class="form-check-label" for="bean_roleAdmin"><spring:message code="personForm.role.siteAdmin"/></label>
            </div>
        </div>
    </fieldset>
    </c:when>
    <c:otherwise>
    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_administrator"><spring:message code="userAdmin.userAdmin"/></label>
        <div class="col-sm-9">
            <input type="checkbox" id="bean_administrator" class="form-check-input" name="bean.administrator" value="true"
                <c:if test="${bean.administrator}">checked="checked"</c:if>/>
            <div class="form-text"><spring:message code="userAdmin.tip.userAdmin"/></div>
        </div>
    </div>
    </c:otherwise>
    </c:choose>
    </c:if>
    <c:if test="${ssoBound}">
    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_writer"><spring:message code="userAdmin.canWrite"/></label>
        <div class="col-sm-9">
            <input type="checkbox" id="bean_writer" class="form-check-input" name="bean.writer" value="true"
                <c:if test="${bean.writer}">checked="checked"</c:if>/>
        </div>
    </div>
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
    <form method="post" action="<c:url value='/pressroom-ui/admin/userEdit!sendPasswordLink.rol'/>"
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
             * Not on the form, because pressroom.js answers a click by walking
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


<c:if test="${actionName == 'modifyUser' && not empty bean.id}">
    <%-- Outside the save form: the section posts forms of its own. --%>
    <jsp:include page="/WEB-INF/jsps/admin/UserWeblogs.jsp"/>
</c:if>

<%-- No client-side gate on Save: it used to stay disabled until an onkeyup
     check passed, so autofill or a mouse paste left it disabled with no reason
     given. UserEditController answers each required field with addFieldError,
     which marks and focuses the field (data-invalid-fields). The autofocus
     attribute on the first real field replaces the old focus script. --%>
