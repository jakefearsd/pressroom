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
    <spring:message code="pageForm.subtitle" arguments="${[fn:escapeXml(bean.name), actionWeblog.handle]}"/>
</p>

<c:choose>
<c:when test="${template.required}">
    <p class="pagetip"><spring:message code="pageForm.tip.required"/></p>
</c:when>
<c:otherwise>
    <p class="pagetip"><spring:message code="pageForm.tip"/></p>
</c:otherwise>
</c:choose><form id="template" class="form-stacked" action="${pageContext.request.contextPath}/pressroom-ui/authoring/templateEdit!save.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <input type="hidden" name="bean.id" value="${fn:escapeXml(bean.id)}"/>
    <input type="hidden" name="bean.type" value="${fn:escapeXml(bean.type)}"/>

    <jsp:include page="/WEB-INF/jsps/editor/SessionExpiryBar.jsp"/>

    <%-- ================================================================== --%>
    <%-- Name, link and description: disabled when page is a required page --%>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="template_bean_name"><spring:message code="generic.name"/></label>
        <div class="col-sm-9">
            <c:choose>
                <c:when test="${template.required}">
                    <%-- Cannot edit name of a required template --%>
                    <input id="template_bean_name" type="text" name="bean.name" value="${fn:escapeXml(bean.name)}" size="50" readonly class="form-control"/>
                </c:when>
                <c:otherwise>
                    <input id="template_bean_name" type="text" name="bean.name" value="${fn:escapeXml(bean.name)}" size="50" class="form-control"/>
                </c:otherwise>
            </c:choose>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="template_bean_action"><spring:message code="pageForm.action"/></label>
        <div class="col-sm-9">
            <input id="template_bean_action" type="text" name="bean.action" value="${fn:escapeXml(bean.action)}" size="50" readonly class="form-control"/>
        </div>
    </div>

    <c:choose>
        <c:when test="${!template.required && template.custom}">

            <div class="row mb-3">
                <label class="col-sm-3 col-form-label" for="template_bean_link"><spring:message code="pageForm.link"/></label>
                <div class="col-sm-9">
                    <%-- allow setting the path for a custom template --%>
                    <input type="text" id="template_bean_link" name="bean.link" value="${fn:escapeXml(bean.link)}" size="50" class="form-control"/>
                </div>
            </div>

            <%-- Preview of the full URL that will result from that path. The
                 server decides the first state, so the resulting URL shows on
                 load rather than only after the reader types; the script
                 below keeps it current as they do. The values the script
                 needs ride in data-* attributes, never in JS string literals:
                 an attribute has one parser, fn:escapeXml is its escape. --%>

            <div id="no_link" class="alert alert-danger" ${empty bean.link ? '' : 'hidden'}>
                <spring:message code="pageForm.noUrl"/>
            </div>

            <div id="good_link" class="alert alert-success" ${empty bean.link ? 'hidden' : ''}
                 data-weblog-url="${fn:escapeXml(urls.weblogAbsolute(actionWeblog))}"
                 data-original-link="${fn:escapeXml(template.link)}"
                 data-type="${fn:escapeXml(bean.type)}">
                <spring:message code="pageForm.resultingUrlWillBe"/>
                <span class="data">${fn:escapeXml(urls.weblogAbsolute(actionWeblog))}page/<span id="linkPreview">${fn:escapeXml(bean.link)}</span></span>
                <c:if test="${template.link != null}">
                    <button type="button" class="btn btn-link p-0 align-baseline" id="launchLink"><spring:message code="pageForm.launch"/></button>
                    <span id="linkChanged" class="form-text" hidden><spring:message code="pageForm.launch.linkChanged"/></span>
                </c:if>
            </div>

        </c:when>
    </c:choose>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="template_bean_description"><spring:message code="generic.description"/></label>
        <div class="col-sm-9">
            <c:choose>
                <c:when test="${template.required}">
                    <%-- Required templates have a description--%>
                    <textarea id="template_bean_description" name="bean.description" rows="2" cols="50" readonly class="form-control">${fn:escapeXml(bean.description)}</textarea>
                </c:when>
                <c:otherwise>
                    <textarea id="template_bean_description" name="bean.description" rows="2" cols="50" class="form-control">${fn:escapeXml(bean.description)}</textarea>
                </c:otherwise>
            </c:choose>
        </div>
    </div>

    <%-- ================================================================== --%>

    <%-- Template content area --%>
    <div class="mb-3">
        <label for="template_bean_contentsStandard" class="form-label"><spring:message code="pageForm.source"/></label>
        <textarea id="template_bean_contentsStandard" name="bean.contentsStandard" rows="30" cols="80"
                  class="form-control" spellcheck="false">${fn:escapeXml(bean.contentsStandard)}</textarea>
    </div>

    <%-- ================================================================== --%>
    <%-- Save, Close and Resize text area buttons--%>

    <button type="submit" class="btn btn-primary"><spring:message code="generic.save"/></button>
    <input type="button" value='<spring:message code="generic.done"/>' class="btn btn-secondary"
           onclick="window.location='<c:url value="/pressroom-ui/authoring/templates.rol"><c:param name="weblog" value="${actionWeblog.handle}"/></c:url>'"/>

    <%-- ================================================================== --%>
    <%-- Advanced settings inside a control toggle --%>

    <c:if test="${template.custom}">

        <div id="accordion" class="mt-4">

        <div class="card" id="panel-advanced">

            <div class="card-header">

                <h2 class="section-head">
                    <a class="collapsed" data-bs-toggle="collapse" data-bs-target="#collapseAdvanced" href="#">
                        <spring:message code="pageForm.advancedSettings"/>
                    </a>
                </h2>

            </div>

            <div id="collapseAdvanced" class="collapse">
                <div class="card-body">

                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label" for="template_bean_templateLanguage"><spring:message code="pageForm.templateLanguage"/></label>
                        <div class="col-sm-9">
                            <select id="template_bean_templateLanguage" name="bean.templateLanguage" class="form-select" size="1">
                                <c:forEach items="${templateLanguages}" var="opt">
                                    <option value="${opt.key}" ${opt.key == bean.templateLanguage ? 'selected' : ''}>${opt.value}</option>
                                </c:forEach>
                            </select>
                        </div>
                    </div>

                    <div class="row mb-3">
                        <div class="offset-sm-3 col-sm-9">
                            <div class="form-check">
                                <label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.hidden" value="true" ${bean.hidden ? 'checked' : ''}/> <spring:message code="pageForm.hidden"/></label>
                            </div>
                        </div>
                    </div>

                    <div class="row mb-3">
                        <div class="offset-sm-3 col-sm-9">
                            <div class="form-check">
                                <label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.navbar" value="true" ${bean.navbar ? 'checked' : ''}/> <spring:message code="pageForm.navbar"/></label>
                            </div>
                        </div>
                    </div>

                    <div class="row mb-3">
                        <div class="offset-sm-3 col-sm-9">
                            <div class="form-check">
                                <label class="form-check-label"><input type="checkbox" class="form-check-input" id="template_bean_autoContentType" name="bean.autoContentType" value="true" ${bean.autoContentType ? 'checked' : ''}/> <spring:message code="pageForm.useAutoContentType"/></label>
                            </div>
                        </div>
                    </div>

                    <div class="row mb-3" id="manual-content-type-control-group" style="display:none">
                        <label class="col-sm-3 col-form-label" for="template_bean_manualContentType"><spring:message code="pageForm.useManualContentType"/></label>
                        <div class="col-sm-9">
                            <input id="template_bean_manualContentType" type="text" name="bean.manualContentType" value="${fn:escapeXml(bean.manualContentType)}" class="form-control"/>
                        </div>
                    </div>

                </div>
            </div>
        </div>

        </div>

    </c:if>

<sec:csrfInput/>
</form>


<script type="text/javascript">

    $(document).ready(function () {

        showContentTypeField();
        $("#template_bean_autoContentType").click(function(e) {
            showContentTypeField();
        });
        $("#template_bean_link").on("input", updatePageURLDisplay);
        $("#launchLink").on("click", launchPage);
        if (document.getElementById("template_bean_link")) {
            // Sync the first render too: after a refused save the field holds
            // a link that is not the saved one yet.
            updatePageURLDisplay();
        }
    });

    // Update the resulting URL as the reader edits the link. textContent,
    // never jQuery's markup setter: the typed link is text, and as markup
    // it would run.
    function updatePageURLDisplay() {
        var link = document.getElementById("template_bean_link").value;
        var good = document.getElementById("good_link");
        document.getElementById("no_link").hidden = link !== "";
        good.hidden = link === "";
        document.getElementById("linkPreview").textContent = link;

        // A changed link would 404 until it is saved: say so beside the
        // control and take the control away, rather than an alert box.
        var launch = document.getElementById("launchLink");
        if (launch) {
            var changed = link !== good.dataset.originalLink;
            launch.hidden = changed;
            document.getElementById("linkChanged").hidden = !changed;
        }
    }

    function launchPage() {
        var good = document.getElementById("good_link");
        window.open(good.dataset.weblogUrl + 'page/' + encodeURI(good.dataset.originalLink)
            + '?type=' + encodeURIComponent(good.dataset.type), '_blank');
    }

    function showContentTypeField() {
        var checked = $("#template_bean_autoContentType").prop("checked");
        if ( checked ) {
            $("#manual-content-type-control-group").hide();
        } else {
            $("#manual-content-type-control-group").show();
        }
    }

</script>
