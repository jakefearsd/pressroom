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
    <%-- STATIC literal, and it must stay static: every screen this layout
         serves -- CreateDatabase, UpgradeDatabase, DatabaseError, Bootstrap --
         renders BEFORE the business tier is up. The sibling layouts' scriptlet
         call to WebloggerRuntimeConfig.getProperty("site.shortName") returns
         null there AND logs a WARN with a stack trace on every single render,
         so the tab read "null: Create Database" and the log filled up. No
         business-tier call may be load-bearing on this layout. "Pressroom" is
         also the honest answer: site.shortName's own default is "Pressroom"
         (runtimeConfigDefs.xml), and pre-bootstrap there is no configured
         value to prefer over it. --%>
    <title>Pressroom: <spring:message code="${pageTitle}" text="${pageTitle}"/></title>
    <jsp:include page="${tile_head}"/>
    <style>
        <jsp:include page="${tile_styles}" />
    </style>
</head>
<body>

<jsp:include page="${tile_banner}"/>

<div class="container-fluid install-page">

    <div class="row">
        <div class="col-md-1"></div>
        <main class="col-md-10">
            <jsp:include page="${tile_messages}"/>
            <jsp:include page="${tile_content}"/>
        </main>
        <div class="col-md-1"></div>
    </div>

</div>

<footer class="footer">
    <div class="container-fluid">
        <jsp:include page="${tile_footer}"/>
    </div>
</footer>

</body>
</html>
