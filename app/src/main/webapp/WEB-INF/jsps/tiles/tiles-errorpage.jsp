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
<!doctype html>
<html lang="${pageContext.response.locale.toLanguageTag()}">
    <head>
      <meta charset="utf-8">
      <meta http-equiv="X-UA-Compatible" content="IE=edge">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <link rel="icon" href="<%= request.getContextPath() %>/favicon.svg" type="image/x-icon">
      <%-- A denial or a stack trace both land here. RedirectController names
           a pageTitle for the denied path; anything else falls back to the
           generic `error` key rather than a blank tab. --%>
      <title>${fn:escapeXml(rc:getProp('site.shortName'))}: <c:choose><c:when test="${empty pageTitle}"><spring:message code="error"/></c:when><c:otherwise><spring:message code="${pageTitle}" text="${pageTitle}"/></c:otherwise></c:choose></title>
      <jsp:include page="${tile_head}" />
      <style>
          <jsp:include page="${tile_styles}" />
      </style>
    </head>
    <body>

        <div id="banner">
            <jsp:include page="${tile_banner}" />
        </div>

        <%-- The six left/center/right wrapper ids this used to carry were a
             pre-Bootstrap centring scheme; not one of them has had a CSS rule
             for years. Bootstrap's grid does the centring. --%>
        <div class="container-fluid">
            <div class="row">
                <div class="col-md-1"></div>
                <main class="col-md-10">
                    <jsp:include page="${tile_messages}" />
                    <jsp:include page="${tile_content}" />
                </main>
                <div class="col-md-1"></div>
            </div>
        </div>

        <footer class="footer">
            <div class="container-fluid">
                <jsp:include page="${tile_footer}" />
            </div>
        </footer>

    </body>
</html>
