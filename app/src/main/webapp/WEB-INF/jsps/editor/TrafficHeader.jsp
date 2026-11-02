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

<%-- The period switch and the state notice, shared by the blog's Traffic page
     (through TrafficBody.jsp) and the Global page. Expects the request
     attribute "traffic" (anything with period, periods, noticeKey and
     ageMinutes) and the include parameters "path" (the context-relative page
     the links point at) and, optionally, "tab" and "sort" to carry along.
     Links carry "weblog" when an actionWeblog is in scope. --%>
<c:set var="trafficPath" value="${param.path}"/>

<nav class="traffic-period" aria-label="<spring:message code='traffic.period.label'/>">
    <c:forEach items="${traffic.periods}" var="p">
        <c:url var="periodUrl" value="${trafficPath}">
            <c:if test="${not empty actionWeblog}"><c:param name="weblog" value="${actionWeblog.handle}"/></c:if>
            <c:param name="period" value="${p}"/>
            <c:if test="${not empty param.tab}"><c:param name="tab" value="${param.tab}"/></c:if>
            <c:if test="${not empty param.sort}"><c:param name="sort" value="${param.sort}"/></c:if>
        </c:url>
        <a href="${periodUrl}" class="traffic-period-link${p eq traffic.period ? ' on' : ''}"
           <c:if test="${p eq traffic.period}">aria-current="true"</c:if>><spring:message code="traffic.period.${p}"/></a>
    </c:forEach>
</nav>

<c:if test="${not empty traffic.noticeKey}">
    <p class="traffic-notice traffic-notice-${fn:substringAfter(traffic.noticeKey, 'traffic.state.')}" role="status">
        <spring:message code="${traffic.noticeKey}" arguments="${[traffic.ageMinutes]}"/>
    </p>
</c:if>
