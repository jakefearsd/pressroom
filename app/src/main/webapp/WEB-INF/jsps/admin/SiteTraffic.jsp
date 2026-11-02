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

<%-- Admin -> Traffic: every blog's numbers from the local snapshots, a total
     for views and visits only (a visitor total would count a reader of two
     blogs twice), and the site's top posts and sources. Blog names are author
     text and are escaped; entry titles are stored escaped and go out bare.
     RouteSweepIT finds this page by #siteTrafficPage. --%>
<div id="siteTrafficPage">
    <p class="subtitle"><spring:message code="siteTraffic.subtitle"/></p>

    <jsp:include page="/WEB-INF/jsps/editor/TrafficHeader.jsp">
        <jsp:param name="path" value="/pressroom-ui/admin/siteTraffic.rol"/>
        <jsp:param name="tab" value=""/>
        <jsp:param name="sort" value=""/>
    </jsp:include>

<c:if test="${traffic.showBody}">

    <div class="table-responsive">
    <table class="pressroomtable table table-striped traffic-table" width="100%">
        <tr>
            <th scope="col" class="pressroomtable"><spring:message code="siteTraffic.column.blog"/></th>
            <th scope="col" class="pressroomtable n"
                title="<spring:message code="siteTraffic.visitors.why"/>"><spring:message code="traffic.column.visitors"/></th>
            <th scope="col" class="pressroomtable n"><spring:message code="traffic.column.views"/></th>
            <th scope="col" class="pressroomtable n"><spring:message code="siteTraffic.column.change"/></th>
            <th scope="col" class="pressroomtable"><spring:message code="siteTraffic.column.topPost"/></th>
        </tr>
        <c:forEach items="${traffic.blogs}" var="line">
        <tr>
            <td>
                <c:url var="blogUrl" value="/pressroom-ui/authoring/traffic.rol">
                    <c:param name="weblog" value="${line.handle}"/>
                    <c:param name="period" value="${traffic.period}"/>
                </c:url>
                <a href="${blogUrl}">
                    <c:choose>
                    <c:when test="${line.siteSpace}"><spring:message code="siteTraffic.siteSpace"/></c:when>
                    <c:otherwise>${fn:escapeXml(line.name)}</c:otherwise>
                    </c:choose>
                </a>
            </td>
            <td class="n">${fn:escapeXml(line.visitors)}</td>
            <td class="n">${fn:escapeXml(line.views)}</td>
            <td class="n ${line.changeClass}">${fn:escapeXml(line.change)}</td>
            <td>${line.topPostTitle}</td>
        </tr>
        </c:forEach>
        <tr class="traffic-total">
            <th scope="row" class="pressroomtable"><spring:message code="siteTraffic.total"/></th>
            <td class="n"><span title="<spring:message code="siteTraffic.visitors.why"/>">&mdash;</span></td>
            <td class="n">${fn:escapeXml(traffic.totalViews)}</td>
            <td class="n"></td>
            <td><spring:message code="siteTraffic.totalVisits" arguments="${[fn:escapeXml(traffic.totalVisits)]}"/></td>
        </tr>
    </table>
    </div>

    <h2 class="traffic-heading"><spring:message code="siteTraffic.topPosts"/></h2>
    <c:choose>
    <c:when test="${not empty traffic.posts}">
    <div class="table-responsive">
    <table class="pressroomtable table table-striped traffic-table" width="100%">
        <tr>
            <th scope="col" class="pressroomtable"><spring:message code="traffic.column.post"/></th>
            <th scope="col" class="pressroomtable n"><spring:message code="traffic.column.views"/></th>
            <th scope="col" class="pressroomtable n"><spring:message code="traffic.column.visitors"/></th>
        </tr>
        <c:forEach items="${traffic.posts}" var="top">
        <tr>
            <td>
                <c:choose>
                <c:when test="${not empty post.title}">${top.title}</c:when>
                <c:otherwise><span class="data">${fn:escapeXml(post.path)}</span></c:otherwise>
                </c:choose>
            </td>
            <td class="n"><fmt:formatNumber value="${top.views}"/></td>
            <td class="n">
                <c:choose>
                <c:when test="${top.visitors lt 0}">&mdash;</c:when>
                <c:otherwise><fmt:formatNumber value="${top.visitors}"/></c:otherwise>
                </c:choose>
            </td>
        </tr>
        </c:forEach>
    </table>
    </div>
    </c:when>
    <c:otherwise><p class="traffic-empty"><spring:message code="traffic.empty.posts"/></p></c:otherwise>
    </c:choose>

    <h2 class="traffic-heading"><spring:message code="siteTraffic.topSources"/></h2>
    <c:choose>
    <c:when test="${not empty traffic.sources}">
    <div class="table-responsive">
    <table class="pressroomtable table table-striped traffic-table" width="100%">
        <tr>
            <th scope="col" class="pressroomtable"><spring:message code="traffic.column.name.sources"/></th>
            <th scope="col" class="pressroomtable n"
                title="<spring:message code="siteTraffic.sources.why"/>"><spring:message code="traffic.column.value.sources"/></th>
        </tr>
        <c:forEach items="${traffic.sources}" var="row">
        <tr>
            <td>${fn:escapeXml(row.label)}</td>
            <td class="n">${fn:escapeXml(row.value)}</td>
        </tr>
        </c:forEach>
    </table>
    </div>
    </c:when>
    <c:otherwise><p class="traffic-empty"><spring:message code="traffic.empty.tab"/></p></c:otherwise>
    </c:choose>

</c:if>

    <p class="traffic-fresh">
        <c:if test="${not empty traffic.asOfText}">
            <spring:message code="traffic.asOf" arguments="${[fn:escapeXml(traffic.asOfText)]}"/>
        </c:if>
    </p>
</div>
