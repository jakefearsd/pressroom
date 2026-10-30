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

<%-- Anonymous, and with installation.type=auto every public request lands
     here while Pressroom cannot start: so the page names the problem and where
     to read about it, nothing more. The startup log (the JDBC URL and user),
     the exception and its trace are in the server log only. --%>
<h1 class="pressroom-page-title"><spring:message code="installer.cannotConnectToDatabase" /></h1>

<p><spring:message code="installer.detailsInServerLog" /></p>

<div class="mb-4"></div>
