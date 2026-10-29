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


<p class="subtitle"><spring:message code="mediaFileSuccess.subtitle"/></p>
<p class="pagetip"><spring:message code="mediaFileSuccess.pageTip"/></p>

<form id="entry" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <%-- Starts empty and is filled by setEnclosure(); it must not read off the
         model "bean" here, which is a MediaFileBean with no enclosureURL
         property -- strict EL turns that into a 500 for the whole page. The
         form posts to entryAddWithMediaFile.rol, whose "bean" is an EntryBean. --%>
    <input type="hidden" name="bean.enclosureURL" value="" id="enclosureURL"/>

    <c:if test="${fn:length(newImages) > 0}">
        <h2 class="section-head"><spring:message code="mediaFileSuccess.selectImagesTitle"/></h2>
        <p><spring:message code="mediaFileSuccess.selectImages"/></p>

        <%-- select images via checkboxes --%>

        <c:forEach items="${newImages}" var="newImage">

            <div class="card">
                <div class="card-body">

                    <div class="row">

                        <div class="col-md-1">
                            <input type="checkbox" name="selectedImages" value="${newImage.id}"
                                   aria-label="${fn:escapeXml(newImage.name)}"/>
                        </div>

                        <div class="col-md-2">
                            <img class="mediaFileImage"
                                 src='${urls.mediaThumbnail(newImage)}' alt=""/>
                        </div>

                        <div class="col-md-9">
                            <p>
                                <b><spring:message code="mediaFileSuccess.name"/></b>
                                ${fn:escapeXml(newImage.name)}
                            </p>

                            <p>
                                <b><spring:message code="mediaFileSuccess.type"/></b>
                                ${fn:escapeXml(newImage.contentType)}
                            </p>

                            <p>
                                <b><spring:message code="mediaFileSuccess.size"/></b>
                                ${newImage.length} <spring:message code="mediaFileSuccess.bytes"/>,
                                ${newImage.width} x
                                ${newImage.height} <spring:message code="mediaFileSuccess.pixels"/>
                            </p>

                            <p>
                                <b><spring:message code="mediaFileSuccess.link"/></b>
                            </p>
                            <%-- Readonly input + clipbutton, the idiom
                                 MediaFileEdit uses: the URL was plain text,
                                 so the one thing an author wants off this
                                 page had to be selected by hand. --%>
                            <p>
                                <input type="text" class="form-control-plaintext d-inline-block w-auto"
                                       id="clip_image_${newImage.id}" size="57"
                                       value='${urls.media(newImage)}' readonly
                                       aria-label="<spring:message code='mediaFileSuccess.link'/>"/>
                                <button class="btn btn-secondary btn-sm clipbutton" type="button"
                                        data-clipboard-target="#clip_image_${newImage.id}"
                                        aria-label="<spring:message code='generic.copyToClipboard'/>"><spring:message code="generic.copy"/></button>
                            </p>
                        </div>

                    </div>

                </div>
            </div>

        </c:forEach>

    </c:if>

    <c:if test="${fn:length(newFiles) > 0}">

        <%-- select enclosure file via radio boxes --%>

        <h2 class="section-head"><spring:message code="mediaFileSuccess.selectEnclosureTitle"/></h2>
        <p><spring:message code="mediaFileSuccess.selectEnclosure"/></p>

        <c:forEach items="${newFiles}" var="newFile">
            <div class="card">
                <div class="card-body">

                    <div class="row">

                        <div class="col-md-1">
                            <input type="radio" name="enclosure"
                                   aria-label="${fn:escapeXml(newFile.name)}"
                                   onchange="setEnclosure('${urls.media(newFile)}')"/>
                        </div>

                        <div class="col-md-11">
                            <p>
                                <b><spring:message code="mediaFileSuccess.name"/></b>
                                ${fn:escapeXml(newFile.name)}
                            </p>

                            <p>
                                <b><spring:message code="mediaFileSuccess.type"/></b>
                                ${fn:escapeXml(newFile.contentType)},&nbsp;

                                <b><spring:message code="mediaFileSuccess.size"/></b>
                                <%-- No dimensions here: this loop is the
                                     enclosure (non-image) list, where width
                                     and height are 0 and "0 x 0 pixels" is a
                                     confusing claim about a PDF. The image
                                     loop above keeps them. --%>
                                ${newFile.length} <spring:message code="mediaFileSuccess.bytes"/>
                            </p>

                            <p>
                                <b><spring:message code="mediaFileSuccess.link"/></b>
                            </p>
                            <p>
                                <input type="text" class="form-control-plaintext d-inline-block w-auto"
                                       id="clip_file_${newFile.id}" size="57"
                                       value='${urls.media(newFile)}' readonly
                                       aria-label="<spring:message code='mediaFileSuccess.link'/>"/>
                                <button class="btn btn-secondary btn-sm clipbutton" type="button"
                                        data-clipboard-target="#clip_file_${newFile.id}"
                                        aria-label="<spring:message code='generic.copyToClipboard'/>"><spring:message code="generic.copy"/></button>
                            </p>
                        </div>

                    </div>

                </div>
            </div>
        </c:forEach>

        <div class="card">
            <div class="card-body">
                <div class="row">

                    <div class="col-md-1">
                        <input type="radio" name="enclosure" onchange="setEnclosure('')"
                               aria-label="<spring:message code='mediaFileSuccess.noEnclosure'/>" />
                    </div>

                    <div class="col-md-10">
                        <spring:message code="mediaFileSuccess.noEnclosure"/>
                    </div>

                </div>
            </div>
        </div>

    </c:if>

    <%-- buttons for create new weblog, cancel and upload more --%>

    <div>
        <c:url var="mediaFileAddURL" value="/pressroom-ui/authoring/mediaFileAdd.rol">
            <c:param name="weblog" value="${actionWeblog.handle}"/>
            <c:param name="directoryName" value="${directoryName}"/>
        </c:url>

        <c:url var="mediaFileViewURL" value="/pressroom-ui/authoring/mediaFileView.rol">
            <c:param name="weblog" value="${actionWeblog.handle}"/>
            <c:param name="directoryId" value="${bean.directoryId}"/>
        </c:url>

        <button type="submit" id="createPostButton" class="btn btn-primary" formaction="${pageContext.request.contextPath}/pressroom-ui/authoring/entryAddWithMediaFile.rol"><spring:message code="mediaFileSuccess.createPost"/></button>

        <%-- "Done", not "Cancel": the upload has happened, and nothing here
             undoes it.
             Anchors, not buttons. These two navigate; only "create post" above
             submits. As typeless <button>s inside this action-less form they
             defaulted to type="submit", so each one re-POSTed to the upload
             endpoint -- and their onclick called window.load(), which is not a
             function, so they threw first and submitted anyway (an uncaught
             handler exception does not prevent the default action). Cancel
             therefore did the opposite of cancelling. Same idiom as
             UserEdit.jsp's cancel link: an anchor cannot submit, and works
             with JavaScript disabled. --%>
        <a href="${mediaFileAddURL}" class="btn btn-secondary">
            <spring:message code="mediaFileSuccess.uploadMore"/>
        </a>

        <a href="${mediaFileViewURL}" class="btn btn-secondary">
            <spring:message code="generic.done"/>
        </a>
    </div>

<sec:csrfInput/>
</form>


<%-- ================================================================================= --%>

<script>

    var submitButton = $("#createPostButton");

    $(document).ready(function () {
        // ClipboardJS is loaded globally by head.jsp.
        var clipboard = new ClipboardJS('.clipbutton');
        clipboard.on('success', function (e) {
            e.trigger.classList.add('copied');
            setTimeout(function () { e.trigger.classList.remove('copied'); }, 1500);
            e.clearSelection();
        });

        $("#createPostButton").prop("disabled", true);

        $("input[type='checkbox']").change(function () {
            if ($("#enclosureURL").val() !== '') {
                $("#createPostButton").prop("disabled", false);
                return;
            }
            submitButton.prop("disabled", !isImageChecked());
        });
    });

    function isImageChecked() {
        var boxes = $("input[type='checkbox']");
        for (var i = 0; i < boxes.length; i++) {
            if (boxes.get(i).checked) {
                return true;
            }
        }
        return false;
    }

    function setEnclosure(url) {
        $("#enclosureURL").get(0).value = url;
        if (isImageChecked()) {
            $("#createPostButton").prop("disabled", false);
            return;
        }
        submitButton.prop("disabled", url === '');
    }

</script>
