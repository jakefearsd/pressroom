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

<%-- ********************************************************************* --%>

<%-- Subtitle and folder path --%>

<%-- OGNL would treat a non-empty list as true; JSTL EL throws trying to
     coerce a List to Boolean, which 500s the whole chooser overlay. --%>
<c:if test="${not empty childFiles or not empty allDirectories}">

    <%-- A GET to the overlay path the controller maps: this only reloads the
         chooser on another folder. It used to POST to a path nothing serves.
         imagesOnly rides along, or the featured-image picker would start
         offering PDFs after one folder change. --%>
    <form id="mediaFileChooserForm" name="mediaFileChooserForm" action="${pageContext.request.contextPath}/roller-ui/authoring/overlay/mediaFileImageChooser.rol" method="get" class="form-vertical">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
        <c:if test="${imagesOnly}">
            <input type="hidden" name="imagesOnly" value="true"/>
        </c:if>

        <p class="pagetip"><spring:message code="mediaFileImageChooser.pageTip"/></p>

        <%-- ***************************************************************** --%>
        <%-- Maybe show media directory selector --%>

        <c:if test="${not empty allDirectories}">
            <label for="chooserDirectoryId"><spring:message code="mediaFileAdd.directory"/></label>
            <select id="chooserDirectoryId" name="directoryId" class="form-select" onchange="onView()">
<option value=""><spring:message code="mediaFileImageChooser.allDirectories"/></option>
<c:forEach items="${allDirectories}" var="opt">
<option value="${opt.id}" ${opt.id == directoryId ? 'selected' : ''}>${fn:escapeXml(opt.name)}</option>
</c:forEach>
</select>
        </c:if>

        <%-- ***************************************************************** --%>
        <%-- Media files grid --%>

        <div id="imageGrid" class="card">
            <div class="card-body">

                <%-- Empty state sits OUTSIDE the <ul>: a <p> nested directly
                     in a list is not valid HTML and the browser hoists it out
                     of the list anyway. No action button either -- this page
                     is the picker overlay opened from inside the entry
                     editor, so an "upload" control here would have to navigate
                     the overlay away from the flow that opened it. The
                     sentence names the Media tab instead of shipping a
                     control that cannot work where it is. --%>
                <c:if test="${fn:length(childFiles) == 0}">
                    <div class="empty-state">
                        <p class="empty-state-title"><spring:message code="mediaFileView.noFiles"/></p>
                        <p class="empty-state-body"><spring:message code="empty.mediaChooser.body"/></p>
                    </div>
                </c:if>

                <ul>

                    <c:if test="${fn:length(childFiles) > 0}">

                        <c:forEach items="${childFiles}" var="mediaFile">

                            <c:set var="mediaFileURL" value="${urls.media(mediaFile)}"/>
                            <c:url var="mediaFileThumbnailURL" value="${urls.mediaThumbnail(mediaFile)}"/>

                            <li class="align-images"
                                onmouseover="highlight(this, true)" onmouseout="highlight(this, false)">

                                <%-- name/url/id ride in data-* attributes, not
                                     an onclick built by string concatenation --
                                     this is the only route to insert an image
                                     into an entry, and fn:escapeXml renders an
                                     apostrophe as &#039;, which the HTML parser
                                     decodes back to ' BEFORE this onclick
                                     compiled as JavaScript -- so a file named
                                     e.g. "Maiia's portrait.jpg" made the tile
                                     permanently unclickable. See the delegated
                                     click binding below (same convention as
                                     MediaFileView.jsp's tiles). --%>
                                <div class="mediaObject" role="button" tabindex="0"
                                     aria-label="${fn:escapeXml(mediaFile.name)}"
                                     data-media-file-name="${fn:escapeXml(mediaFile.name)}"
                                     data-media-file-url="${fn:escapeXml(mediaFileURL)}"
                                     data-media-file-is-image="${mediaFile.isImageFile()}"
                                     data-media-file-id="${mediaFile.id}">

                                    <c:choose>
<c:when test="${mediaFile.imageFile}">
                                        <img src='${mediaFileThumbnailURL}'
                                             width='${mediaFile.thumbnailWidth}'
                                             height='${mediaFile.thumbnailHeight}'
                                             alt='${fn:escapeXml(mediaFile.name)}'/>
                                    </c:when>
<c:otherwise>
                                        <span class="bi bi-file-earmark fs-1" aria-hidden="true"></span>
                                    </c:otherwise>
                                    </c:choose>
                                </div>

                                <div class="mediaObjectInfo">
                                    ${fn:escapeXml(mediaFile.name)}
                                </div>

                            </li>

                        </c:forEach>
                    </c:if>

                </ul>
            </div>
        </div>

        <div style="clear:left;"></div>

</form>

</c:if>


<script>

    function onSelectMediaFile(name, url, isImage, id) {
        window.parent.onSelectMediaFile(name, url, isImage, id);
    }

    <%-- Delegated on the grid: a tile's name/url/isImage/id ride in data-*
         attributes (see the comment above the loop), never in an inline
         onclick string. isImage stays a string here ("true"/"false") because
         window.parent.onSelectMediaFile (EntryEditor.jsp/PageEdit.jsp)
         already compares it with === "true", the same way the old
         onclick-built string argument was compared. --%>
    $(document).on('click', '.mediaObject', function () {
        onSelectMediaFile(this.dataset.mediaFileName, this.dataset.mediaFileUrl,
                this.dataset.mediaFileIsImage, this.dataset.mediaFileId);
    });

    <%-- A tile is role="button" with tabindex="0", so a keyboard user can
         reach it; Enter and Space activate it the way they activate a real
         button, and focus shows the same highlight as hover. --%>
    $(document).on('keydown', '.mediaObject', function (event) {
        if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            this.click();
        }
    });

    $(document).on('focusin focusout', '.mediaObject', function (event) {
        highlight(this.parentNode, event.type === 'focusin');
    });

    function highlight(el, flag) {
        if (flag) {
            $(el).addClass("highlight");
        } else {
            $(el).removeClass("highlight");
        }
    }

    function onView() {
        document.mediaFileChooserForm.submit();
    }

</script>
