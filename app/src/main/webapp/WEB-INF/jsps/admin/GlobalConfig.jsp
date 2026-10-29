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

<p class="subtitle"><spring:message code="configForm.subtitle"/></p>
<%-- configForm.prompt's value is itself a <p>...</p>; wrapping it in a
     second one nested a block element inside a block element. --%>
<spring:message code="configForm.prompt"/>


<%-- Site settings is the other long form with a rail, built to the same card
     as WeblogConfig.jsp (docs/design/forms/settings-with-rail.html): the
     display groups on the left, a 252px rail on the right holding a section
     index and a Save that is reachable without scrolling past nine groups of
     fields to find it.

     The <form> IS the grid container, so the rail sits in its second column
     without a display:contents indirection and Save stays a plain descendant
     of the form it submits. The index is generated from the SAME
     globalConfigDef.displayGroups the fields are -- a hand-written list would
     stop matching the moment runtimeConfigDefs.xml grew a group, silently. --%>
<form method="post" action="<c:url value='/roller-ui/admin/globalConfig!save.rol'/>"
      class="settings-grid form-stacked">
    <sec:csrfInput/>

<div>

    <c:forEach var="dg" items="${globalConfigDef.displayGroups}">

        <h2 class="section-head" id="cfg-${fn:escapeXml(dg.key)}"><spring:message code="${dg.key}"/></h2>

        <c:forEach var="pd" items="${dg.propertyDefs}">

            <%-- special case for front page blog --%>
            <c:if test="${pd.name == 'site.frontpage.weblog.handle'}">
                <div class="row mb-3">
                    <label class="col-sm-3 col-form-label"
                           for='globalConfig_${pd.nameWithUnderbars}'><spring:message code="${pd.key}"/></label>
                    <div class="col-sm-9">
                        <select name="${fn:escapeXml(pd.name)}"
                                id="globalConfig_${pd.nameWithUnderbars}" class="form-select">
                            <%-- None is a real choice: / then shows the site
                                 directory. It comes first, so when no weblog
                                 option is marked selected the browser picks it
                                 rather than the first weblog, and the field is
                                 still submitted when no weblog exists yet. --%>
                            <option value=""><spring:message code="configForm.frontpageWeblogHandle.none"/></option>
                            <%-- Published site pages: the value is page:<slug>, which
                                 the controller turns into site.frontpage.page. A
                                 weblog option is only selected while no site page is. --%>
                            <c:if test="${not empty staleFrontDoorPage}">
                                <%-- The stored page is no longer published (unpublished or
                                     renamed). Offered as the selected option so an unrelated
                                     save keeps it instead of clearing the front door; / shows
                                     the directory meanwhile. --%>
                                <option value="page:${fn:escapeXml(staleFrontDoorPage)}" selected="selected"><spring:message
                                    code="configForm.frontpageStalePage" arguments="${fn:escapeXml(staleFrontDoorPage)}"/></option>
                            </c:if>
                            <c:if test="${not empty sitePages}">
                                <spring:message code="configForm.frontpageSitePages" var="sitePagesLabel"/>
                                <optgroup label="${fn:escapeXml(sitePagesLabel)}">
                                    <c:forEach var="sitePage" items="${sitePages}">
                                        <option value="page:${fn:escapeXml(sitePage.slug)}"
                                            <c:if test="${properties['site.frontpage.page'].value == sitePage.slug}">selected="selected"</c:if>
                                        >${fn:escapeXml(sitePage.title)} (/${fn:escapeXml(sitePage.slug)})</option>
                                    </c:forEach>
                                </optgroup>
                            </c:if>
                            <c:forEach var="weblog" items="${weblogs}">
                                <option value="${fn:escapeXml(weblog.handle)}"
                                    <c:if test="${empty properties['site.frontpage.page'].value && properties[pd.name].value == weblog.handle}">selected="selected"</c:if>
                                >${fn:escapeXml(weblog.handle)}</option>
                            </c:forEach>
                        </select>
                        <c:if test="${empty sitePages}">
                            <c:url value="/roller-ui/authoring/pages.rol" var="sitePagesUrl">
                                <c:param name="weblog" value="site-space"/>
                            </c:url>
                            <div class="form-text"><spring:message code="configForm.frontpageNoSitePages"/>
                                <a href="${sitePagesUrl}"><spring:message code="configForm.frontpageSitePagesLink"/></a></div>
                        </c:if>
                    </div>
                </div>
            </c:if>

            <%-- "string" type means use a simple textbox --%>
            <c:if test="${pd.name != 'site.frontpage.weblog.handle' && pd.name != 'site.frontpage.page' && pd.type == 'string'}">
                <div class="row mb-3">
                    <label class="col-sm-3 col-form-label"
                           for='globalConfig_${pd.nameWithUnderbars}'><spring:message code="${pd.key}"/></label>
                    <div class="col-sm-9">
                        <input type="text" name="${fn:escapeXml(pd.name)}" size="35"
                               value="${fn:escapeXml(properties[pd.name].value)}"
                               id="globalConfig_${pd.nameWithUnderbars}"
                               class="form-control"/>
                    </div>
                </div>
            </c:if>

            <%-- "text" type means use a full textarea --%>
            <c:if test="${pd.name != 'site.frontpage.weblog.handle' && pd.type == 'text'}">
                <div class="row mb-3">
                    <label class="col-sm-3 col-form-label"
                           for='globalConfig_${pd.nameWithUnderbars}'><spring:message code="${pd.key}"/></label>
                    <div class="col-sm-9">
                        <textarea name="${fn:escapeXml(pd.name)}" rows="${pd.rows}" cols="${pd.cols}"
                                  id="globalConfig_${pd.nameWithUnderbars}"
                                  class="form-control">${fn:escapeXml(properties[pd.name].value)}</textarea>
                    </div>
                </div>
            </c:if>

            <%-- "boolean" type means use a checkbox. A switch reads as a
                 sentence -- box, then what it does -- so the label sits BESIDE
                 the control in a .form-check rather than above it in the
                 stacked-label column every other field uses. Bootstrap's
                 .form-check is the shape that puts it there. --%>
            <c:if test="${pd.name != 'site.frontpage.weblog.handle' && pd.type == 'boolean'}">
                <div class="row mb-3">
                    <div class="form-check">
                        <input type="checkbox" name="${fn:escapeXml(pd.name)}" value="true"
                            <c:if test="${properties[pd.name].value == 'true'}">checked="checked"</c:if>
                               id="globalConfig_${pd.nameWithUnderbars}"
                               class="form-check-input boolean"/>
                        <label class="form-check-label"
                               for='globalConfig_${pd.nameWithUnderbars}'><spring:message code="${pd.key}"/></label>
                    </div>
                </div>
            </c:if>

            <%-- "integer" use input type number --%>
            <c:if test="${pd.name != 'site.frontpage.weblog.handle' && pd.type == 'integer'}">
                <div class="row mb-3">
                    <label class="col-sm-3 col-form-label"
                           for='globalConfig_${pd.nameWithUnderbars}'>
                        <spring:message code="${pd.key}"/>
                    </label>
                    <div class="col-sm-9">
                        <input type="number" name='${fn:escapeXml(pd.name)}' size="35"
                               value='${fn:escapeXml(properties[pd.name].value)}'
                               id='globalConfig_${pd.nameWithUnderbars}'
                               class="form-control integer"/>
                    </div>
                </div>
            </c:if>

            <%-- "float" use input type number --%>
            <c:if test="${pd.name != 'site.frontpage.weblog.handle' && pd.type == 'float'}">
                <div class="row mb-3">
                    <label class="col-sm-3 col-form-label"
                           for='globalConfig_${pd.nameWithUnderbars}'>
                        <spring:message code="${pd.key}"/>
                    </label>
                    <div class="col-sm-9">
                        <input type="number" name='${fn:escapeXml(pd.name)}' size="5"
                               value='${fn:escapeXml(properties[pd.name].value)}'
                               id='globalConfig_${pd.nameWithUnderbars}'
                               class="form-control float"/>
                    </div>
                </div>
            </c:if>

            <%-- if it's something we don't understand then use textbox --%>
            <c:if test="${pd.name != 'site.frontpage.weblog.handle' && pd.type != 'string' && pd.type != 'text' && pd.type != 'boolean' && pd.type != 'integer' && pd.type != 'float'}">
                <div class="row mb-3">
                    <label class="col-sm-3 col-form-label"
                           for='globalConfig_${pd.nameWithUnderbars}'><spring:message code="${pd.key}"/></label>
                    <div class="col-sm-9">
                        <input type="text" name="${fn:escapeXml(pd.name)}" size="35"
                               value="${fn:escapeXml(properties[pd.name].value)}"
                               id="globalConfig_${pd.nameWithUnderbars}"
                               class="form-control"/>
                    </div>
                </div>
            </c:if>

        </c:forEach>

        <%-- The gap between one display group and the next used to be a
             spacer.png with an inline min-height. It is margin, and
             .section-head already carries 32px of top margin, so the group
             heading below spaces the groups on its own -- nothing goes here. --%>

    </c:forEach>

</div>

<%-- ====================================================================== --%>
<%-- The rail: where you are, and Save. --%>

<aside class="settings-rail">

    <div class="rail-box settings-rail-index">
        <%-- websiteSettings.sections is the bare word "Sections", already
             translated in every locale bundle for the weblog-settings rail.
             Reused rather than adding a second key for the same word, the way
             MainMenu.jsp reuses the inviteMember.* role names. --%>
        <h2 class="section-head"><spring:message code="websiteSettings.sections"/></h2>
        <nav class="section-index" id="globalConfigSectionIndex"
             aria-label="<spring:message code="websiteSettings.sections"/>">
            <c:forEach var="dg" items="${globalConfigDef.displayGroups}" varStatus="dgStatus">
                <a href="#cfg-${fn:escapeXml(dg.key)}"<c:if test="${dgStatus.first}"> class="is-current"</c:if>><spring:message code="${dg.key}"/></a>
            </c:forEach>
        </nav>
    </div>

    <div class="rail-box">
        <%-- id="saveButton" is load-bearing beyond this page:
             PressroomIT.setGlobalFlags -- which every browser test that permutes a
             runtime property goes through -- clicks it by that id. --%>
        <button id="saveButton" class="btn btn-primary w-100" type="submit"><spring:message code="generic.save"/></button>
    </div>

</aside>

</form>


<script type="text/javascript">

    <%-- The numeric fields used to carry onkeyup="formChanged()" and the
         checkboxes onchange="formChanged()" -- an inline handler per control,
         which is a second place translated or generated text can reach raw
         JavaScript (see roller.js's data-confirm note for what that costs).
         One delegated listener on the form covers every control it will ever
         have, including ones runtimeConfigDefs.xml has not grown yet. --%>
    (function () {
        var save = document.getElementById("saveButton");
        if (!save || !save.form) {
            return;
        }
        save.form.addEventListener("input", formChanged);
        save.form.addEventListener("change", formChanged);
    }());

    function formChanged() {
        var saveButton = $('#saveButton:first');
        var error = false;

        $("input").each(function () {
            var isInteger = $(this).hasClass("integer");
            var isFloat = $(this).hasClass("float");
            var isBoolean = $(this).hasClass("boolean");

            if (isInteger || isFloat) {

                if (isNaN(this.valueAsNumber)) {
                    $(this).addClass("field-invalid-highlight")
                    error = true;

                } else if (isInteger && !Number.isInteger(this.valueAsNumber)) {
                    $(this).addClass("field-invalid-highlight")
                    error = true;

                } else {
                    $(this).removeClass("field-invalid-highlight")
                }

            } else if (isBoolean) {
                // not sure why this is necessary, value does not track checked state?
                $(this).prop("value", !(!$(this).prop("checked")));
            }

        });

        saveButton.prop("disabled", error);
    }

    <%-- The index marks where you are while scrolling, exactly as the weblog
         settings rail does. IntersectionObserver only, guarded: without it the
         index is still a working list of anchor links and the server-rendered
         .is-current simply stays on the first group. --%>
    (function () {
        var index = document.getElementById('globalConfigSectionIndex');
        if (!index || !('IntersectionObserver' in window)) {
            return;
        }

        var links = {};
        var heads = [];
        Array.prototype.forEach.call(index.querySelectorAll('a[href^="#"]'), function (link) {
            var head = document.getElementById(decodeURIComponent(link.getAttribute('href').substring(1)));
            if (head) {
                links[head.id] = link;
                heads.push(head);
            }
        });
        if (heads.length === 0) {
            return;
        }

        var inBand = {};

        function mark(id) {
            heads.forEach(function (head) {
                links[head.id].classList.toggle('is-current', head.id === id);
            });
        }

        // The band is the top 30% of the viewport: the heading in it is the
        // group you are reading. When a long group has scrolled past its own
        // heading nothing is in the band, and the previous mark stays put.
        var observer = new IntersectionObserver(function (entries) {
            entries.forEach(function (entry) {
                inBand[entry.target.id] = entry.isIntersecting;
            });
            for (var i = 0; i < heads.length; i++) {
                if (inBand[heads[i].id]) {
                    mark(heads[i].id);
                    return;
                }
            }
        }, { rootMargin: '0px 0px -70% 0px' });

        heads.forEach(function (head) {
            observer.observe(head);
        });
    }());

</script>
