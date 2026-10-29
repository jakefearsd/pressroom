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

<p class="subtitle">
    <spring:message code="themeEditor.subtitle" arguments="${actionWeblog.handle}"/>
</p>

<script src="<c:url value='/theme/scripts/pressroom-guard-submit.js'/>"></script>

<form action="${pageContext.request.contextPath}/pressroom-ui/authoring/themeEdit!save.rol" method="post" class="guard-submit">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>

    <%-- Two choices side-by-side: choose Shared or Custom Theme.

         Offered only when there is a choice to make. With
         themes.customtheme.allowed off and the weblog on a shared theme,
         customThemeAllowed is false and the "custom" half is refused by
         ThemeEditController.save whatever the page shows -- so the row was a
         lone radio, already selected, next to nothing. The whole row is
         omitted, and a hidden themeType carries the only answer there is:
         save() does nothing at all when themeType is absent, so this is not
         optional decoration. A weblog already ON a custom theme keeps the
         control, matching the controller's grandfathering rule. --%>
    <c:choose>
    <c:when test="${customThemeAllowed}">

    <%-- Side by side from md up; stacked on a phone, where two half-width
         cards squeezed their descriptions into a word per line. --%>
    <div class="row row-display-flex g-3">

        <div class="col-12 col-md-6">
            <div class="card">
                <div class="card-body" id="sharedChooser">
                    <%-- .section-head, not a bare h2: this labels the chooser
                         card, and a bare h2 renders at Bootstrap's 32px
                         against a 20px page title. --%>
                    <h2 class="section-head">
                        <input id="sharedRadio" type="radio" name="themeType" value="shared"
                            <c:if test="${!customTheme}">checked</c:if>/>&nbsp;
                        <label for="sharedRadio"><spring:message code="themeEditor.sharedTheme"/></label>
                    </h2>
                    <spring:message code="themeEditor.sharedThemeDescription"/>
                </div>
            </div>
        </div>

        <div class="col-12 col-md-6">
            <div class="card">
                <div class="card-body" id="customChooser">
                    <h2 class="section-head">
                        <input id="customRadio" type="radio" name="themeType" value="custom"
                            <c:if test="${customTheme}">checked</c:if>/>&nbsp;
                        <label for="customRadio"><spring:message code="themeEditor.customTheme"/></label>
                    </h2>
                    <spring:message code="themeEditor.customThemeDescription"/>
                </div>
            </div>
        </div>

    </div>

    </c:when>
    <c:otherwise>
        <input type="hidden" name="themeType" value="shared"/>
    </c:otherwise>
    </c:choose>

    <%-- ================================================= --%>

    <div id="sharedNoChange" style="display:none;">

        <%-- "Your weblog uses the X theme." A plain paragraph: p.lead was
             20px, the page title's size; the theme name carries the
             emphasis, by weight (the <b> is in the message). The old
             trailing clause ("using the Shared Theme default stylesheet")
             was jargon about the normal case; only the exception, a custom
             stylesheet copy, is worth a sentence. --%>
        <p class="mt-3">
            <spring:message code="themeEditor.currentTheme" arguments="${fn:escapeXml(currentThemeName)}"/>
            <c:if test="${sharedThemeCustomStylesheet}">
                <spring:message code="themeEditor.customStylesheetNote"/>
            </c:if>
        </p>

        <p><a href="${urls.weblogAbsolute(actionWeblog)}" target="_blank" rel="noopener">
            <spring:message code="themeEditor.viewYourBlog"/></a></p>

    </div>

    <%-- ================================================= --%>

    <div id="themeChooser" style="display:none;">

        <%-- A grid of cards, each showing the theme's own preview image, in
             place of a one-line <select> beside a single thumbnail fetched
             over ajax on every change -- which is also how a description
             carrying a quote used to leave the preview frozen (see
             ThemeDataServlet). Every card is a <label> wrapping its radio, so
             the whole card is the hit target and the browser does the
             grouping; the change listener is delegated, not inline. --%>
        <%-- The caps-label role, not p.lead's 20px: this names the card grid
             below (aria-labelledby), the way a section head does. --%>
        <p class="section-head" id="themeChooserLabel"><spring:message code="themeEditor.selectTheme"/></p>

        <%-- The current theme id may name no card at all: a blank
             selectedThemeId (the weblog is on a custom theme), OR a non-blank
             one that matches nothing in ${themes} (a retired/removed shared
             theme id still on the row). Either way the fallback is the first
             card -- computed once, up front, rather than folded into the
             per-card condition, because "found anywhere in the list" is not
             expressible from inside the same forEach that is looking for it. --%>
        <c:set var="selectedThemeFound" value="false"/>
        <c:forEach items="${themes}" var="opt">
            <c:if test="${opt.id == selectedThemeId}">
                <c:set var="selectedThemeFound" value="true"/>
            </c:if>
        </c:forEach>

        <div class="theme-cards" role="radiogroup" aria-labelledby="themeChooserLabel">
            <%-- The first card is checked when the weblog's current theme
                 isn't found among the rendered cards (blank selectedThemeId
                 on a custom theme, or a shared id that no longer exists).
                 That is not cosmetic: the <select> this replaced always
                 posted a value, because a browser selects the first option
                 when none is marked selected, and the custom-to-shared path
                 relies on it -- with no radio checked the form posts no
                 selectedThemeId at all and the save answers "theme not
                 found". --%>
            <c:forEach items="${themes}" var="opt" varStatus="themeStatus">
                <label class="theme-card">
                    <c:if test="${not empty opt.previewImage}">
                        <img class="theme-card-thumb" alt=""
                             src="${siteURL}/themes/${fn:escapeXml(opt.id)}/${fn:escapeXml(opt.previewImage.path)}"/>
                    </c:if>
                    <span class="theme-card-name">
                        <input class="theme-card-radio" type="radio" name="selectedThemeId"
                               value="${fn:escapeXml(opt.id)}"
                               <c:if test="${opt.id == selectedThemeId or (not selectedThemeFound and themeStatus.first)}">checked</c:if>/>
                        ${fn:escapeXml(opt.name)}
                    </span>
                    <span class="theme-card-desc">${fn:escapeXml(opt.description)}</span>
                </label>
            </c:forEach>
        </div>

    </div>

    <%-- ================================================= --%>

    <%-- Below: four mutually-exclusive "something is about to change" blocks,
         each with its own copy of the Preview/Update/Cancel action row (kept
         per-block, not consolidated, so existing IT selectors like
         "#sharedChangeToShared button[type='submit']" keep working). Exactly
         one of these six state blocks (this four, plus #sharedNoChange /
         #customNoChange above) may be visible at a time -- updateView() below
         now calls hideAll() unconditionally on every call, first, before
         deciding what to show, rather than relying on each branch's own
         hide()/show() pairs to have covered every other block. That
         unconditional reset is the actual fix for "three Update Theme
         buttons visible at once": some transitions (moving from a *NoChange
         state straight into a *ChangeTo* one) previously showed the new
         block's button without ever hiding the old block's, so more than one
         stayed in the DOM, visible, simultaneously. --%>

    <div id="sharedChangeToShared" style="display:none;">

        <div class="alert alert-warning my-4">
            <spring:message code="themeEditor.proposedSharedThemeChange"/>
        </div>

        <%-- Preview and Update buttons --%>
        <p> <spring:message code="themeEditor.previewDescription"/> </p>
        <button type="button" name="themePreview" class="btn btn-secondary"
            onclick="fullPreview()"><spring:message code="themeEditor.preview"/></button>

        <button type="submit" class="btn btn-primary"
                data-busy-label="<spring:message code='themeEditor.saving'/>"><spring:message code="themeEditor.save"/></button>

        <button type="button" class="btn btn-secondary" onclick="cancelChanges()"><spring:message code="generic.cancel"/></button>

    </div>

    <div id="sharedChangeToCustom" style="display:none;">

        <div class="alert alert-warning my-4">
            <spring:message code="themeEditor.proposedSharedChangeToCustom"/>
        </div>

        <c:choose>
<c:when test="${firstCustomization}">
            <p>
                <spring:message code="themeEditor.importRequired"/>
                <input type="hidden" name="importTheme" value="${true}"/>
            </p>
        </c:when>
<c:otherwise>
            <p><spring:message code="themeEditor.existingTemplatesWarning"/></p>
            <label><input type="checkbox" name="importTheme" value="true" ${importTheme ? 'checked' : ''}/> <spring:message code="themeEditor.importAndOverwriteTemplates"/></label>
        </c:otherwise>
</c:choose><%-- Update button --%>
        <button type="submit" class="btn btn-primary"
                data-busy-label="<spring:message code='themeEditor.saving'/>"><spring:message code="themeEditor.save"/></button>

        <button type="button" class="btn btn-secondary" onclick="cancelChanges()"><spring:message code="generic.cancel"/></button>

    </div>

    <div id="customNoChange" style="display:none;">
        <p class="mt-3"><spring:message code="themeEditor.youAreUsingACustomTheme"/></p>

        <p><a href="${urls.weblogAbsolute(actionWeblog)}" target="_blank" rel="noopener">
            <spring:message code="themeEditor.viewYourBlog"/></a></p>
    </div>

    <div id="customChangeToShared" style="display:none;">

        <div class="alert alert-warning my-4">
            <spring:message code="themeEditor.proposedChangeToShared"/>
        </div>

        <%-- Preview and Update buttons --%>
        <p> <spring:message code="themeEditor.previewDescription"/> </p>
        <button type="button" name="themePreview" class="btn btn-secondary"
            onclick="fullPreview()"><spring:message code="themeEditor.preview"/></button>

        <button type="submit" class="btn btn-primary"
                data-busy-label="<spring:message code='themeEditor.saving'/>"><spring:message code="themeEditor.save"/></button>

        <button type="button" class="btn btn-secondary" onclick="cancelChanges()"><spring:message code="generic.cancel"/></button>

    </div>

<sec:csrfInput/>
</form>

<script type="text/javascript">

    var proposedChangeType = ""
    var proposedThemeId = ""
    var originalThemeId = "${themeId}"
    var originalType = ""

    <%-- updateView takes the type as a STRING now, not the jQuery radio it
         used to be handed. With themes.customtheme.allowed off there are no
         type radios on the page at all (see the markup above), so a function
         that reads selected[0].value has nothing to read; a plain string is
         also simply what every caller already had. The #sharedChooser /
         #customChooser class toggles below run against an empty jQuery set in
         that case, which is a no-op. --%>
    $.when( $.ready ).then(function() {

        <c:choose>
<c:when test="${customTheme}">
        originalType = "custom"
        updateView("custom");
        </c:when>
<c:otherwise>
        originalType = "shared"
        updateView("shared");
        </c:otherwise>
</c:choose>
        <%-- Delegated, not an inline onchange on 50 controls: an inline
             handler is a second place generated text reaches raw JavaScript
             (see pressroom.js's data-confirm note), and the cards are generated
             from ${themes}. The type radios are wired the same way for the
             same reason. --%>
        $('.theme-cards').on('change', "input[name='selectedThemeId']", function () {
            proposeSharedThemeChange(this.value);
        });
        $("input[name='themeType']").on('change', function () {
            proposeThemeTypeChange(this.value);
        });
    });

    function proposeThemeTypeChange(type) {

        if (type === 'shared') {
            proposedChangeType = "shared"
            proposedThemeId = selectedThemeId()

        } else {
            proposedThemeId = originalThemeId
            proposedChangeType = "custom"
        }
        updateView(type)
    }

    function proposeSharedThemeChange(themeId) {
        proposedThemeId = themeId;
        updateView("shared")
    }

    <%-- The id of the checked theme card, or "" when nothing is checked (a
         weblog on a custom theme has no shared theme selected). --%>
    function selectedThemeId() {
        var checked = document.querySelector(".theme-cards input[name='selectedThemeId']:checked");
        return checked ? checked.value : "";
    }

    function cancelChanges() {

        proposedThemeId = originalThemeId;
        proposedChangeType = originalType;

        hideAll();

        if ( originalType === "custom" ) {
            $("#sharedRadio").prop("checked", false);
            $("#customRadio").prop("checked", true);
            updateView("custom");

        } else {
            $("#sharedRadio").prop("checked", true);
            $("#customRadio").prop("checked", false);
            $(".theme-cards input[name='selectedThemeId'][value='" + originalThemeId + "']")
                .prop("checked", true);
            updateView("shared");
        }

    }

    function hideAll() {
        $('#themeChooser').hide();
        $('#customNoChange').hide();
        $('#customChangeToShared').hide();
        $('#sharedChangeToShared').hide();
        $('#sharedNoChange').hide();
        $('#sharedChangeToCustom').hide();
    }

    function fullPreview() {
        var themeId = selectedThemeId();
        if (!themeId) {
            return;
        }
        window.open('<c:url value="/pressroom-ui/authoring/preview/${actionWeblog.handle}"/>?theme='
            + encodeURIComponent(themeId));
    }

    <%-- Shows exactly one of the six state blocks for the given theme type.
         hideAll() runs first on every call -- unconditionally -- so this never
         has to rely on the *previous* call having hidden the right things;
         that was the bug (see the comment above the state blocks in the
         markup): some transitions showed the new block without hiding the old
         one, leaving more than one Update Theme button visible at once. --%>
    function updateView(type) {

        changed =
               (proposedThemeId    !== "" && proposedThemeId    !== originalThemeId)
            || (proposedChangeType !== "" && proposedChangeType !== originalType )

        hideAll();

        if (type === 'shared') {

            $('#sharedChooser').addClass("chooser-selected")
            $('#customChooser').removeClass("chooser-selected")

            $('#themeChooser').show();

            if ( !changed ) {
                $('#sharedNoChange').show();
            } else if ( originalType === "shared" ) {
                $('#sharedChangeToShared').show();
            } else {
                $('#customChangeToShared').show();
            }

        } else {

            $('#sharedChooser').removeClass("chooser-selected")
            $('#customChooser').addClass("chooser-selected")

            if ( !changed ) {
                $('#customNoChange').show();
            } else {
                $('#sharedChangeToCustom').show();
            }

        }
    }

</script>
