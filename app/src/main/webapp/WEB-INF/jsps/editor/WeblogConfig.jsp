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
    <spring:message code="websiteSettings.subtitle" arguments="${actionWeblog.handle}"/>
</p>

<c:url var="weblogRemoveUrl" value="/roller-ui/authoring/weblogRemove.rol">
    <c:param name="weblog" value="${actionWeblog.handle}"/>
</c:url>

<%-- Settings is a long form with a rail (see
     docs/design/forms/settings-with-rail.html, the approved card): the field
     groups on the left, and a 252px rail holding a section index -- the spine
     marks where you are, exactly as the nav rail does -- plus a Save that is
     reachable without scrolling past nine groups to find it.

     The <form> IS the grid container, so the rail sits in its second column
     without a display:contents indirection and the Save button stays a plain
     descendant of the form it submits. Hidden inputs are display:none and so
     never become grid items. --%>
<form action="${pageContext.request.contextPath}/roller-ui/authoring/weblogConfig!save.rol" method="post" class="settings-grid form-stacked">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>

<div>

    <%-- ***** General settings ***** --%>

    <h2 class="section-head" id="settings-general"><spring:message code="websiteSettings.generalSettings"/></h2>

    <%-- The one question this page never answered: what URL is this?
         Read-only, because the handle (and the custom domain below) are what
         change it. --%>
    <div class="row mb-3">
        <span class="col-sm-3 col-form-label"><spring:message code="websiteSettings.weblogUrl"/></span>
        <div class="col-sm-9">
            <a id="weblogAbsoluteUrl" href="${urls.weblogAbsolute(actionWeblog)}"
               target="_blank" rel="noopener">${urls.weblogAbsolute(actionWeblog)}</a>
            <button class="clipbutton" type="button"
                    data-clipboard-text="${urls.weblogAbsolute(actionWeblog)}"
                    aria-label="<spring:message code='generic.copyToClipboard'/>">
                <img src='<c:url value="/roller-ui/images/clippy.svg"/>' alt=""
                     style="width:0.9em; height:0.9em">
            </button>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_name"><spring:message code="websiteSettings.websiteTitle"/></label>
        <div class="col-sm-9">
            <input id="weblog_bean_name" type="text" name="bean.name" value="${fn:escapeXml(bean.name)}" size="30" maxlength="255" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_tagline"><spring:message code="generic.tagline"/></label>
        <div class="col-sm-9">
            <input id="weblog_bean_tagline" type="text" name="bean.tagline" value="${fn:escapeXml(bean.tagline)}" size="30" maxlength="255" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_copyright"><spring:message code="websiteSettings.copyright"/></label>
        <div class="col-sm-9">
            <input id="weblog_bean_copyright" type="text" name="bean.copyright" value="${fn:escapeXml(bean.copyright)}" size="30" maxlength="255" class="form-control"/>
            <div class="form-text"><spring:message code="websiteSettings.copyright.tip"/></div>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_icon"><spring:message code="websiteSettings.icon"/></label>
        <div class="col-sm-9">
            <input id="weblog_bean_icon" type="text" name="bean.icon" value="${fn:escapeXml(bean.icon)}" size="30" maxlength="255" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_about"><spring:message code="websiteSettings.about"/></label>
        <div class="col-sm-9">
            <textarea id="weblog_bean_about" name="bean.about" rows="3" cols="40" class="form-control">${fn:escapeXml(bean.about)}</textarea>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_emailAddress"><spring:message code="websiteSettings.emailAddress"/></label>
        <div class="col-sm-9">
            <input id="weblog_bean_emailAddress" type="email" name="bean.emailAddress" value="${fn:escapeXml(bean.emailAddress)}" size="30" maxlength="255" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_entryDisplayCount"><spring:message code="websiteSettings.entryDisplayCount"/></label>
        <div class="col-sm-9">
            <input id="weblog_bean_entryDisplayCount" type="number" name="bean.entryDisplayCount" value="${bean.entryDisplayCount}"
                   min="1" max="${maxEntriesPerPage}" size="4" class="form-control"/>
            <div class="form-text"><spring:message code="websiteSettings.entryDisplayCount.tip"
                    arguments="${maxEntriesPerPage}"/></div>
        </div>
    </div>

    <div class="row mb-3">
        <div class="offset-sm-3 col-sm-9">
            <div class="form-check">
                <label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.active" value="true" ${bean.active ? 'checked' : ''}/> <spring:message code="websiteSettings.active"/></label>
            </div>
        </div>
    </div>

    <div class="row mb-3">
        <div class="offset-sm-3 col-sm-9">
            <div class="form-check">
                <label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.commentsEnabled" value="true" ${bean.commentsEnabled ? 'checked' : ''}/> <spring:message code="weblogSettings.commentsEnabled"/></label>
            </div>
        </div>
    </div>

    <div class="row mb-3">
        <div class="offset-sm-3 col-sm-9">
            <div class="form-check">
                <label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.commentNotify" value="true" ${bean.commentNotify ? 'checked' : ''}/> <spring:message code="weblogSettings.commentNotify"/></label>
            </div>
        </div>
    </div>

    <%-- ***** Language/i18n settings ***** --%>

    <h2 class="section-head" id="settings-language"><spring:message code="websiteSettings.languageSettings"/></h2>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_locale"><spring:message code="createWebsite.locale"/></label>
        <div class="col-sm-9">
            <select id="weblog_bean_locale" name="bean.locale" class="form-select">
                <c:forEach items="${localesList}" var="opt">
                    <option value="${opt}" ${opt == bean.locale ? 'selected' : ''}>${fn:escapeXml(opt.displayName)}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_timeZone"><spring:message code="createWebsite.timezone"/></label>
        <div class="col-sm-9">
            <select id="weblog_bean_timeZone" name="bean.timeZone" class="form-select">
                <c:forEach items="${timeZonesList}" var="opt">
                    <option value="${opt}" ${opt == bean.timeZone ? 'selected' : ''}>${opt}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <%-- ***** Analytics settings ***** --%>

    <h2 class="section-head" id="settings-analytics"><spring:message code="websiteSettings.analyticsSettings"/></h2>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_analyticsSiteId"><spring:message code="websiteSettings.analyticsSiteId"/></label>
        <div class="col-sm-9">
            <input id="weblog_bean_analyticsSiteId" type="text" name="bean.analyticsSiteId" value="${fn:escapeXml(bean.analyticsSiteId)}" size="40" maxlength="64" class="form-control"/>
            <div class="form-text"><spring:message code="websiteSettings.analyticsSiteId.tip"/></div>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_analyticsShareUrl"><spring:message code="websiteSettings.analyticsShareUrl"/></label>
        <div class="col-sm-9">
            <input id="weblog_bean_analyticsShareUrl" type="text" name="bean.analyticsShareUrl" value="${fn:escapeXml(bean.analyticsShareUrl)}" size="40" maxlength="255" class="form-control"/>
            <div class="form-text"><spring:message code="websiteSettings.analyticsShareUrl.tip"/></div>
            <%-- A rejected save re-renders this page with the raw submitted bean, so
                 "not empty" alone would echo an unvalidated value (e.g. javascript:...)
                 as a live link. The prefix check below is the scheme allowlist for the
                 echoed link, case-insensitively mirroring myValidate's own check; "not
                 empty" short-circuits first so fn:toLowerCase never sees a null value. --%>
            <c:if test="${not empty bean.analyticsShareUrl
                    and (fn:startsWith(fn:toLowerCase(bean.analyticsShareUrl), 'https://')
                    or fn:startsWith(fn:toLowerCase(bean.analyticsShareUrl), 'http://'))}">
                <div class="form-text"><a href="${fn:escapeXml(bean.analyticsShareUrl)}" target="_blank" rel="noopener">${fn:escapeXml(bean.analyticsShareUrl)}</a></div>
            </c:if>
        </div>
    </div>

    <%-- ***** Newsletter settings ***** --%>

    <h2 class="section-head" id="settings-newsletter"><spring:message code="websiteSettings.newsletterSettings"/></h2>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_newsletterListUuid"><spring:message code="websiteSettings.newsletterListUuid"/></label>
        <div class="col-sm-9">
            <input id="weblog_bean_newsletterListUuid" type="text" name="bean.newsletterListUuid" value="${fn:escapeXml(bean.newsletterListUuid)}" size="40" maxlength="64" class="form-control"/>
            <div class="form-text"><spring:message code="websiteSettings.newsletterListUuid.tip"/></div>
        </div>
    </div>

    <%-- ***** Business settings ***** --%>

    <h2 class="section-head" id="settings-business"><spring:message code="websiteSettings.businessSettings"/></h2>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_businessId"><spring:message code="websiteSettings.business"/></label>
        <div class="col-sm-9">
            <c:choose>
                <c:when test="${businessesUnavailable}">
                    <%-- No list to choose from: carry the current business, never post "None". --%>
                    <select id="weblog_bean_businessId" class="form-select" disabled="disabled">
                        <option selected="selected"><c:choose><c:when test="${empty bean.businessId}"><spring:message code="websiteSettings.business.none"/></c:when><c:otherwise><spring:message code="websiteSettings.business.kept"/></c:otherwise></c:choose></option>
                    </select>
                    <input type="hidden" name="bean.businessId" value="${fn:escapeXml(bean.businessId)}"/>
                </c:when>
                <c:otherwise>
                    <select id="weblog_bean_businessId" name="bean.businessId" class="form-select">
                        <option value=""><spring:message code="websiteSettings.business.none"/></option>
                        <c:forEach var="b" items="${businesses}">
                            <option value="${fn:escapeXml(b.id)}"<c:if test="${bean.businessId == b.id}"> selected="selected"</c:if>>${fn:escapeXml(b.name)}</option>
                        </c:forEach>
                    </select>
                </c:otherwise>
            </c:choose>
            <div class="form-text"><spring:message code="websiteSettings.business.tip"/></div>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_placeType"><spring:message code="websiteSettings.placeType"/></label>
        <div class="col-sm-9">
            <select id="weblog_bean_placeType" name="bean.placeType" class="form-select">
                <option value=""><spring:message code="websiteSettings.placeType.none"/></option>
                <option value="LodgingBusiness"<c:if test="${bean.placeType == 'LodgingBusiness'}"> selected="selected"</c:if>><spring:message code="websiteSettings.placeType.lodging"/></option>
            </select>
            <div class="form-text"><spring:message code="websiteSettings.placeType.tip"/></div>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_placeLocality"><spring:message code="websiteSettings.placeLocality"/></label>
        <div class="col-sm-9">
            <input id="weblog_bean_placeLocality" type="text" name="bean.placeLocality" value="${fn:escapeXml(bean.placeLocality)}" size="40" maxlength="128" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_placeRegion"><spring:message code="websiteSettings.placeRegion"/></label>
        <div class="col-sm-9">
            <input id="weblog_bean_placeRegion" type="text" name="bean.placeRegion" value="${fn:escapeXml(bean.placeRegion)}" size="40" maxlength="128" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_placeCountry"><spring:message code="websiteSettings.placeCountry"/></label>
        <div class="col-sm-9">
            <input id="weblog_bean_placeCountry" type="text" name="bean.placeCountry" value="${fn:escapeXml(bean.placeCountry)}" size="4" maxlength="2" class="form-control"/>
            <div class="form-text"><spring:message code="websiteSettings.placeCountry.tip"/></div>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_placeLat"><spring:message code="websiteSettings.placeLat"/></label>
        <div class="col-sm-9">
            <input id="weblog_bean_placeLat" type="text" name="bean.placeLat" value="${fn:escapeXml(bean.placeLat)}" size="40" maxlength="16" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_placeLng"><spring:message code="websiteSettings.placeLng"/></label>
        <div class="col-sm-9">
            <input id="weblog_bean_placeLng" type="text" name="bean.placeLng" value="${fn:escapeXml(bean.placeLng)}" size="40" maxlength="16" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_bookingUrl"><spring:message code="websiteSettings.bookingUrl"/></label>
        <div class="col-sm-9">
            <input id="weblog_bean_bookingUrl" type="text" name="bean.bookingUrl" value="${fn:escapeXml(bean.bookingUrl)}" size="40" maxlength="255" class="form-control"/>
            <div class="form-text"><spring:message code="websiteSettings.bookingUrl.tip"/></div>
        </div>
    </div>

    <%-- ***** Custom domain settings ***** --%>

    <h2 class="section-head" id="settings-customdomain"><spring:message code="websiteSettings.customDomainSettings"/></h2>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblog_bean_customDomain"><spring:message code="websiteSettings.customDomain"/></label>
        <div class="col-sm-9">
            <input id="weblog_bean_customDomain" type="text" name="bean.customDomain" value="${fn:escapeXml(bean.customDomain)}" size="40" maxlength="255" class="form-control"/>
            <div class="form-text"><spring:message code="websiteSettings.customDomain.tip"/></div>
            <c:if test="${not empty actionWeblog.customDomain}">
                <div class="form-text">
                    <a href="https://${fn:escapeXml(actionWeblog.customDomain)}/"
                       target="_blank" rel="noopener">https://${fn:escapeXml(actionWeblog.customDomain)}/</a>
                </div>
            </c:if>
            <c:if test="${not empty customDomainWarning}">
                <div class="form-text text-warning">
                    <spring:message code="websiteSettings.customDomain.outsideZone"/>
                </div>
            </c:if>
        </div>
    </div>

</div>

<%-- ====================================================================== --%>
<%-- The rail: where you are, Save, and the quiet way out. --%>

<aside class="settings-rail">

    <div class="rail-box settings-rail-index">
        <h2 class="section-head"><spring:message code="websiteSettings.sections"/></h2>
        <nav class="section-index" id="settingsSectionIndex"
             aria-label="<spring:message code="websiteSettings.sections"/>">
            <a href="#settings-general" class="is-current"><spring:message code="websiteSettings.generalSettings"/></a>
            <a href="#settings-language"><spring:message code="websiteSettings.languageSettings"/></a>
            <a href="#settings-analytics"><spring:message code="websiteSettings.analyticsSettings"/></a>
            <a href="#settings-newsletter"><spring:message code="websiteSettings.newsletterSettings"/></a>
            <a href="#settings-business"><spring:message code="websiteSettings.businessSettings"/></a>
            <a href="#settings-customdomain"><spring:message code="websiteSettings.customDomainSettings"/></a>
        </nav>
    </div>

    <div class="rail-box">
        <button type="submit" class="btn btn-primary w-100"><spring:message code="websiteSettings.button.update"/></button>
    </div>

    <%-- Removal is a quiet text link, matching the editor's delete: it opens
         weblogRemove.rol, the confirmation page that carries the irreversible-
         removal warning and the CSRF-protected POST that actually removes the
         weblog. It was a second <form> below this one, POSTing to that same
         address -- which has only a GET mapping, so the button answered 405.
         A link is both what the card asks for and the working form of it.

         The rail's one caps label belongs on the section index, per the
         card; the delete box is a single quiet link and needs none. --%>
    <div class="rail-box">
        <a class="delete-link" href="${weblogRemoveUrl}"><spring:message code="websiteSettings.button.remove"/></a>
    </div>

</aside>

<sec:csrfInput/>
</form>

<%-- The index marks where you are while scrolling. IntersectionObserver only,
     guarded: without it the index is still a working list of anchor links and
     the server-rendered .is-current simply stays on the first group. The links
     are plain anchors, so scrolling is the browser's own -- no smooth-scroll
     behaviour to withhold from a reader who asked for reduced motion. --%>
<script>
(function () {
    // ClipboardJS is loaded globally by head.jsp.
    var clipboard = new ClipboardJS('.clipbutton');
    clipboard.on('success', function (e) {
        e.trigger.classList.add('copied');
        setTimeout(function () { e.trigger.classList.remove('copied'); }, 1500);
        e.clearSelection();
    });
})();

(function () {
    var index = document.getElementById('settingsSectionIndex');
    if (!index || !('IntersectionObserver' in window)) {
        return;
    }

    var links = {};
    var heads = [];
    Array.prototype.forEach.call(index.querySelectorAll('a[href^="#"]'), function (link) {
        var head = document.getElementById(link.getAttribute('href').substring(1));
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

    // The band is the top 30% of the viewport: the heading in it is the group
    // you are reading. When a long group has scrolled past its own heading
    // nothing is in the band, and the previous mark deliberately stays put.
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
