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

<%-- Prevent annoying scrolling. taken from http://stackoverflow.com/a/10548809/3591946 --%>
<script type="text/javascript">
    $(document).ready(function () {
        $("a[href='#'][data-bs-toggle='collapse']").click(function (e) {
            e.preventDefault();
        });
    });
</script>

<%-- Titling, processing actions different between entry add and edit --%>
<c:choose>
<c:when test="${actionName == 'entryEdit'}">
    <c:set var="subtitleKey">weblogEdit.subtitle.editEntry</c:set>
    <c:set var="mainAction">entryEdit</c:set>
</c:when>
<c:otherwise>
    <c:set var="subtitleKey">weblogEdit.subtitle.newEntry</c:set>
    <c:set var="mainAction">entryAdd</c:set>
</c:otherwise>
</c:choose><p class="subtitle">
    <spring:message code="${subtitleKey}" arguments="${actionWeblog.handle}"/>
</p>

<%-- The editor is a writing surface with a publish rail (see
     docs/design/editor/editor-writing-surface.html, the approved card):
     the main column carries title, permalink and the Markdown editor;
     everything about *managing* the entry lives in a 252px rail. The form
     is display:contents so the newsletter/revisions cards -- which carry
     their own <form>s and therefore cannot nest inside #entry -- can still
     join the rail's grid column below it. --%>

<%-- Local draft recovery. Static script, not a JSP include: every string it
     needs rides on the bar's data- attributes below. --%>
<script src="<c:url value='/theme/scripts/roller-draft.js'/>"></script>
<script src="<c:url value='/theme/scripts/roller-guard-submit.js'/>"></script>
<script>
    <%-- Last-used category. Keyed per install and per weblog, beside the
         draft keys and for the same reason: two Roller installs on one origin
         must not share storage, and a category id from weblog A means nothing
         on weblog B. Purely a default for a NEW entry -- an entry being edited
         already has its own category, and preselecting over that would silently
         refile it. If the stored id is no longer in the list (the category was
         renamed away or deleted) nothing happens; the select keeps its own
         default. No schema, no server round trip. --%>
    (function () {
        var key = "roller.lastCategory.v1:${pageContext.request.contextPath}:${actionWeblog.handle}";
        document.addEventListener('DOMContentLoaded', function () {
            var select = document.getElementById('entry_bean_categoryId');
            if (!select) {
                return;
            }
            if ("${actionName}" === "entryAdd") {
                var last = null;
                try {
                    last = window.localStorage.getItem(key);
                } catch (e) {
                    // Private-browsing modes throw on access rather than
                    // returning null. A missing convenience, not an error.
                    last = null;
                }
                if (last && select.querySelector("option[value='" + last + "']")) {
                    select.value = last;
                }
            }
            var form = document.getElementById('entry');
            if (form) {
                form.addEventListener('submit', function () {
                    try {
                        window.localStorage.setItem(key, select.value);
                    } catch (e) {
                        // Same as above: storage unavailable, nothing to do.
                    }
                });
            }
        });
    })();

    <%-- Session-expiry banner: see the markup comment above it. --%>
    <%-- On ready: this script block sits above the markup it looks for. --%>
    document.addEventListener('DOMContentLoaded', function () {
        var bar = document.getElementById('sessionExpiryBar');
        if (!bar) {
            return;
        }
        var timeout = parseInt(bar.dataset.timeout, 10);
        if (!(timeout > 180)) {
            // A session shorter than the warning lead time would show the
            // banner permanently, which teaches people to ignore it.
            return;
        }
        var warnAfterMs = (timeout - 120) * 1000;
        var timer = null;
        var arm = function () {
            bar.hidden = true;
            window.clearTimeout(timer);
            timer = window.setTimeout(function () {
                bar.hidden = false;
            }, warnAfterMs);
        };
        ['keydown', 'click', 'input'].forEach(function (type) {
            document.addEventListener(type, arm, true);
        });
        arm();
    });
</script>


<%-- Request scope, not page scope: EntryEditor.jsp arrives via jsp:include
     and cannot see page-scoped variables set here. --%>
<c:set var="draftKey" scope="request"
       value="roller.draft.v1:${pageContext.request.contextPath}:${actionWeblog.handle}:${actionName}:${empty bean.id ? 'new' : bean.id}"/>
<c:set var="draftNewKey" scope="request"
       value="roller.draft.v1:${pageContext.request.contextPath}:${actionWeblog.handle}:entryAdd:new"/>

<div class="editor-grid">

<form id="entry" method="post" class="form-stacked editor-form">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <input type="hidden" name="bean.status" value="${bean.status}"/>
    <c:choose>
<c:when test="${actionName == 'entryEdit'}">
        <input type="hidden" name="bean.id" value="${bean.id}"/>
    </c:when>
</c:choose>

    <%-- ================================================================== --%>
    <%-- The writing surface: title, permalink, editor --%>

    <div class="editor-main">

        <%-- Session-expiry warning. Purely local arithmetic against the
             container's own maxInactiveInterval -- no endpoint, no polling,
             nothing that would itself keep the session alive. The timer
             restarts on any input, so it measures inactivity the same way the
             container does; clock drift can only make it warn EARLY, which is
             the safe direction. --%>
        <div id="sessionExpiryBar" class="draft-bar" hidden role="status" aria-live="polite"
             data-timeout="${pageContext.session.maxInactiveInterval}">
            <span class="draft-bar-text"><spring:message code="session.expiringSoon"/></span>
        </div>

        <%-- Draft recovery. Hidden until roller-draft.js finds a local
             snapshot the server does not have. type="button" is load-bearing:
             this sits inside #entry, where a bare <button> submits the form. --%>
        <div id="draftRecoveryBar" class="draft-bar" hidden role="status" aria-live="polite"
             data-restored="<spring:message code='weblogEdit.draftRecovery.restored'/>">
            <span class="draft-bar-text"
                  data-template="<spring:message code='weblogEdit.draftRecovery.message'/>"></span>
            <button type="button" class="draft-bar-restore"><spring:message code="weblogEdit.draftRecovery.restore"/></button>
            <span class="draft-bar-sep">&#183;</span>
            <button type="button" class="draft-bar-discard"><spring:message code="weblogEdit.draftRecovery.discard"/></button>
        </div>

        <%-- title: the page's one piece of layout hierarchy. Large serif,
             borderless -- emphasis elsewhere is weight, never size. --%>
        <input type="text" name="bean.title" value="${fn:escapeXml(bean.title)}" maxlength="255"
               autofocus
               class="editor-title"
               placeholder="<spring:message code="weblogEdit.title"/>"
               aria-label="<spring:message code="weblogEdit.title"/>"/>

        <%-- permalink: small mono line with a copy control --%>
        <c:if test="${actionName == 'entryEdit'}">
            <p class="editor-permalink" title="<spring:message code="weblogEdit.permaLink"/>"
               role="status" aria-live="polite">
                <c:choose>
<c:when test="${bean.published}">
                    <a id="entry_bean_permalink" href='${urls.entry(entry)}'><span id="entry_bean_permalink_text">${urls.entry(entry)}</span></a>
                </c:when>
<c:otherwise>
                    <span id="entry_bean_permalink_text">${urls.entry(entry)}</span>
                </c:otherwise>
</c:choose>
                <%-- Only offered on a published entry: a draft's permalink
                     404s, so copying it hands someone a broken link. --%>
                <c:if test="${bean.published}">
                &#183;
                <button type="button" class="editor-permalink-copy"
                        data-permalink="${urls.entry(entry)}"
                        data-label="<spring:message code='weblogEdit.copyPermalink'/>"
                        data-copied="<spring:message code='weblogEdit.copiedPermalink'/>"
                        onclick="copyPermalink(this)"><spring:message code="weblogEdit.copyPermalink"/></button>
                </c:if>
            </p>
        </c:if>

        <%-- Weblog editor --%>
        <jsp:include page="/WEB-INF/jsps/editor/EntryEditor.jsp"/>

    </div>

    <%-- ================================================================== --%>
    <%-- The publish rail --%>

    <div class="editor-rail">

        <%-- Publish: status, one visible time field, the submit buttons --%>
        <div class="editor-box">
            <p class="rail-group-label"><spring:message code="weblogEdit.publishGroup"/></p>

            <%-- Status is the one shared pill component, the same five-variant
                 vocabulary Entries.jsp and Pages.jsp render -- plus its sixth,
                 UNSAVED, which exists for exactly this caller: an entry being
                 composed has no PubStatus at all, so the rail needs no branch
                 outside the component. The Bootstrap badges this replaced were
                 four stock semantic colours (success/info/warning/danger) that
                 said "danger" about an unsaved draft and used one colour, info,
                 for two different states.

                 Request scope, not page scope: <jsp:include> runs the included
                 page in a fresh JspContext that cannot see page-scoped
                 variables. The <c:remove> is the convention every other caller
                 follows -- request scope outlives one include, and this rail
                 has no scheduled pubtime to show. --%>
            <div class="editor-statusrow">
                <c:choose>
                <c:when test="${bean.published}"><c:set var="pillStatus" value="PUBLISHED" scope="request"/></c:when>
                <c:when test="${bean.draft}"><c:set var="pillStatus" value="DRAFT" scope="request"/></c:when>
                <c:when test="${bean.pending}"><c:set var="pillStatus" value="PENDING" scope="request"/></c:when>
                <c:when test="${bean.scheduled}"><c:set var="pillStatus" value="SCHEDULED" scope="request"/></c:when>
                <c:otherwise><c:set var="pillStatus" value="UNSAVED" scope="request"/></c:otherwise>
                </c:choose>
                <c:remove var="pillWhen" scope="request"/>
                <jsp:include page="/WEB-INF/jsps/editor/StatusPill.jsp"/>
                <c:if test="${not empty entry.updateTime}">
                    <span class="editor-when" title="<spring:message code="weblogEdit.updateTime"/>"><rc:date value="${entry.updateTime}"/></span>
                </c:if>
            </div>

            <label class="editor-field-label" for="entry_bean_pubTimeLocal"><spring:message code="weblogEdit.pubTime"/></label>
            <input type="datetime-local" name="bean.pubTimeLocal" id="entry_bean_pubTimeLocal"
                   value="${bean.pubTimeLocal}" class="editor-dt"/>
            <div class="editor-tz">${actionWeblog.timeZone}</div>

            <div class="editor-btnrow">
                <c:choose>
<c:when test="${userAnAuthor}">
                    <%-- publish --%>
                    <button type="submit" class="btn btn-primary" formaction="${pageContext.request.contextPath}/roller-ui/authoring/${mainAction}!publish.rol"><spring:message code="weblogEdit.post"/></button>
                </c:when>
<c:otherwise>
                    <%-- submit for review --%>
                    <button type="submit" class="btn btn-primary" formaction="${pageContext.request.contextPath}/roller-ui/authoring/${mainAction}!publish.rol"><spring:message code="weblogEdit.submitForReview"/></button>
                </c:otherwise>
</c:choose>
                <%-- save draft --%>
                <button type="submit" class="btn btn-secondary" formaction="${pageContext.request.contextPath}/roller-ui/authoring/${mainAction}!saveDraft.rol"><spring:message code="weblogEdit.save"/></button>
            </div>

            <c:if test="${actionName == 'entryEdit'}">
                <%-- preview mode --%>
                <button type="button" name="fullPreview" class="editor-preview-link"
                        onclick="fullPreviewMode()"><spring:message code="weblogEdit.fullPreviewMode"/></button>
            </c:if>

            <%-- global admin can pin items to front page weblog --%>
            <c:if test="${isGlobalAdmin}">
                <div class="form-check editor-quiet-check">
                    <label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.pinnedToMain" value="true" ${bean.pinnedToMain ? 'checked' : ''}/> <spring:message code="weblogEdit.pinnedToMain"/></label>
                </div>
            </c:if>
        </div>

        <%-- Organize: category and tags are the visible controls. Locale
             rides along as a hidden input -- there is no locale picker in
             this rail -- so the entry's existing locale value round-trips
             on save instead of silently reverting to a default. --%>
        <div class="editor-box">
            <p class="rail-group-label"><spring:message code="weblogEdit.organizeGroup"/></p>

            <label class="editor-field-label" for="entry_bean_categoryId"><spring:message code="weblogEdit.category"/></label>
            <select name="bean.categoryId" id="entry_bean_categoryId" class="form-select">
<c:forEach items="${categories}" var="opt">
<option value="${opt.id}" ${opt.id == bean.categoryId ? 'selected' : ''}>${opt.name}</option>
</c:forEach>
</select>

            <label class="editor-field-label" for="entry_bean_tagsAsString"><spring:message code="weblogEdit.tags"/></label>
            <input type="text" name="bean.tagsAsString" value="${fn:escapeXml(bean.tagsAsString)}" id="entry_bean_tagsAsString" maxlength="255" class="form-control"/>

            <input type="hidden" name="bean.locale" value="${bean.locale}"/>
        </div>

        <%-- SEO and social sharing: the whole card survives intact behind a
             quiet drawer. Field ids/names and the picker/snippet JS below are
             a browser-test contract -- do not rename. --%>
        <div class="editor-box">
            <a class="editor-drawer collapsed" data-bs-toggle="collapse" data-bs-target="#collapseSeo" href="#">
                <spring:message code="weblogEdit.seoSettings"/>
            </a>
            <div id="collapseSeo" class="collapse">
                <div class="editor-drawer-body">

                    <%-- meta title (falls back to the entry title on the public page) --%>
                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label" for="seo_metaTitle"><spring:message code="weblogEdit.metaTitle"/></label>
                        <div class="col-sm-9">
                            <input type="text" id="seo_metaTitle" name="bean.metaTitle" value="${fn:escapeXml(bean.metaTitle)}" maxlength="255" class="form-control"/>
                        </div>
                    </div>

                    <%-- meta description (the entry's searchDescription column) --%>
                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label" for="seo_metaDescription"><spring:message code="weblogEdit.metaDescription"/></label>
                        <div class="col-sm-9">
                            <input type="text" id="seo_metaDescription" name="bean.searchDescription" value="${fn:escapeXml(bean.searchDescription)}" maxlength="255" class="form-control"/>
                        </div>
                    </div>

                    <%-- Lightweight search-snippet preview, fed by JS from the
                         title/description fields.

                         The three lines used to carry inline font-size bumps
                         (1.15em / 0.85em / 0.9em) -- size-as-emphasis, on the
                         one page whose borderless 26px title is the spec's
                         canonical example of "emphasis is weight, never size".
                         They are named classes now and sit on the 12 / 14.5 /
                         16 scale: 16px title, 12px mono URL, 14.5px
                         description. That still reads as a search result --
                         the hierarchy is intact -- without inventing a support
                         tier the scale does not have.

                         The rules themselves live in roller.css beside the
                         other .editor-* rules. --%>
                    <div class="row mb-3">
                        <span class="col-sm-3 col-form-label"><spring:message code="weblogEdit.snippetPreview"/></span>
                        <div class="col-sm-9">
                            <div id="seo_snippet_preview" class="border rounded p-2 bg-body">
                                <div id="seo_snippet_title" class="seo-snippet-title"></div>
                                <div id="seo_snippet_url" class="seo-snippet-url"><c:choose><c:when test="${actionName == 'entryEdit'}">${urls.entry(entry)}</c:when><c:otherwise>${urls.weblogAbsolute(actionWeblog)}<spring:message code="weblogEdit.snippetPreview.placeholderSlug"/></c:otherwise></c:choose></div>
                                <div id="seo_snippet_description" class="seo-snippet-description"></div>
                            </div>
                        </div>
                    </div>

                    <%-- featured image --%>
                    <div class="row mb-3" role="group" aria-labelledby="seo_featuredImage_label">
                        <span class="col-sm-3 col-form-label" id="seo_featuredImage_label"><spring:message code="weblogEdit.featuredImage"/></span>
                        <div class="col-sm-9">
                            <input type="hidden" id="seo_featuredImageId" name="bean.featuredImageId" value="${bean.featuredImageId}"/>
                            <div class="mb-2">
                                <img id="seo_featuredImage_preview" src="${featuredImageThumbnailUrl}" alt=""
                                     style="max-height:120px;${empty featuredImageThumbnailUrl ? 'display:none;' : ''}"/>
                            </div>
                            <button type="button" class="btn btn-secondary btn-sm" onclick="openImagePicker('featuredImage')"><spring:message code="weblogEdit.chooseImage"/></button>
                            <button type="button" class="btn btn-outline-danger btn-sm" id="seo_featuredImage_clear"
                                    style="${empty bean.featuredImageId ? 'display:none;' : ''}"
                                    onclick="clearPickedImage('featuredImage')"><spring:message code="weblogEdit.clearImage"/></button>
                        </div>
                    </div>

                    <%-- social share (Open Graph) image; when unset the featured image is used --%>
                    <div class="row mb-3" role="group" aria-labelledby="seo_ogImage_label">
                        <span class="col-sm-3 col-form-label" id="seo_ogImage_label"><spring:message code="weblogEdit.ogImage"/></span>
                        <div class="col-sm-9">
                            <input type="hidden" id="seo_ogImageId" name="bean.ogImageId" value="${bean.ogImageId}"/>
                            <div class="mb-2">
                                <img id="seo_ogImage_preview" src="${ogImageThumbnailUrl}" alt=""
                                     style="max-height:120px;${empty ogImageThumbnailUrl ? 'display:none;' : ''}"/>
                            </div>
                            <button type="button" class="btn btn-secondary btn-sm" onclick="openImagePicker('ogImage')"><spring:message code="weblogEdit.chooseImage"/></button>
                            <button type="button" class="btn btn-outline-danger btn-sm" id="seo_ogImage_clear"
                                    style="${empty bean.ogImageId ? 'display:none;' : ''}"
                                    onclick="clearPickedImage('ogImage')"><spring:message code="weblogEdit.clearImage"/></button>
                        </div>
                    </div>

                    <%-- canonical URL override --%>
                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label" for="seo_canonicalUrl"><spring:message code="weblogEdit.canonicalUrl"/></label>
                        <div class="col-sm-9">
                            <input type="text" id="seo_canonicalUrl" name="bean.canonicalUrl" value="${fn:escapeXml(bean.canonicalUrl)}" maxlength="255" class="form-control"/>
                        </div>
                    </div>

                    <%-- noindex --%>
                    <div class="row mb-3">
                        <div class="offset-sm-3 col-sm-9">
                            <div class="form-check">
                                <label class="form-check-label"><input type="checkbox" class="form-check-input" id="seo_noindex" name="bean.noindex" value="true" ${bean.noindex ? 'checked' : ''}/> <spring:message code="weblogEdit.noindex"/></label>
                            </div>
                        </div>
                    </div>

                    <%-- comments: closing stops new ones on this entry; the counts line
                         links to the moderation page and shows only for a saved entry
                         on a weblog that has comments on, to a member who may moderate
                         (the controller counts only for them). It counts top-level
                         comments, as the entry page's heading does. --%>
                    <div class="row mb-3">
                        <div class="offset-sm-3 col-sm-9">
                            <div class="form-check">
                                <label class="form-check-label"><input type="checkbox" class="form-check-input" id="comments_closed" name="bean.commentsClosed" value="true" ${bean.commentsClosed ? 'checked' : ''}/> <spring:message code="weblogEdit.commentsClosed"/></label>
                            </div>
                            <c:if test="${actionWeblog.commentsEnabled and not empty bean.id and not empty commentsPublished}">
                                <c:url value="/roller-ui/authoring/comments.rol" var="entryCommentsUrl">
                                    <c:param name="weblog" value="${actionWeblog.handle}"/>
                                    <c:param name="entry" value="${bean.id}"/>
                                </c:url>
                                <a class="small" id="entryCommentCounts" href="${entryCommentsUrl}"><spring:message code="weblogEdit.commentCounts" arguments="${commentsPublished},${commentsPending}"/></a>
                            </c:if>
                        </div>
                    </div>

                    <%-- structured data: the schema.org type this entry's head declares.
                         The event rows below are shown/hidden by
                         updateSeoJsonLdRows() and stay in the DOM when hidden, so
                         switching type never silently drops dates the author
                         already entered. --%>
                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label" for="seo_jsonldType"><spring:message code="weblogEdit.jsonLdType"/></label>
                        <div class="col-sm-9">
                            <select id="seo_jsonldType" name="bean.jsonLdType" class="form-select">
                                <option value="BLOG_POSTING" ${empty bean.jsonLdType or bean.jsonLdType == 'BLOG_POSTING' ? 'selected' : ''}><spring:message code="weblogEdit.jsonLdType.blogPosting"/></option>
                                <option value="TOURIST_ATTRACTION" ${bean.jsonLdType == 'TOURIST_ATTRACTION' ? 'selected' : ''}><spring:message code="weblogEdit.jsonLdType.touristAttraction"/></option>
                                <option value="TOURIST_TRIP" ${bean.jsonLdType == 'TOURIST_TRIP' ? 'selected' : ''}><spring:message code="weblogEdit.jsonLdType.touristTrip"/></option>
                                <option value="EVENT" ${bean.jsonLdType == 'EVENT' ? 'selected' : ''}><spring:message code="weblogEdit.jsonLdType.event"/></option>
                                <option value="FAQ_PAGE" ${bean.jsonLdType == 'FAQ_PAGE' ? 'selected' : ''}><spring:message code="weblogEdit.jsonLdType.faqPage"/></option>
                            </select>
                            <div class="form-text"><spring:message code="weblogEdit.jsonLdType.tip"/></div>
                        </div>
                    </div>

                    <%-- coordinates: a TouristAttraction's geo, and the default centre
                         for a bare [map]. Always shown, because the [map] use has
                         nothing to do with the structured-data type -- an ordinary
                         blog post may well want a map of where it happened. --%>
                    <div id="seo_row_geo">
                        <div class="row mb-3">
                            <label class="col-sm-3 col-form-label" for="seo_geoLatitude"><spring:message code="weblogEdit.geoLatitude"/></label>
                            <div class="col-sm-9">
                                <input type="number" step="any" min="-90" max="90" id="seo_geoLatitude" name="bean.geoLatitude" value="${bean.geoLatitude}" class="form-control"/>
                            </div>
                        </div>
                        <div class="row mb-3">
                            <label class="col-sm-3 col-form-label" for="seo_geoLongitude"><spring:message code="weblogEdit.geoLongitude"/></label>
                            <div class="col-sm-9">
                                <input type="number" step="any" min="-180" max="180" id="seo_geoLongitude" name="bean.geoLongitude" value="${bean.geoLongitude}" class="form-control"/>
                                <div class="form-text"><spring:message code="weblogEdit.geoCoordinates.tip"/></div>
                            </div>
                        </div>
                    </div>

                    <%-- event schedule and venue --%>
                    <div id="seo_row_event" style="${bean.jsonLdType == 'EVENT' ? '' : 'display:none;'}">
                        <div class="row mb-3">
                            <label class="col-sm-3 col-form-label" for="seo_eventStart"><spring:message code="weblogEdit.eventStart"/></label>
                            <div class="col-sm-9">
                                <input type="datetime-local" id="seo_eventStart" name="bean.eventStartLocal" value="${fn:escapeXml(bean.eventStartLocal)}" class="form-control"/>
                                <div class="form-text"><spring:message code="weblogEdit.eventStart.tip"/></div>
                            </div>
                        </div>
                        <div class="row mb-3">
                            <label class="col-sm-3 col-form-label" for="seo_eventEnd"><spring:message code="weblogEdit.eventEnd"/></label>
                            <div class="col-sm-9">
                                <input type="datetime-local" id="seo_eventEnd" name="bean.eventEndLocal" value="${fn:escapeXml(bean.eventEndLocal)}" class="form-control"/>
                            </div>
                        </div>
                        <div class="row mb-3">
                            <label class="col-sm-3 col-form-label" for="seo_eventLocation"><spring:message code="weblogEdit.eventLocation"/></label>
                            <div class="col-sm-9">
                                <input type="text" id="seo_eventLocation" name="bean.eventLocation" value="${fn:escapeXml(bean.eventLocation)}" maxlength="255" class="form-control"/>
                            </div>
                        </div>
                    </div>

                </div>
            </div>
        </div>

    </div>

<sec:csrfInput/>
</form>


<%-- "Send as newsletter": a manual, synchronous, cannot-double-send action.
     Shown for published entries only -- an unpublished draft has no rendered
     content to mail out. Outside the main entry form (its modal is its own
     POST with its own CSRF input), but placed in the rail's grid column so it
     reads as one more quiet box below the rail -- same shape as Publish/
     Organize/the SEO drawer (.editor-box/.rail-group-label), not a Bootstrap
     card: the rail is one continuous column, not boxes-then-cards. --%>

<c:if test="${actionName == 'entryEdit' && entry.published}">
    <div id="newsletterCard" class="editor-box editor-rail-extra">
        <p class="rail-group-label"><spring:message code="newsletter.cardTitle"/></p>
        <c:choose>
            <c:when test="${not empty entry.newsletterSentAt}">
                <%-- The tag writes to the page, so its output is captured
                     into a variable and handed over as the message's one
                     argument. Nesting it inside <spring:message> instead
                     would mean dropping the arguments attribute, and a
                     <spring:message> with no arguments attribute reads as a
                     zero-argument call site -- against a message that
                     declares one. <spring:message> does not HTML-escape
                     (defaultHtmlEscape is unset), which is what lets the
                     <time> element through intact. --%>
                <c:set var="newsletterSentAtText"><rc:date value="${entry.newsletterSentAt}"/></c:set>
                <p class="pagetip" id="newsletterSentAt">
                    <spring:message code="newsletter.sentAt" arguments="${newsletterSentAtText}"/>
                </p>
            </c:when>
            <c:when test="${empty actionWeblog.newsletterListUuid}">
                <p class="pagetip" id="newsletterNoList">
                    <spring:message code="newsletter.noList"/>
                    <c:url value="/roller-ui/authoring/weblogConfig.rol" var="newsletterWeblogConfigUrl">
                        <c:param name="weblog" value="${actionWeblog.handle}"/>
                    </c:url>
                    <a href="${newsletterWeblogConfigUrl}"><spring:message code="newsletter.noList.link"/></a>
                </p>
            </c:when>
            <c:otherwise>
                <button type="button" class="btn btn-primary" id="sendNewsletterButton"
                        onclick="showNewsletterModal()">
                    <spring:message code="newsletter.send"/>
                </button>
            </c:otherwise>
        </c:choose>
    </div>
</c:if>

<%-- entry revisions: every content-changing save leaves one. Outside the main
     entry form for the same reason the newsletter box is: restore is its own
     POST with its own CSRF token, and forms must not nest. --%>

<c:if test="${actionName == 'entryEdit' && not empty entryRevisions}">
    <div id="entryRevisionsCard" class="editor-box editor-rail-extra">
        <p class="rail-group-label"><spring:message code="weblogEdit.revisions"/></p>
        <p class="pagetip"><spring:message code="weblogEdit.revisionsTip"/></p>
        <table class="table table-sm" id="entryRevisionsTable">
            <c:forEach items="${entryRevisions}" var="revision">
                <tr>
                    <td>
                        <rc:date value="${revision.created}"/>
                    </td>
                    <td><c:out value="${revision.creator}"/></td>
                    <td>
                        <button type="button" class="btn btn-link btn-sm revision-diff-button"
                                data-revision-id="${revision.id}">
                            <spring:message code="weblogEdit.revisionCompare"/>
                        </button>
                    </td>
                    <td>
                        <form method="post" style="display:inline"
                              action="${pageContext.request.contextPath}/roller-ui/authoring/entryEdit!restoreRevision.rol">
                            <input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
                            <input type="hidden" name="bean.id" value="${entry.id}"/>
                            <input type="hidden" name="revisionId" value="${revision.id}"/>
                            <sec:csrfInput/>
                            <button type="submit" class="btn btn-outline-secondary btn-sm revision-restore-button">
                                <spring:message code="weblogEdit.revisionRestore"/>
                            </button>
                        </form>
                    </td>
                </tr>
            </c:forEach>
        </table>
    </div>
</c:if>

<c:if test="${actionName == 'entryEdit'}">
    <%-- delete: a quiet text link, not a red button. Last thing in the rail's
         column, after the newsletter and revisions boxes. id/title ride in
         data-* attributes rather than an interpolated onclick string --
         fn:escapeXml renders an apostrophe as &#039;, which the HTML parser
         decodes back to ' BEFORE the onclick attribute compiles as
         JavaScript, so an entry titled e.g. "Maiia's trip" made this button a
         permanent SyntaxError. See the delegated handler below (same
         convention as MediaFileView.jsp:493). --%>
    <button type="button" id="deleteEntryButton" class="delete-link editor-rail-extra"
            data-entry-id="${entry.id}" data-entry-title="${fn:escapeXml(entry.title)}"
            aria-label="<spring:message code='weblogEdit.deleteEntry'/>: ${fn:escapeXml(entry.title)}"><spring:message code="weblogEdit.deleteEntry"/></button>
</c:if>

</div><%-- /editor-grid --%>


<%-- ========================================================================================== --%>

<%-- newsletter confirmation modal + script (the card lives in the rail above) --%>

<c:if test="${actionName == 'entryEdit' && entry.published}">
    <div id="newsletter-confirm-modal" class="modal" tabindex="-1" role="dialog" aria-modal="true" aria-labelledby="newsletter-confirm-modal-title">
        <div class="modal-dialog">
            <div class="modal-content">
                <form action="${pageContext.request.contextPath}/roller-ui/authoring/entryEdit!sendNewsletter.rol"
                      method="post" class="guard-submit">
                    <input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
                    <input type="hidden" name="bean.id" value="${entry.id}"/>

                    <div class="modal-header">
                        <p id="newsletter-confirm-modal-title" class="modal-title"><spring:message code="newsletter.confirmTitle"/></p>
                        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="<spring:message code='generic.close'/>"></button>
                    </div>

                    <div class="modal-body">
                        <p><spring:message code="newsletter.confirmBody"/></p>
                    </div>

                    <%-- Dismiss first, action last: Bootstrap packs a
                         .modal-footer left-to-right in DOM order, so the
                         reading order IS the markup order (task B7). --%>
                    <div class="modal-footer">
                        <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">
                            <spring:message code="generic.no"/>
                        </button>
                        <button type="submit" class="btn btn-primary" id="confirmSendNewsletterButton"
                                data-busy-label="<spring:message code='newsletter.sending'/>">
                            <spring:message code="newsletter.send"/>
                        </button>
                    </div>

                    <sec:csrfInput/>
                </form>
            </div>
        </div>
    </div>

    <script>
        function showNewsletterModal() {
            bootstrap.Modal.getOrCreateInstance(document.getElementById('newsletter-confirm-modal')).show();
        }
    </script>
</c:if>


<%-- ========================================================================================== --%>

<%-- revision diff modal + script (the card lives in the rail above) --%>

<c:if test="${actionName == 'entryEdit' && not empty entryRevisions}">
    <div id="revision-diff-modal" class="modal" tabindex="-1" role="dialog" aria-modal="true" aria-labelledby="revision-diff-modal-title">
        <div class="modal-dialog modal-lg">
            <div class="modal-content">
                <div class="modal-header">
                    <p id="revision-diff-modal-title" class="modal-title"><spring:message code="weblogEdit.revisionCompare"/></p>
                    <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="<spring:message code='generic.close'/>"></button>
                </div>
                <div class="modal-body" id="revisionDiffBody"></div>
            </div>
        </div>
    </div>

    <style>
        #revisionDiffBody pre { white-space: pre-wrap; }
        #revisionDiffBody .diff-added { background-color: color-mix(in srgb, var(--good) 15%, var(--surface)); display: block; }
        #revisionDiffBody .diff-removed { background-color: color-mix(in srgb, var(--bad) 15%, var(--surface)); display: block; }
        #revisionDiffBody .diff-same { color: var(--ink-soft); display: block; }
    </style>

    <script>
        <%-- The diff is computed on the SERVER against the entry as saved, so
             it reflects what is actually stored rather than whatever is
             currently unsaved in the editor. --%>
        $(function () {
            $(".revision-diff-button").on('click', function () {
                var revisionId = this.dataset.revisionId;
                $("#revisionDiffBody").text('<spring:message code="weblogEdit.previewLoading"/>');
                bootstrap.Modal.getOrCreateInstance(document.getElementById('revision-diff-modal')).show();
                $.ajax({
                    type: 'POST',
                    url: '<c:url value="/roller-ui/authoring/entryEdit!revisionDiff.rol"/>',
                    data: {
                        id: '${entry.id}',
                        revisionId: revisionId,
                        weblog: '${actionWeblog.handle}',
                        '${_csrf.parameterName}': '${_csrf.token}'
                    },
                    success: function (html) { $("#revisionDiffBody").html(html); },
                    error: function () {
                        $("#revisionDiffBody").text('<spring:message code="weblogEdit.previewFailed"/>');
                    }
                });
            });
        });
    </script>
</c:if>


<%-- ========================================================================================== --%>

<%-- delete entry confirmation modal --%>

<div id="delete-entry-modal" class="modal delete-entry-modal" tabindex="-1" role="dialog" aria-modal="true" aria-labelledby="delete-entry-modal-title">

    <div class="modal-dialog modal-lg">

        <div class="modal-content">

            <c:set var="deleteAction">entryRemoveViaList!remove</c:set>

            <form class="form-stacked" action="${pageContext.request.contextPath}/roller-ui/authoring/${deleteAction}.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
                <input type="hidden" name="removeId" value="${removeId}" id="removeId"/>

                <%-- One title, one role. This header used to wrap an <h3>
                     AND the "are you sure" sentence inside the .modal-title
                     div, so one class did the work of a header and a body at
                     once; the sentence belongs in the body with the details
                     it introduces. --%>
                <div class="modal-header">
                    <p id="delete-entry-modal-title" class="modal-title"><spring:message code="weblogEntryRemove.removeWeblogEntry"/></p>
                </div>

                <div class="modal-body">

                    <p><spring:message code="weblogEntryRemove.areYouSure"/></p>

                    <div class="row mb-3">
                        <span class="col-sm-3 col-form-label">
                            <spring:message code="weblogEntryRemove.entryTitle"/>
                        </span>
                        <div class="col-sm-9">
                            <p class="form-control-plaintext" id="postTitleLabel"></p>
                        </div>
                    </div>

                    <div class="row mb-3">
                        <span class="col-sm-3 col-form-label">
                            <spring:message code="weblogEntryRemove.entryId"/>
                        </span>
                        <div class="col-sm-9">
                            <p class="form-control-plaintext" id="postIdLabel"></p>
                        </div>
                    </div>

                </div>

                <%-- Dismiss first, destructive last: Bootstrap packs a
                     .modal-footer left-to-right in DOM order, so the reading
                     order IS the markup order (task B7). --%>
                <div class="modal-footer">
                    <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">
                        <spring:message code="generic.no"/>
                    </button>
                    <button type="submit" class="btn btn-danger"><spring:message code="generic.yes"/></button>
                </div>

            <sec:csrfInput/>
</form>

        </div>

    </div>

</div>

<%-- ========================================================================================== --%>

<script>

    function fullPreviewMode() {
        window.open('${previewURL}', 'roller-preview');
    }


    function showDeleteModal(postId, postTitle) {
        $('#postIdLabel').html(postId);
        $('#postTitleLabel').text(postTitle);
        $('#removeId').val(postId);
        bootstrap.Modal.getOrCreateInstance(document.getElementById('delete-entry-modal')).show();
    }

    <%-- The delete button's id/title ride in data-* attributes (see the
         comment above the button); this reads them rather than trusting an
         onclick built by string concatenation. The button only renders on
         entryEdit, not entryAdd, so this script (shared by both actions)
         guards against the element being absent. --%>
    var deleteEntryButton = document.getElementById('deleteEntryButton');
    if (deleteEntryButton) {
        deleteEntryButton.addEventListener('click', function () {
            showDeleteModal(this.dataset.entryId, this.dataset.entryTitle);
        });
    }

    <%-- permalink copy control --%>

    function copyPermalink(el) {
        var done = function () {
            el.classList.add('copied');
            el.textContent = el.dataset.copied;
            setTimeout(function () {
                el.classList.remove('copied');
                el.textContent = el.dataset.label;
            }, 1500);
        };
        // navigator.clipboard is undefined on a non-HTTPS origin, which is
        // every plain-http deployment and every dev server -- the control was
        // silently dead there (a TypeError in the console, nothing on screen).
        // The fallback selects the text so Ctrl-C still works, and the
        // feedback is TEXT inside the existing role="status" region rather
        // than colour alone.
        if (navigator.clipboard) {
            navigator.clipboard.writeText(el.dataset.permalink).then(done, selectPermalink);
        } else {
            selectPermalink();
        }
    }

    function selectPermalink() {
        var link = document.getElementById('entry_bean_permalink_text');
        if (!link) {
            return;
        }
        var range = document.createRange();
        range.selectNodeContents(link);
        var selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
    }

    <%-- SEO panel: search-snippet preview fed from the title/description fields --%>

    function updateSeoSnippet() {
        var title = $('#seo_metaTitle').val() || $("input[name='bean.title']").val() || '';
        var description = $('#seo_metaDescription').val() || '';
        $('#seo_snippet_title').text(title);
        $('#seo_snippet_description').text(description);
    }

    <%-- SEO panel: the event rows appear only for the Event type. They keep
         their inputs (and their values) in the form while hidden, so a type
         switch is reversible and never wipes what the author typed. The
         coordinate rows are not conditional -- they also centre a bare [map],
         which any entry may use. --%>

    function updateSeoJsonLdRows() {
        $('#seo_row_event').toggle($('#seo_jsonldType').val() === 'EVENT');
    }

    $(document).ready(function () {
        updateSeoSnippet();
        $("#seo_metaTitle, #seo_metaDescription, input[name='bean.title']").on('input', updateSeoSnippet);
        updateSeoJsonLdRows();
        $('#seo_jsonldType').on('change', updateSeoJsonLdRows);
    });

    <%-- The featured/social image pickers themselves (openImagePicker /
         onImagePicked / clearPickedImage) live in EditorScript.jsp: they are
         generic over the seo_<target>* id convention, and the page editor's
         own SEO drawer uses the same three. --%>

</script>
