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

<p class="pagetip">
    <spring:message code="mediaFileEdit.pagetip"/>
</p>

<script src="<c:url value='/theme/scripts/roller-guard-submit.js'/>"></script>

<form id="entry" class="form-stacked" action="${pageContext.request.contextPath}/roller-ui/authoring/mediaFileEdit!save.rol" method="POST" enctype="multipart/form-data">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <input type="hidden" name="mediaFileId" value="${fn:escapeXml(mediaFileId)}" id="mediaFileId"/>
    <input type="hidden" name="bean.permalink" value="${fn:escapeXml(bean.permalink)}"/>

    <c:if test="${bean.isImage}">
        <div class="row mb-3">
            <span class="col-form-label col-sm-3"><spring:message code="mediaFileEdit.thumbnail"/></span>
            <div class="col-sm-9">
                <%-- The image is the link's only content, so its alt is the
                     link's name: what the link does, not what the image is. --%>
                <a href='${fn:escapeXml(bean.permalink)}' target="_blank">
                    <img alt="<spring:message code='mediaFileEdit.clickToView'/>" src='${fn:escapeXml(bean.thumbnailURL)}'
                         title='<spring:message code="mediaFileEdit.clickToView"/>'/>
                </a>
            </div>
        </div>

        <%-- ============================================================== --%>
        <%-- Focal point: click-to-set marker, saved with the main form     --%>

        <div class="row mb-3">
            <span class="col-form-label col-sm-3"><spring:message code="mediaFileEdit.focalPoint"/></span>
            <div class="col-sm-9">
                <%-- A 480px rendition: the box is at most 240px, and the
                     original can be many megabytes. The picker takes focus so
                     the arrow keys can move the point (script below). --%>
                <div id="focalPicker" tabindex="0" aria-describedby="focalTip focalKeys"
                     aria-label="<spring:message code='mediaFileEdit.focalPoint'/>"
                     style="position:relative; display:inline-block; cursor:crosshair; line-height:0">
                    <img id="focalImage" src='${fn:escapeXml(bean.permalink)}?w=480' alt=""
                         style="max-width:240px; max-height:240px; display:block"/>
                    <span id="focalMarker"
                          style="position:absolute; width:14px; height:14px; margin:-7px 0 0 -7px;
                                 border:2px solid var(--surface); border-radius:50%;
                                 background:color-mix(in srgb, var(--bad) 85%, transparent);
                                 box-shadow:0 0 3px var(--ink);
                                 pointer-events:none; display:none"></span>
                </div>
                <div id="focalTip" class="form-text"><spring:message code="mediaFileEdit.focalPoint.tip"/></div>
                <div id="focalKeys" class="form-text"><spring:message code="mediaFileEdit.focalPoint.keys"/></div>
                <button type="button" id="clearFocalButton" class="btn btn-sm btn-outline-secondary mt-1">
                    <spring:message code="mediaFileEdit.focalPoint.clear"/>
                </button>
                <input type="hidden" name="bean.focalX" id="focalX" value="${bean.focalX}"/>
                <input type="hidden" name="bean.focalY" id="focalY" value="${bean.focalY}"/>
            </div>
        </div>
    </c:if>

    <%-- ================================================================== --%>
    <%-- Title, category, dates and other metadata --%>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="mfedit_bean_name"><spring:message code="generic.name"/></label>
        <div class="col-sm-9">
            <input id="mfedit_bean_name" type="text" name="bean.name" value="${fn:escapeXml(bean.name)}" size="35" maxlength="255" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <span class="col-form-label col-sm-3"><spring:message code="mediaFileEdit.fileInfo"/></span>

        <div class="col-sm-9">

            <spring:message code="mediaFileEdit.fileTypeSize" arguments="${fn:escapeXml(bean.contentType)},${bean.length}"/>

            <c:if test="${bean.isImage}">
                <spring:message code="mediaFileEdit.fileDimensions" arguments="${bean.width},${bean.height}"/>
            </c:if>

        </div>
    </div>

    <div class="row mb-3">
        <label class="col-form-label col-sm-3" for="clip_text"><spring:message code="mediaFileEdit.url"/></label>

        <div class="col-sm-9">

            <input type="text" id="clip_text" size="57"
                   value='${fn:escapeXml(bean.permalink)}' readonly />

            <button class="btn btn-secondary btn-sm clipbutton" data-clipboard-target="#clip_text" type="button"
                    aria-label="<spring:message code='generic.copyToClipboard'/>"><spring:message code="generic.copy"/></button>

        </div>
    </div>

    <%-- The shortcode is the canonical way to put this file in an entry --
         it picks up alt text and the rendition ladder, where a hand-pasted
         URL does not -- and until now the string was only ever visible
         inside the editor's own picker. --%>
    <div class="row mb-3">
        <label class="col-form-label col-sm-3" for="clip_shortcode"><spring:message code="mediaFileEdit.shortcode"/></label>

        <div class="col-sm-9">

            <input type="text" id="clip_shortcode" size="57"
                   value='[image id="${fn:escapeXml(mediaFileId)}"]' readonly />

            <button class="btn btn-secondary btn-sm clipbutton" data-clipboard-target="#clip_shortcode" type="button"
                    aria-label="<spring:message code='generic.copyToClipboard'/>"><spring:message code="generic.copy"/></button>

            <div class="form-text"><spring:message code="mediaFileEdit.shortcode.tip"/></div>

        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="mfedit_bean_description"><spring:message code="generic.description"/></label>
        <div class="col-sm-9">
            <textarea id="mfedit_bean_description" name="bean.description" rows="2" cols="50" class="form-control">${fn:escapeXml(bean.description)}</textarea>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="mfedit_bean_altText"><spring:message code="mediaFileEdit.altText"/></label>
        <div class="col-sm-9">
            <input id="mfedit_bean_altText" type="text" name="bean.altText" value="${fn:escapeXml(bean.altText)}"
                   maxlength="255" class="form-control"/>
            <div class="form-text"><spring:message code="mediaFileEdit.altText.tip"/></div>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="mfedit_bean_tagsAsString"><spring:message code="mediaFileEdit.tags"/></label>
        <div class="col-sm-9">
            <input id="mfedit_bean_tagsAsString" type="text" name="bean.tagsAsString" value="${fn:escapeXml(bean.tagsAsString)}" size="30" maxlength="255" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="mfedit_bean_copyrightText"><spring:message code="mediaFileEdit.copyright"/></label>
        <div class="col-sm-9">
            <input id="mfedit_bean_copyrightText" type="text" name="bean.copyrightText" value="${fn:escapeXml(bean.copyrightText)}" size="30" maxlength="1023" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="mfedit_bean_directoryId"><spring:message code="mediaFileEdit.directory"/></label>
        <div class="col-sm-9">
            <select id="mfedit_bean_directoryId" name="bean.directoryId" class="form-select">
                <c:forEach items="${allDirectories}" var="opt">
                    <option value="${opt.id}" ${opt.id == bean.directoryId ? 'selected' : ''}>${fn:escapeXml(opt.name)}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <!-- original path from base URL of ctx/resources/ -->
    <c:if test="${rc:getBooleanProp('mediafile.originalPathEdit.enabled')}">
        <div id="originalPathdiv" class="miscControl">
            <input type="text" name="bean.originalPath" value="${fn:escapeXml(bean.originalPath)}" id="originalPath" size="30" maxlength="100" class="form-control"/>
        </div>
    </c:if>


    <input type="submit" class="btn btn-primary"
           value="<spring:message code="generic.save"/>" name="saveButton"/>
    <button type="button" class="btn btn-secondary"
            onClick="window.parent.onEditCancelled();"><spring:message code="generic.cancel"/></button>

<sec:csrfInput/>
</form>

<%-- ================================================================== --%>
<%-- Crop: Cropper.js v2 custom elements over the original image;       --%>
<%-- destructive server-side re-encode behind a confirm dialog          --%>

<c:if test="${bean.croppable}">
    <hr/>
    <%-- id kept: MediaCropIT identifies the crop section by #cropSectionTitle. --%>
    <h2 id="cropSectionTitle" class="section-head"><spring:message code="mediaFileEdit.crop.title"/></h2>
    <p class="pagetip"><spring:message code="mediaFileEdit.crop.tip"/></p>

    <cropper-canvas id="cropCanvas" background style="width:100%; height:360px">
        <cropper-image id="cropImage" src='${fn:escapeXml(bean.permalink)}' alt=""></cropper-image>
        <cropper-shade hidden></cropper-shade>
        <cropper-handle action="select" plain></cropper-handle>
        <cropper-selection id="cropSelection" initial-coverage="0.9" movable resizable>
            <cropper-grid role="presentation" covered></cropper-grid>
            <cropper-crosshair centered></cropper-crosshair>
            <cropper-handle action="move" theme-color="rgba(255, 255, 255, 0.35)"></cropper-handle>
            <cropper-handle action="n-resize"></cropper-handle>
            <cropper-handle action="e-resize"></cropper-handle>
            <cropper-handle action="s-resize"></cropper-handle>
            <cropper-handle action="w-resize"></cropper-handle>
            <cropper-handle action="ne-resize"></cropper-handle>
            <cropper-handle action="nw-resize"></cropper-handle>
            <cropper-handle action="se-resize"></cropper-handle>
            <cropper-handle action="sw-resize"></cropper-handle>
        </cropper-selection>
    </cropper-canvas>

    <form id="cropForm" class="mt-2 guard-submit" action="${pageContext.request.contextPath}/roller-ui/authoring/mediaFileEdit!crop.rol" method="POST">
        <input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
        <input type="hidden" name="mediaFileId" value="${fn:escapeXml(mediaFileId)}"/>
        <input type="hidden" name="cropX" id="cropX" value="0"/>
        <input type="hidden" name="cropY" id="cropY" value="0"/>
        <input type="hidden" name="cropWidth" id="cropWidth" value="0"/>
        <input type="hidden" name="cropHeight" id="cropHeight" value="0"/>
        <button type="submit" id="cropButton" class="btn btn-danger"
                data-confirm="<spring:message code='mediaFileEdit.crop.confirm'/>"
                data-busy-label="<spring:message code='mediaFileEdit.crop.applying'/>">
            <spring:message code="mediaFileEdit.crop.apply"/>
        </button>
        <sec:csrfInput/>
    </form>

    <c:url var="cropperJsURL" value="/webjars/cropperjs/2.2.0/dist/cropper.min.js"/>
    <script src="${cropperJsURL}"></script>
    <script>
        (function () {
            // The stored dimensions already describe the DISPLAYED
            // (orientation-corrected) image, which is also what the browser
            // renders, so selection math and server-side crop math agree.
            var naturalWidth = Number('${bean.width}');
            var naturalHeight = Number('${bean.height}');

            // This page is loaded into an iframe inside a Bootstrap modal, and
            // MediaFileView.jsp sets that iframe's src BEFORE the modal has
            // finished fading in. Whenever this document wins that race the
            // cropper custom elements upgrade while the iframe still has no
            // layout box at all: <cropper-selection> sizes its
            // initial-coverage selection from <cropper-canvas>.offsetWidth,
            // which is 0 then, and Cropper v2 never recomputes it (it observes
            // no resize; $initSelection runs only from connectedCallback). The
            // user is then shown a cropper with an invisible 0x0 selection and
            // a Crop button that silently does nothing. Seed the selection
            // ourselves the moment the canvas actually gains a size - and only
            // while it is still empty, so we never fight a selection the user
            // has already drawn.
            function seedSelectionOnceLaidOut() {
                var canvas = document.getElementById('cropCanvas');
                var selection = document.getElementById('cropSelection');
                if (!canvas || !selection || typeof selection.$change !== 'function') {
                    return;
                }
                if (selection.width > 0 && selection.height > 0) {
                    return;
                }
                var coverage = selection.initialCoverage;
                if (!(coverage > 0) || coverage > 1) {
                    coverage = 0.9;
                }
                var canvasWidth = canvas.offsetWidth;
                var canvasHeight = canvas.offsetHeight;
                var width = canvasWidth * coverage;
                var height = canvasHeight * coverage;
                if (!(width > 0) || !(height > 0)) {
                    return;
                }
                selection.$change((canvasWidth - width) / 2, (canvasHeight - height) / 2,
                        width, height);
            }

            if (window.ResizeObserver) {
                new ResizeObserver(seedSelectionOnceLaidOut)
                        .observe(document.getElementById('cropCanvas'));
            } else {
                seedSelectionOnceLaidOut();
            }

            function cropRectInNaturalPixels() {
                var canvas = document.getElementById('cropCanvas');
                var image = document.getElementById('cropImage');
                var selection = document.getElementById('cropSelection');
                var canvasRect = canvas.getBoundingClientRect();
                var imageRect = image.getBoundingClientRect();
                if (!(imageRect.width > 0) || !(imageRect.height > 0)) {
                    return null;
                }
                if (!(naturalWidth > 0) || !(naturalHeight > 0)) {
                    // pre-ladder upload without stored dimensions: fall back
                    // to the decoded image itself
                    var img = image.$image;
                    naturalWidth = img ? img.naturalWidth : 0;
                    naturalHeight = img ? img.naturalHeight : 0;
                    if (!(naturalWidth > 0)) {
                        return null;
                    }
                }
                var scaleX = naturalWidth / imageRect.width;
                var scaleY = naturalHeight / imageRect.height;
                return {
                    x: Math.round((selection.x + canvasRect.left - imageRect.left) * scaleX),
                    y: Math.round((selection.y + canvasRect.top - imageRect.top) * scaleY),
                    width: Math.round(selection.width * scaleX),
                    height: Math.round(selection.height * scaleY)
                };
            }

            // Confirmation is handled upstream now, by data-confirm on
            // #cropButton (see roller.js): a click that fails to confirm never
            // reaches this submit handler at all. This still guards against
            // submitting with no valid selection drawn.
            document.getElementById('cropForm').addEventListener('submit', function (event) {
                var rect = cropRectInNaturalPixels();
                if (rect === null || !(rect.width > 0) || !(rect.height > 0)) {
                    event.preventDefault();
                    return;
                }
                document.getElementById('cropX').value = rect.x;
                document.getElementById('cropY').value = rect.y;
                document.getElementById('cropWidth').value = rect.width;
                document.getElementById('cropHeight').value = rect.height;
            });
        })();
    </script>
</c:if>

<script>
    $(document).ready(function () {
        // Copying used to give no feedback at all -- the entry editor's
        // permalink control already toggles .copied, so this matches it.
        var clipboard = new ClipboardJS('.clipbutton');
        clipboard.on('success', function (e) {
            e.trigger.classList.add('copied');
            setTimeout(function () { e.trigger.classList.remove('copied'); }, 1500);
            e.clearSelection();
        });

        // ------------------------------------------------- focal point
        var picker = document.getElementById('focalPicker');
        if (picker) {
            var marker = document.getElementById('focalMarker');
            var focalX = document.getElementById('focalX');
            var focalY = document.getElementById('focalY');

            function showMarker(fx, fy) {
                marker.style.left = (fx * 100) + '%';
                marker.style.top = (fy * 100) + '%';
                marker.style.display = 'block';
            }

            if (focalX.value !== '' && focalY.value !== '') {
                showMarker(parseFloat(focalX.value), parseFloat(focalY.value));
            }

            // The keyboard path: arrows nudge the point by 5% (1% with
            // Shift), starting from the centre when none is set yet.
            var STEPS = {ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1]};
            picker.addEventListener('keydown', function (event) {
                var step = STEPS[event.key];
                if (!step) {
                    return;
                }
                event.preventDefault();
                var size = event.shiftKey ? 0.01 : 0.05;
                var fx = focalX.value === '' ? 0.5 : parseFloat(focalX.value);
                var fy = focalY.value === '' ? 0.5 : parseFloat(focalY.value);
                fx = Math.min(1, Math.max(0, fx + step[0] * size));
                fy = Math.min(1, Math.max(0, fy + step[1] * size));
                focalX.value = fx.toFixed(3);
                focalY.value = fy.toFixed(3);
                showMarker(fx, fy);
            });

            picker.addEventListener('click', function (event) {
                var image = document.getElementById('focalImage');
                var rect = image.getBoundingClientRect();
                if (!(rect.width > 0) || !(rect.height > 0)) {
                    return;
                }
                var fx = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
                var fy = Math.min(1, Math.max(0, (event.clientY - rect.top) / rect.height));
                focalX.value = fx.toFixed(3);
                focalY.value = fy.toFixed(3);
                showMarker(fx, fy);
            });

            document.getElementById('clearFocalButton').addEventListener('click', function () {
                focalX.value = '';
                focalY.value = '';
                marker.style.display = 'none';
            });
        }
    });
</script>
