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

<%-- The Traffic fragment: period switch, state notice, headline tiles, chart,
     tabs and freshness line. Expects the request attribute "traffic" (a
     TrafficView) and optionally "trafficAction", the context-relative path
     the period, tab and sort links point at (default: the blog's Traffic
     page). Links carry "weblog" when an actionWeblog is in scope. No script,
     no inline style: the chart is SVG drawn from the model. Entry titles are
     stored escaped and go out bare; every other text is escaped. --%>
<c:set var="trafficPath" value="${empty trafficAction ? '/pressroom-ui/authoring/traffic.rol' : trafficAction}"/>

<jsp:include page="/WEB-INF/jsps/editor/TrafficHeader.jsp">
    <jsp:param name="path" value="${trafficPath}"/>
    <jsp:param name="tab" value="${traffic.tab}"/>
    <jsp:param name="sort" value="${traffic.sort}"/>
</jsp:include>

<c:if test="${not empty traffic.notRegisteredError}">
    <p class="traffic-notice traffic-notice-notRegistered" role="status"><spring:message code="traffic.state.notRegistered" arguments="${[fn:escapeXml(traffic.notRegisteredError)]}"/></p>
</c:if>

<c:if test="${traffic.showBody}">

<ul class="traffic-tiles" aria-label="<spring:message code='traffic.tile.changeNote'/>">
    <c:forEach items="${traffic.tiles}" var="tile">
        <li class="traffic-tile">
            <span class="traffic-tile-label"><spring:message code="${tile.labelKey}"/></span>
            <span class="traffic-tile-value">${fn:escapeXml(tile.value)}</span>
            <span class="traffic-tile-change ${tile.trend}">${fn:escapeXml(tile.change)}</span>
        </li>
    </c:forEach>
</ul>

<svg class="traffic-chart" viewBox="0 0 ${traffic.chart.width} ${traffic.chart.height}" role="img"
     aria-label="<spring:message code='traffic.chart.label'/>" preserveAspectRatio="none">
    <title><spring:message code="traffic.chart.label"/></title>
    <line class="traffic-baseline" x1="0" y1="${traffic.chart.plotHeight}"
          x2="${traffic.chart.width}" y2="${traffic.chart.plotHeight}"/>
    <c:forEach items="${traffic.chart.bars}" var="bar">
        <rect class="traffic-bar" x="${bar.x}" y="${bar.y}" width="${bar.width}" height="${bar.height}">
            <title>${fn:escapeXml(bar.title)}</title>
        </rect>
        <c:if test="${not empty bar.label}">
            <text class="traffic-axis" x="${bar.x + bar.width / 2}" y="${traffic.chart.height - 6}"
                  text-anchor="middle">${fn:escapeXml(bar.label)}</text>
        </c:if>
    </c:forEach>
</svg>

<nav class="traffic-tabs" aria-label="<spring:message code='traffic.tabs.label'/>">
    <c:forEach items="${traffic.tabs}" var="t">
        <c:url var="tabUrl" value="${trafficPath}">
            <c:if test="${not empty actionWeblog}"><c:param name="weblog" value="${actionWeblog.handle}"/></c:if>
            <c:param name="period" value="${traffic.period}"/>
            <c:param name="tab" value="${t}"/>
        </c:url>
        <a href="${tabUrl}" class="traffic-tab${t eq traffic.tab ? ' on' : ''}"
           <c:if test="${t eq traffic.tab}">aria-current="page"</c:if>><spring:message code="traffic.tab.${t}"/></a>
    </c:forEach>
</nav>

<c:choose>
<c:when test="${traffic.tab eq 'posts'}">
    <c:choose>
    <c:when test="${not empty traffic.posts}">
    <div class="table-responsive">
    <table class="pressroomtable table table-striped traffic-table" width="100%">
        <tr>
            <th scope="col" class="pressroomtable"><spring:message code="traffic.column.post"/></th>
            <c:forEach items="${['views', 'visitors', 'trend']}" var="col">
                <c:url var="sortUrl" value="${trafficPath}">
                    <c:if test="${not empty actionWeblog}"><c:param name="weblog" value="${actionWeblog.handle}"/></c:if>
                    <c:param name="period" value="${traffic.period}"/>
                    <c:param name="tab" value="posts"/>
                    <c:param name="sort" value="${col}"/>
                </c:url>
                <th scope="col" class="pressroomtable n"
                    <c:if test="${col eq traffic.sort}">aria-sort="descending"</c:if>>
                    <a href="${sortUrl}" class="quiet-link"><spring:message code="traffic.column.${col}"/></a>
                </th>
            </c:forEach>
        </tr>
        <c:forEach items="${traffic.posts}" var="line">
        <tr>
            <td>
                <c:choose>
                <c:when test="${not empty line.entryId and not empty line.title}">
                    <c:url var="editUrl" value="/pressroom-ui/authoring/entryEdit.rol">
                        <c:param name="weblog" value="${actionWeblog.handle}"/>
                        <c:param name="bean.id" value="${line.entryId}"/>
                    </c:url>
                    <a href="${editUrl}">${line.title}</a>
                </c:when>
                <c:otherwise><span class="data">${fn:escapeXml(line.path)}</span></c:otherwise>
                </c:choose>
            </td>
            <td class="n"><fmt:formatNumber value="${line.views}"/></td>
            <td class="n">
                <c:choose>
                <c:when test="${line.visitors lt 0}">&mdash;</c:when>
                <c:otherwise><fmt:formatNumber value="${line.visitors}"/></c:otherwise>
                </c:choose>
            </td>
            <td class="n ${line.trendClass}">${fn:escapeXml(line.trendText)}</td>
        </tr>
        </c:forEach>
    </table>
    </div>
    <c:if test="${traffic.pathsCappedAt gt 0}">
    <p class="traffic-empty"><spring:message code="traffic.posts.capped" arguments="${[traffic.pathsCappedAt]}"/></p>
    </c:if>
    </c:when>
    <c:otherwise>
    <p class="traffic-empty"><spring:message code="traffic.empty.posts"/></p>
    </c:otherwise>
    </c:choose>
</c:when>
<c:otherwise>
    <c:choose>
    <c:when test="${not empty traffic.rows}">
    <div class="table-responsive">
    <table class="pressroomtable table table-striped traffic-table" width="100%">
        <tr>
            <th scope="col" class="pressroomtable"><spring:message code="traffic.column.name.${traffic.tab}"/></th>
            <th scope="col" class="pressroomtable n"><spring:message code="traffic.column.value.${traffic.tab}"/></th>
        </tr>
        <c:set var="lastGroup" value=""/>
        <c:forEach items="${traffic.rows}" var="row">
        <c:if test="${not empty row.group and row.group ne lastGroup}">
        <tr class="traffic-group"><th scope="rowgroup" colspan="2"><spring:message code="traffic.group.${row.group}"/></th></tr>
        <c:set var="lastGroup" value="${row.group}"/>
        </c:if>
        <tr>
            <td>
                <c:choose>
                <c:when test="${traffic.tab eq 'speed'}"><spring:message code="traffic.speed.${row.label}" text="${fn:escapeXml(row.label)}"/></c:when>
                <c:otherwise>${fn:escapeXml(row.label)}</c:otherwise>
                </c:choose>
            </td>
            <td class="n">${fn:escapeXml(row.value)}</td>
        </tr>
        </c:forEach>
    </table>
    </div>
    </c:when>
    <c:when test="${traffic.tab eq 'speed'}">
    <p class="traffic-empty"><spring:message code="traffic.empty.speed"/></p>
    </c:when>
    <c:otherwise>
    <p class="traffic-empty"><spring:message code="traffic.empty.tab"/></p>
    </c:otherwise>
    </c:choose>
</c:otherwise>
</c:choose>

</c:if>

<p class="traffic-fresh">
    <c:if test="${not empty traffic.asOfText}">
        <spring:message code="traffic.asOf" arguments="${[fn:escapeXml(traffic.asOfText)]}"/>
    </c:if>
    <c:if test="${not empty traffic.umamiUrl}">
        <a href="${fn:escapeXml(traffic.umamiUrl)}" target="_blank" rel="noopener" class="quiet-link"><spring:message code="traffic.openInUmami"/></a>
    </c:if>
</p>
