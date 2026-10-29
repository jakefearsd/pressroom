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
    <spring:message code="categoriesForm.subtitle" arguments="${actionWeblog.handle}"/>
</p>

<%-- The list's one "New ..." action, in the card above the table as on
     Pages, Businesses and Users (it used to be a link in the sidebar, which
     landed above the whole page on a phone). An empty list carries the action
     in its empty state instead, so exactly one of the two renders and
     id="addCategoryButton" (what CategoryIT keys on) is on whichever does. --%>
<c:if test="${not empty allCategories}">
<div class="list-actions">
    <button type="button" id="addCategoryButton" class="btn btn-primary btn-sm" onclick="showCategoryAddModal()">
        <spring:message code="categoriesForm.addCategory"/>
    </button>
</div>
</c:if>

<%-- A plain table. It used to be wrapped in a form posting to
     categories!move.rol, left over from a bulk-move UI whose checkboxes and
     submit button are long gone -- so nothing could submit it, and the handler
     only re-rendered this same list.

     The <table id="category-table"> ELEMENT is unconditional even on a weblog
     with no categories: RouteSweepIT identifies this route by
     "table#category-table" (Routes.java) and CategoryIT waits on the same
     selector after every save, so gating the table itself would leave both
     with nothing to match. Only its CONTENTS are gated. The header row used to
     sit outside the c:choose, so an empty weblog rendered a full four-column
     header above a single colspan="6" strip -- on a four-column table. The
     invitation below is a sibling of the table, not a row inside it. --%>
    <div class="table-responsive">
    <table id="category-table" class="pressroomtable table table-striped" width="100%">

        <c:if test="${not empty allCategories}">

            <tr class="pressroomtable">
                <th scope="col" width="30%"><spring:message code="generic.name"/></th>
                <th scope="col"><spring:message code="generic.description"/></th>
                <th scope="col" class="row-actions"><span class="visually-hidden"><spring:message code="mainPage.actions"/></span></th>
            </tr>

            <c:forEach items="${allCategories}" var="category" varStatus="rowstatus">
                <%-- data-category-* so a test can find a row without parsing the
                     onclick attributes that drive the modals. --%>
                <tr data-category-id="${fn:escapeXml(category.id)}"
                    data-category-name="${fn:escapeXml(category.name)}">
                    <td>${fn:escapeXml(category.name)}</td>

                    <td>${fn:escapeXml(category.description)}</td>

                    <td class="row-actions">

                        <c:set var="categoryId" value="${category.id}"/>
                        <c:set var="categoryName" value="${fn:escapeXml(category.name)}"/>
                        <c:set var="categoryDesc" value="${fn:escapeXml(category.description)}"/>
                        <c:set var="categoryImage" value="${fn:escapeXml(category.image)}"/>
                        <%-- id/name/description/image ride in data-*
                             attributes, not an interpolated onclick string --
                             fn:escapeXml renders an apostrophe as &#039;,
                             which the HTML parser decodes back to ' BEFORE
                             the onclick attribute compiles as JavaScript, so
                             a category named e.g. "Maiia's Portraits" made
                             this control a permanent SyntaxError. Delegated
                             handler below (same convention as
                             MediaFileView.jsp:493). --%>
                        <button type="button" class="row-action category-edit-btn"
                                data-category-id="${categoryId}" data-category-name="${categoryName}"
                                data-category-desc="${categoryDesc}" data-category-image="${categoryImage}"
                                aria-label="<spring:message code='generic.edit'/>: ${categoryName}">
                            <span class="bi bi-pencil-square" aria-hidden="true"></span>
                        </button>

                        <c:if test="${fn:length(allCategories) > 1}">

                            <c:set var="categoryId" value="${category.id}"/>
                            <c:set var="categoryName" value="${fn:escapeXml(category.name)}"/>
                            <c:set var="categoryInUse" value="${categoriesInUse.contains(category.id)}"/>
                            <button type="button" class="row-action row-action-danger category-delete-btn"
                                    data-category-id="${categoryId}" data-category-name="${categoryName}"
                                    data-category-in-use="${categoryInUse}"
                                    aria-label="<spring:message code='categoriesForm.remove'/>: ${categoryName}">
                                <span class="bi bi-trash" aria-hidden="true"></span>
                            </button>

                        </c:if>
                    </td>

                </tr>
            </c:forEach>

        </c:if>

    </table>
    </div>

<%-- Empty state: an invitation, not a strip of table. showCategoryAddModal()
     (below) drives the same #category-edit-modal as the button above the
     table; only one of the two renders. --%>
<c:if test="${empty allCategories}">
    <div class="empty-state">
        <p class="empty-state-title"><spring:message code="empty.categories.title"/></p>
        <p class="empty-state-body"><spring:message code="empty.categories.body"/></p>
        <button type="button" id="addCategoryButton" class="btn btn-primary" onclick="showCategoryAddModal()">
            <spring:message code="empty.categories.action"/>
        </button>
    </div>
</c:if>


<%-- ============================================================= --%>
<%-- add/edit category modal --%>

<div id="category-edit-modal" class="modal category-edit-modal" tabindex="-1" role="dialog" aria-modal="true" aria-labelledby="category-edit-title">

    <div class="modal-dialog modal-lg">

        <div class="modal-content">

            <div class="modal-header">
                <p id="category-edit-title" class="modal-title"></p>
            </div>

            <div class="modal-body">
                <form id="categoryEditForm" class="form-stacked" action="${pageContext.request.contextPath}/pressroom-ui/authoring/categoryEdit.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
                    <input type="hidden" name="bean.id" value="${fn:escapeXml(bean.id)}"/>

                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label" for="category_bean_name"><spring:message code="generic.name"/></label>
                        <div class="col-sm-9">
                            <input id="category_bean_name" type="text" name="bean.name" value="${fn:escapeXml(bean.name)}" maxlength="255" class="form-control" onchange="validateCategory()" onkeyup="validateCategory()"/>
                        </div>
                    </div>

                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label" for="category_bean_description"><spring:message code="generic.description"/></label>
                        <div class="col-sm-9">
                            <input id="category_bean_description" type="text" name="bean.description" value="${fn:escapeXml(bean.description)}" class="form-control"/>
                        </div>
                    </div>

                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label" for="category_bean_image"><spring:message code="categoryForm.image"/></label>
                        <div class="col-sm-9">
                            <input id="category_bean_image" type="text" name="bean.image" value="${fn:escapeXml(bean.image)}" class="form-control" onchange="validateCategory()" onkeyup="validateCategory()"/>
                        </div>
                    </div>
                <sec:csrfInput/>
</form>
            </div>

            <div class="modal-footer">
                <p id="feedback-area-edit"></p>
                <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">
                    <spring:message code="generic.cancel"/>
                </button>
                <button id="category-save-button" type="button" onclick="submitEditedCategory()" class="btn btn-primary">
                    <spring:message code="generic.save"/>
                </button>
            </div>

        </div>
    </div>
</div>

<script>

    var feedbackAreaEdit = $("#feedback-area-edit");

    /*
     * Fields are reached by NAME, scoped to the form. They used to be reached by
     * ids like #categoryEditForm_bean_name, which Struts generated and the JSP
     * migration never reproduced -- so every selector here matched nothing,
     * .val(x) was a silent no-op and .val().trim() threw on undefined. The
     * result was a page where Add, Edit and Delete all did nothing at all.
     * Names are what the server binds, so they cannot drift without the
     * handler noticing.
     */
    function categoryField( name ) {
        return $("#categoryEditForm [name='" + name + "']");
    }

    <%-- Delegated: a row's id/name/description/image ride in data-*
         attributes on the button (see the comment above it), never in an
         inline onclick string. --%>
    $(document).on('click', '.category-edit-btn', function () {
        showCategoryEditModal(this.dataset.categoryId, this.dataset.categoryName,
                this.dataset.categoryDesc, this.dataset.categoryImage);
    });

    function showCategoryEditModal( id, name, desc, image ) {
        feedbackAreaEdit.html("");
        $('#category-edit-title').text('<spring:message code="categoryForm.edit.title" javaScriptEscape="true"/>');

        categoryField('bean.id').val(id);
        categoryField('bean.name').val(name);
        categoryField('bean.description').val(desc);
        categoryField('bean.image').val(image);
        validateCategory();

        bootstrap.Modal.getOrCreateInstance(document.getElementById('category-edit-modal')).show();

    }

    function validateCategory() {

        var saveCategoryButton = $('#category-save-button');

        var categoryName = categoryField('bean.name').val();
        var imageURL = categoryField('bean.image').val();

        if (!categoryName || categoryName.trim() === '') {
            saveCategoryButton.prop("disabled", true);
            feedbackAreaEdit.text('<spring:message code="categoryForm.requiredFields" javaScriptEscape="true"/>');
            feedbackAreaEdit.css("color", "var(--bad)");
            return;
        }

        if (imageURL && imageURL.trim() !== '') {
            if (!isValidUrl(imageURL)) {
                saveCategoryButton.prop("disabled", true);
                feedbackAreaEdit.text('<spring:message code="categoryForm.badURL" javaScriptEscape="true"/>');
                feedbackAreaEdit.css("color", "var(--bad)");
                return;
            }
        }

        feedbackAreaEdit.html('');
        saveCategoryButton.prop("disabled", false);
    }

    <%-- The modal's form carries no submit button, so the browser's implicit
         submission never fires and Enter did nothing whatever. Bound on the
         three text inputs only -- not on the form -- so a future textarea in
         there keeps its newlines. --%>
    $(document).on('keydown', "#category-edit-modal input[type='text']", function (event) {
        if (event.key === 'Enter') {
            event.preventDefault();
            submitEditedCategory();
        }
    });

    function submitEditedCategory() {

        // if name is empty reject and show error message
        var enteredName = categoryField('bean.name').val();
        if (!enteredName || enteredName.trim() === "") {
            feedbackAreaEdit.text('<spring:message code="categoryForm.requiredFields" javaScriptEscape="true"/>');
            feedbackAreaEdit.css("color", "var(--bad)");
            return;
        }

        // Add and edit are different handlers. This always posted to
        // categoryEdit!save.rol, so "Add Category" arrived at the edit handler
        // with an empty bean.id, found no such category and failed -- adding a
        // category through the UI had never worked.
        var hasId = categoryField('bean.id').val();
        var saveUrl = hasId && hasId.trim() !== ''
                ? "${pageContext.request.contextPath}/pressroom-ui/authoring/categoryEdit!save.rol"
                : "${pageContext.request.contextPath}/pressroom-ui/authoring/categoryAdd!save.rol";

        // post category via AJAX
        $.ajax({
            method: 'post',
            url: saveUrl,
            data: $("#categoryEditForm").serialize(),
            context: document.body

        }).done(function () {

            // The response is never inspected. It used to be scraped for the
            // duplicate-name and invalid-name strings inside an "ALERT_END"
            // marker -- a Struts-era kludge that has not been able to match
            // anything since CategoryEditController started answering with a
            // 302 and a flash message: jQuery follows the redirect, so `data`
            // is the redirected page, and the flash renders on the reload
            // below anyway. Scraping localized text is also the shape that
            // breaks the moment a message gains a placeholder, which is
            // exactly what happened.
            feedbackAreaEdit.css("color", "var(--good)");
            feedbackAreaEdit.text('<spring:message code="generic.success" javaScriptEscape="true"/>');
            bootstrap.Modal.getOrCreateInstance(document.getElementById('category-edit-modal')).hide();
            location.reload(true);

        // .fail, not .error: jQuery removed .error() in 3.0, so this threw
        // "TypeError: $.ajax(...).done(...).error is not a function" on every
        // single save.
        }).fail(function () {
            feedbackAreaEdit.text('<spring:message code="generic.error.check.logs" javaScriptEscape="true"/>');
            feedbackAreaEdit.css("color", "var(--bad)");
        });
    }

</script>


<%-- ============================================================= --%>
<%-- delete confirmation modal --%>

<div id="delete-category-modal" class="modal delete-category-modal" tabindex="-1" role="dialog" aria-modal="true" aria-labelledby="delete-category-modal-title">

    <div class="modal-dialog modal-lg">

        <div class="modal-content">

            <div class="modal-header">
                <p id="delete-category-modal-title" class="modal-title">
                    <spring:message code="categoryDeleteOK.removeCategory"/>:
                    <span id="category-name"></span>
                </p>
            </div>

            <form id="categoryRemoveForm" action="${pageContext.request.contextPath}/pressroom-ui/authoring/categoryRemove!remove.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
                <input type="hidden" name="removeId" value="${fn:escapeXml(removeId)}"/>

                <div class="modal-body">

                    <div id="category-in-use" style="display:none">
                        <p>
                            <spring:message code="categoryDeleteOK.warningCatInUse"/>
                            <spring:message code="categoryDeleteOK.youMustMoveEntries"/>
                        </p>
                        <label for="targetCategoryId"><spring:message code="categoryDeleteOK.moveToWhere"/></label>
                        <select id="targetCategoryId" name="targetCategoryId" class="form-select">
<c:forEach items="${allCategories}" var="opt">
<option value="${opt.id}" ${opt.id == targetCategoryId ? 'selected' : ''}>${fn:escapeXml(opt.name)}</option>
</c:forEach>
</select>
                    </div>

                    <div id="category-empty" style="display:none">
                        <p><spring:message code="categoryDeleteOK.noEntriesInCat"/></p>
                    </div>

                    <p> <strong><spring:message code="categoryDeleteOK.areYouSure"/></strong> </p>
                </div>

                <div class="modal-footer">
                    <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">
                        <spring:message code="generic.cancel"/>
                    </button>
                    <button type="submit" class="btn btn-danger"><spring:message code="generic.delete"/></button>
                </div>

            <sec:csrfInput/>
</form>

        </div>
    </div>
</div>

<script>

    <%-- Delegated: a row's id/name/in-use flag ride in data-* attributes on
         the button (see the comment above it), never in an inline onclick
         string. data-category-in-use is the string "true"/"false" (an EL
         boolean stringified into an attribute), so it is compared as a
         string here rather than passed through as the JS boolean the old
         onclick built. --%>
    $(document).on('click', '.category-delete-btn', function () {
        showCategoryDeleteModal(this.dataset.categoryId, this.dataset.categoryName,
                this.dataset.categoryInUse === 'true');
    });

    function showCategoryDeleteModal( id, name, inUse ) {
        // Same story as the edit modal: this set #categoryRemove_removeId,
        // which does not exist, so Delete posted an empty id and removed
        // nothing. (It also set the *edit* form's name field, from the delete
        // dialog, which never meant anything.)
        $("#categoryRemoveForm [name='removeId']").val(id);
        $('#category-name').text(name);
        if ( inUse ) {
            $('#category-in-use').css('display','block');
            $('#category-empty').css('display', 'none');
        } else {
            $('#category-in-use').css('display', 'none');
            $('#category-empty').css('display', 'block');
        }
        populateCategorySelect(id);
        bootstrap.Modal.getOrCreateInstance(document.getElementById('delete-category-modal')).show();
    }

    function populateCategorySelect(removeId) {
        // Read the category list back out of the DOM (the data-category-id/
        // data-category-name attributes every row already carries,
        // fn:escapeXml-escaped -- see the table above) instead of templating
        // category.name straight into this script block. A category named
        // to break out of a JS string literal used to execute for every
        // viewer of this page on load, no click required.
        const allCategories = $("#category-table tr[data-category-id]").map(function () {
            return {
                id: $(this).attr('data-category-id'),
                name: $(this).attr('data-category-name')
            };
        }).get();

        // The select is rendered server-side holding every category, this
        // filter drops the one being deleted. It targeted a non-existent id,
        // so the filter never ran and the modal offered you the category you
        // were deleting as the place to move its own entries.
        const select = $("#categoryRemoveForm [name='targetCategoryId']");
        select.empty();
        allCategories.forEach(function(category) {
            if (category.id !== removeId) {
                select.append(new Option(category.name, category.id));
            }
        });
    }

</script>

<script>

    function showCategoryAddModal() {

        feedbackAreaEdit.html("");
        $('#category-edit-title').text('<spring:message code="categoryForm.add.title" javaScriptEscape="true"/>');

        // categoryField() (above) reaches the inputs by name; the ids these lines used to
        // use (#categoryEditForm_bean_name and friends) were generated by
        // Struts and no longer exist, so clearing the form did nothing and Add
        // opened showing whatever was last in it -- when it opened at all.
        categoryField('bean.id').val("");
        categoryField('bean.name').val("");
        categoryField('bean.description').val("");
        categoryField('bean.image').val("");

        validateCategory();

        bootstrap.Modal.getOrCreateInstance(document.getElementById('category-edit-modal')).show();
    }

    <%-- Focus has to wait for shown.bs.modal: Bootstrap animates the dialog
         in, and an element inside a display:none subtree cannot take focus.
         Bound once on the element, not per open. --%>
    $(document).ready(function () {
        $('#category-edit-modal').on('shown.bs.modal', function () {
            categoryField('bean.name').trigger('focus');
        });
    });

</script>
