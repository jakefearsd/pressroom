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

<p class="pagetip"><spring:message code="index.prompt"/></p>

<%--
      Index page on Roller startup; tell the user how to complete their Roller install,
      with helpful notes and links to the appropriate places in the Roller UI.
--%>


<%-- STEP 1: Create a user if you don't already have one --%>

<div class="card">
    <div class="card-header">
        <h2 class="setup-section-title">
            <spring:message code="index.createUser"/>
            <c:if test="${userCount > 0}"> -
                <spring:message code="index.createUserDone" arguments="${userCount}"/>
            </c:if>
        </h2>
    </div>

    <div class="card-body">

        <p><spring:message code="index.createUserHelp"/></p>
        <p><c:if test="${userCount == 0}">
            <spring:message code="index.createUserBy"/>
            <a id="a_createUser" href='<c:url value="/pressroom-ui/admin/createUser.rol"/>'>
                <spring:message code="index.createUserPage"/></a>.
        </c:if></p>

    </div>
</div>

<%-- STEP 2: Create a weblog if you don't already have one --%>

<div class="card">
    <div class="card-header">
        <h2 class="setup-section-title">
            <spring:message code="index.createWeblog"/>
            <c:if test="${blogCount > 0}"> -
                <spring:message code="index.createWeblogDone" arguments="${blogCount}"/>
            </c:if>
        </h2>
    </div>

    <div class="card-body">

        <p><spring:message code="index.createWeblogHelp"/></p>
        <c:if test="${userCount > 0 && blogCount == 0}">
            <spring:message code="index.createWeblogBy"/>
            <a id="a_createBlog" href='<c:url value="/pressroom-ui/createWeblog.rol"/>'>
                <spring:message code="index.createWeblogPage"/></a>.
        </c:if>

    </div>
</div>


<%-- STEP 3: Designate a weblog to be the frontpage weblog --%>

<div class="card">
    <div class="card-header">
        <h2 class="setup-section-title">
            <spring:message code="index.setFrontpage"/>
        </h2>
    </div>

    <div class="card-body">

        <p><spring:message code="index.setFrontpageHelp"/></p>

        <c:if test="${blogCount > 0}">

            <form action="${pageContext.request.contextPath}/pressroom-ui/setup!save.rol" method="post" class="form-stacked">
                <sec:csrfInput/>

                <div class="row mb-3">
                    <spring:message code="frontpageConfig.frontpageBlogName" var="frontpageBlogLabel"/>
                    <label class="col-sm-3 col-form-label" for="frontpageBlog">${frontpageBlogLabel}</label>
                    <div class="col-sm-9">
                        <select name="frontpageBlog" id="frontpageBlog" class="form-select">
                            <%-- Mirrors Maintenance.jsp's placeholder pattern: a
                                 disabled option carries "selected" only while
                                 nothing is configured yet, so a re-save can never
                                 silently pick whichever weblog happens to be
                                 first in the list. --%>
                            <option value=""
                                <c:if test="${empty frontpageWeblogHandle}">selected="selected"</c:if>
                                disabled="disabled"><spring:message code="frontpageConfig.select.placeholder"/></option>
                            <c:forEach items="${weblogs}" var="w">
                                <option value="${fn:escapeXml(w.handle)}"
                                    <c:if test="${frontpageWeblogHandle == w.handle}">selected="selected"</c:if>
                                >${fn:escapeXml(w.name)}</option>
                            </c:forEach>
                        </select>
                    </div>
                </div>

                <div class="row mb-3">
                    <spring:message code="frontpageConfig.frontpageAggregated" var="aggregatedLabel"/>
                    <label class="col-sm-3 col-form-label" for="frontpageAggregated">${aggregatedLabel}</label>
                    <div class="col-sm-9">
                        <input type="checkbox" id="frontpageAggregated" class="form-check-input" name="aggregated" value="true"
                            <c:if test="${frontpageAggregated}">checked="checked"</c:if>
                        />
                    </div>
                </div>

                <button type="submit" class="btn btn-primary"><spring:message code="generic.save"/></button>

            </form>

        </c:if>
    </div>
</div>
