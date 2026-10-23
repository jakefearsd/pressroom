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
<script src="<c:url value='/theme/scripts/roller-guard-submit.js'/>"></script>

<p class="subtitle"><spring:message code="maintenance.subtitle"/></p>

<%-- guard-submit: rebuild-index and regenerate-renditions are the slow,
     re-run-the-whole-thing-again operations this guard exists for. One form
     with three formaction-routed buttons, same shape as Entries.jsp's bulk
     actions, so the opt-in is form-level. --%>
<form action="${pageContext.request.contextPath}/roller-ui/admin/maintenance.rol" method="post" class="form-stacked guard-submit">
<sec:csrfInput/>

    <div class="mb-3">
        <label for="maintenanceWeblog"><spring:message code="maintenance.prompt.weblog"/></label>
        <select name="weblogId" id="maintenanceWeblog" class="form-select">
            <%-- A <select> with no explicitly selected <option> defaults to
                 its FIRST option per the HTML spec -- not "nothing chosen".
                 Without this placeholder, an admin who opens the page and
                 clicks an action button without touching the dropdown would
                 silently submit whichever weblog getWeblogs() happens to
                 list first (most-recently-created, per its ORDER BY), with
                 no error and no visual cue a choice was ever made. Disabled
                 so it can never be re-selected once a real weblog is picked;
                 selected only while selectedWeblog is null, so exactly one
                 <option> ever carries "selected".

                 NOTE: per WHATWG HTML's form-entry-list construction, a
                 <select>'s option contributes an entry only when it is
                 selected AND NOT disabled. Left untouched, this placeholder
                 is both -- so submitting the form this way sends NO
                 "weblogId" parameter at all, not weblogId="". That is why
                 the three POST handlers below declare their @RequestParam
                 weblogId as required=false: a missing parameter has to reach
                 resolveWeblog() as null and land on
                 maintenance.error.noSuchWeblog exactly like an explicit
                 empty one, rather than failing request binding with a 400
                 before the controller ever runs. --%>
            <option value=""
                <c:if test="${selectedWeblog == null}">selected="selected"</c:if>
                disabled="disabled"><spring:message code="maintenance.select.placeholder"/></option>
            <c:forEach var="w" items="${weblogs}">
                <option value="${fn:escapeXml(w.id)}" data-handle="${fn:escapeXml(w.handle)}"
                    <c:if test="${selectedWeblog != null && selectedWeblog.id == w.id}">selected="selected"</c:if>
                >${fn:escapeXml(w.name)} (${fn:escapeXml(w.handle)})</option>
            </c:forEach>
        </select>
    </div>

    <%-- Each prompt and its button are one bordered row, so the three
         operations read as three separate choices rather than one wall of
         sentences with buttons somewhere in it. The two long-running ones
         confirm and NAME the selected weblog first: the <select> above is the
         only thing that says which weblog an unlabelled "Rebuild" acts on,
         and it is easy to have scrolled past.

         The name is NOT baked in at render time: selectedWeblog is null on a
         first visit (the prompt read "... for ?") and the admin changes the
         select after the page has rendered. Each button carries its prompt as
         a data-confirm-template with weblogToken where the handle goes; the
         script at the end writes the live data-confirm from the chosen
         option's data-handle, on load and on every change. With nothing
         chosen there is no prompt to name, and the server refuses with
         maintenance.error.noSuchWeblog. --%>
    <c:set var="weblogToken" value="@WEBLOG@"/>

    <div class="maintenance-op">
        <p><spring:message code="maintenance.prompt.flush"/></p>
        <button type="submit" class="btn btn-secondary"
                formaction="${pageContext.request.contextPath}/roller-ui/admin/maintenance!flushCache.rol"><spring:message code="maintenance.button.flush"/></button>
    </div>

    <%-- data-confirm, NOT onclick="return confirm('...')": an HTML escape in a
         JS-string position is decoded by the HTML parser before the JS
         compiles, so one apostrophe in a translated value breaks the handler
         and the button submits with NO confirmation. See roller.js.
         Escaped exactly once, as a whole: the template's argument is the
         plain token, so fn:escapeXml on the formatted message is the one
         escape the attribute needs and the browser hands the script the
         literal text. The live data-confirm rendered for a selected weblog
         escapes the handle the same way. The bundle values must not contain
         a raw double quote; AdminJspHygieneTest pins that. --%>
    <c:if test="${rc:getBooleanProp('search.enabled')}">
        <spring:message code="maintenance.confirm.index" arguments="${weblogToken}" var="confirmIndex"/>
        <div class="maintenance-op">
            <p><spring:message code="maintenance.prompt.index"/></p>
            <button type="submit" class="btn btn-secondary"
                    data-confirm-template="${fn:escapeXml(confirmIndex)}"
                    <c:if test="${selectedWeblog != null}">data-confirm="${fn:escapeXml(fn:replace(confirmIndex, weblogToken, selectedWeblog.handle))}"</c:if>
                    data-busy-label="<spring:message code='maintenance.index.building'/>"
                    formaction="${pageContext.request.contextPath}/roller-ui/admin/maintenance!index.rol"><spring:message code="maintenance.button.index"/></button>
        </div>
    </c:if>

    <spring:message code="maintenance.confirm.regenerateRenditions" arguments="${weblogToken}" var="confirmRenditions"/>
    <div class="maintenance-op">
        <p><spring:message code="maintenance.prompt.regenerateRenditions"/></p>
        <button type="submit" class="btn btn-secondary"
                data-confirm-template="${fn:escapeXml(confirmRenditions)}"
                <c:if test="${selectedWeblog != null}">data-confirm="${fn:escapeXml(fn:replace(confirmRenditions, weblogToken, selectedWeblog.handle))}"</c:if>
                data-busy-label="<spring:message code='maintenance.regenerateRenditions.building'/>"
                formaction="${pageContext.request.contextPath}/roller-ui/admin/maintenance!regenerateRenditions.rol"><spring:message code="maintenance.button.regenerateRenditions"/></button>
    </div>

</form>

<script>
    // Rewrites each long operation's prompt so it names the weblog the
    // select currently holds (see the comment above the first operation),
    // and keeps the actions disabled until one is chosen.
    (function () {
        var select = document.getElementById("maintenanceWeblog");
        if (!select) {
            return;
        }
        var token = "${weblogToken}";
        function rewrite() {
            var option = select.options[select.selectedIndex];
            var handle = option ? option.getAttribute("data-handle") : null;
            // Every action acts on the chosen weblog, so none is live
            // before there is one.
            var actions = select.form.querySelectorAll("button[type=submit]");
            for (var a = 0; a < actions.length; a++) {
                actions[a].disabled = !handle;
            }
            var buttons = select.form.querySelectorAll("[data-confirm-template]");
            for (var i = 0; i < buttons.length; i++) {
                if (handle) {
                    buttons[i].setAttribute("data-confirm",
                            buttons[i].getAttribute("data-confirm-template").split(token).join(handle));
                } else {
                    buttons[i].removeAttribute("data-confirm");
                }
            }
        }
        select.addEventListener("change", rewrite);
        rewrite();
    })();
</script>
