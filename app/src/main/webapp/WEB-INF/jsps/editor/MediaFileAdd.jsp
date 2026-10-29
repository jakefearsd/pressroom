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


<p class="subtitle"> <spring:message code="mediaFileAdd.title"/> </p>
<p class="pagetip"> <spring:message code="mediaFileAdd.pageTip"/> </p>

<script src="<c:url value='/theme/scripts/roller-guard-submit.js'/>"></script>

<form id="entry" class="form-stacked guard-submit" action="${pageContext.request.contextPath}/pressroom-ui/authoring/mediaFileAdd!save.rol" method="POST" enctype="multipart/form-data">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <input type="hidden" name="directoryName" value="${fn:escapeXml(directoryName)}"/>

    <%-- The folder select stays ABOVE the drop zone. It is not optional the
         way the three text fields below are -- it decides where the batch
         lands, and there is no undo for dropping thirty files into the wrong
         directory. --%>
    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="mfadd_bean_directoryId"><spring:message code="mediaFileAdd.directory"/></label>
        <div class="col-sm-9">
            <select id="mfadd_bean_directoryId" name="bean.directoryId" class="form-select">
                <c:forEach items="${allDirectories}" var="opt">
                    <option value="${opt.id}" ${opt.id == bean.directoryId ? 'selected' : ''}>${fn:escapeXml(opt.name)}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <%-- The files come next, because they are what the author opened this page
         to hand over. Description, copyright and tags used to sit above this --
         three optional fields in the way of the one required act, and applied
         identically to every file in a batch, which is rarely what anyone
         means past the first file. --%>
    <div class="card">
        <div class="card-header">
            <h2 class="section-head">
                <spring:message code="mediaFileAdd.fileLocation"/>
            </h2>
        </div>
        <div class="card-body">
            <div id="mediaDropZone" class="media-dropzone"
                 data-summary-one="<spring:message code='mediaFileAdd.chosenSummary.one'/>"
                 data-summary-many="<spring:message code='mediaFileAdd.chosenSummary.many'/>">
                <label for="uploadedFiles" class="visually-hidden"><spring:message code="mediaFileAdd.fileLocation"/></label>
                <input type="file" name="uploadedFiles" id="uploadedFiles" multiple/>
                <p class="media-dropzone-hint"><spring:message code="mediaFileAdd.dropHint"/></p>
                <ul id="mediaChosenFiles" class="media-chosen"></ul>
            </div>
        </div>
    </div>

    <%-- A native <details>, not a Bootstrap collapse: no JS, no aria wiring to
         get wrong, and the browser's own disclosure state. Whatever is typed
         here is applied to every file in the batch. Starts open on a
         re-render that carries typed-but-unsubmitted text, so a validation
         refusal never hides what the author already typed. --%>
    <details class="editor-details" ${not empty bean.description or not empty bean.tagsAsString or not empty bean.copyrightText ? 'open' : ''}>
        <summary class="rail-group-label"><spring:message code="generic.details"/></summary>

        <div class="row mb-3">
            <label class="col-sm-3 col-form-label" for="mfadd_bean_description"><spring:message code="generic.description"/></label>
            <div class="col-sm-9">
                <textarea id="mfadd_bean_description" name="bean.description" rows="3" maxlength="255" class="form-control">${fn:escapeXml(bean.description)}</textarea>
            </div>
        </div>

        <div class="row mb-3">
            <label class="col-sm-3 col-form-label" for="mfadd_bean_copyrightText"><spring:message code="mediaFileAdd.copyright"/></label>
            <div class="col-sm-9">
                <textarea id="mfadd_bean_copyrightText" name="bean.copyrightText" rows="3" maxlength="1023" class="form-control">${fn:escapeXml(bean.copyrightText)}</textarea>
            </div>
        </div>

        <div class="row mb-3">
            <label class="col-sm-3 col-form-label" for="mfadd_bean_tagsAsString"><spring:message code="mediaFileAdd.tags"/></label>
            <div class="col-sm-9">
                <input id="mfadd_bean_tagsAsString" type="text" name="bean.tagsAsString" value="${fn:escapeXml(bean.tagsAsString)}" maxlength="255" class="form-control"/>
            </div>
        </div>

    </details>

    <button type="submit" id="uploadButton" class="btn btn-primary"
            data-busy-label="<spring:message code='mediaFileAdd.uploading'/>" formaction="${pageContext.request.contextPath}/pressroom-ui/authoring/mediaFileAdd!save.rol"><spring:message code="mediaFileAdd.upload"/></button>
    <c:url var="mediaFileCancelURL" value="/pressroom-ui/authoring/mediaFileView.rol">
        <c:param name="weblog" value="${actionWeblog.handle}"/>
        <c:param name="directoryId" value="${bean.directoryId}"/>
    </c:url>
    <a class="btn btn-secondary" href="${mediaFileCancelURL}"><spring:message code="generic.cancel"/></a>

<sec:csrfInput/>
</form>


<%-- ================================================================== --%>

<script>

    (function () {
        var dropZone = document.getElementById("mediaDropZone");
        var fileInput = document.getElementById("uploadedFiles");
        var chosenList = document.getElementById("mediaChosenFiles");
        var uploadButton = document.getElementById("uploadButton");

        function formatSize(bytes) {
            var units = ["B", "KB", "MB", "GB"];
            var unit = 0;
            var size = bytes;
            while (size >= 1024 && unit < units.length - 1) {
                size = size / 1024;
                unit++;
            }
            return (unit === 0 ? size : size.toFixed(1)) + " " + units[unit];
        }

        function renderChosenFiles() {
            var files = fileInput.files;
            chosenList.innerHTML = "";

            var total = 0;
            for (var i = 0; i < files.length; i++) {
                var item = document.createElement("li");
                item.textContent = files[i].name + " (" + formatSize(files[i].size) + ")";
                chosenList.appendChild(item);
                total += files[i].size;
            }

            if (files.length > 0) {
                var summary = document.createElement("li");
                summary.className = "media-chosen-total";
                var template = files.length === 1
                        ? dropZone.dataset.summaryOne
                        : dropZone.dataset.summaryMany;
                summary.textContent = template
                        .replace("#COUNT#", files.length)
                        .replace("#SIZE#", formatSize(total));
                chosenList.appendChild(summary);
            }

            uploadButton.disabled = files.length === 0;
        }

        fileInput.addEventListener("change", renderChosenFiles);

        dropZone.addEventListener("dragover", function (event) {
            // Without this the browser's default is to navigate to the
            // dropped file, losing everything typed into the form so far.
            event.preventDefault();
            dropZone.classList.add("media-dropzone-active");
        });

        // Counted rather than a bare dragleave: HTML5 fires dragleave every
        // time the pointer crosses INTO a child box during a drag (the input,
        // the hint, a chosen-file row), so the naive version strips the
        // highlight repeatedly while the pointer is still inside the zone and
        // the whole thing flickers.
        var dragDepth = 0;

        dropZone.addEventListener("dragenter", function () {
            dragDepth++;
            dropZone.classList.add("media-dropzone-active");
        });

        dropZone.addEventListener("dragleave", function () {
            dragDepth--;
            if (dragDepth <= 0) {
                dragDepth = 0;
                dropZone.classList.remove("media-dropzone-active");
            }
        });

        dropZone.addEventListener("drop", function (event) {
            // Same reason as dragover: without preventDefault the browser
            // navigates away to the dropped file.
            event.preventDefault();
            dragDepth = 0;
            dropZone.classList.remove("media-dropzone-active");
            fileInput.files = event.dataTransfer.files;
            renderChosenFiles();
        });

        // The hint says "or click to choose", so the whole zone has to open
        // the picker -- otherwise only the native control does and the copy
        // is describing an affordance that is not there. Clicks on the input
        // itself are left alone; forwarding those would reopen the dialog.
        dropZone.addEventListener("click", function (event) {
            if (event.target !== fileInput) {
                fileInput.click();
            }
        });

        uploadButton.disabled = true;
    })();

</script>