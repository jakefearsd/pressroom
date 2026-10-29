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
    <c:choose>
        <c:when test="${empty bean.id}"><spring:message code="businesses.edit.titleNew"/></c:when>
        <c:otherwise><spring:message code="businesses.edit.titleEdit"/></c:otherwise>
    </c:choose>
</p>

<form method="post" action="<c:url value='/pressroom-ui/admin/businesses!save.rol'/>" class="form-stacked">
    <sec:csrfInput/>
    <input type="hidden" name="bean.id" value="${fn:escapeXml(bean.id)}"/>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_name"><spring:message code="businesses.name"/></label>
        <div class="col-sm-9">
            <input type="text" id="bean_name" name="bean.name" value="${fn:escapeXml(bean.name)}"
                   maxlength="255" class="form-control" required="required"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_businessType"><spring:message code="businesses.type"/></label>
        <div class="col-sm-9">
            <select id="bean_businessType" name="bean.businessType" class="form-select">
                <option value="Organization" <c:if test="${bean.businessType == 'Organization'}">selected="selected"</c:if>
                ><spring:message code="businesses.type.Organization"/></option>
                <option value="LocalBusiness" <c:if test="${bean.businessType == 'LocalBusiness'}">selected="selected"</c:if>
                ><spring:message code="businesses.type.LocalBusiness"/></option>
                <option value="ProfessionalService" <c:if test="${bean.businessType == 'ProfessionalService'}">selected="selected"</c:if>
                ><spring:message code="businesses.type.ProfessionalService"/></option>
            </select>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_websiteUrl"><spring:message code="businesses.websiteUrl"/></label>
        <div class="col-sm-9">
            <input type="url" id="bean_websiteUrl" name="bean.websiteUrl" value="${fn:escapeXml(bean.websiteUrl)}"
                   maxlength="255" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_bookingUrl"><spring:message code="businesses.bookingUrl"/></label>
        <div class="col-sm-9">
            <input type="url" id="bean_bookingUrl" name="bean.bookingUrl" value="${fn:escapeXml(bean.bookingUrl)}"
                   maxlength="255" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_telephone"><spring:message code="businesses.telephone"/></label>
        <div class="col-sm-9">
            <input type="tel" id="bean_telephone" name="bean.telephone" value="${fn:escapeXml(bean.telephone)}"
                   maxlength="32" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_email"><spring:message code="businesses.email"/></label>
        <div class="col-sm-9">
            <input type="email" id="bean_email" name="bean.email" value="${fn:escapeXml(bean.email)}"
                   maxlength="255" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_logoUrl"><spring:message code="businesses.logoUrl"/></label>
        <div class="col-sm-9">
            <input type="url" id="bean_logoUrl" name="bean.logoUrl" value="${fn:escapeXml(bean.logoUrl)}"
                   maxlength="255" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_sameAs"><spring:message code="businesses.sameAs"/></label>
        <div class="col-sm-9">
            <textarea id="bean_sameAs" name="bean.sameAs" rows="5"
                      class="form-control">${fn:escapeXml(bean.sameAs)}</textarea>
            <div class="form-text"><spring:message code="businesses.sameAs.help" arguments="${maxSameAs}"/></div>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_areaServed"><spring:message code="businesses.areaServed"/></label>
        <div class="col-sm-9">
            <input type="text" id="bean_areaServed" name="bean.areaServed" value="${fn:escapeXml(bean.areaServed)}"
                   maxlength="255" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_description"><spring:message code="businesses.description"/></label>
        <div class="col-sm-9">
            <textarea id="bean_description" name="bean.description" rows="4"
                      class="form-control">${fn:escapeXml(bean.description)}</textarea>
        </div>
    </div>

    <div class="mb-3">
        <button type="submit" class="btn btn-primary" id="business-save"><spring:message code="generic.save"/></button>
        <c:url var="businessesListUrl" value="/pressroom-ui/admin/businesses.rol"/>
        <a href="${businessesListUrl}" class="btn btn-secondary"><spring:message code="generic.cancel"/></a>
    </div>
</form>
