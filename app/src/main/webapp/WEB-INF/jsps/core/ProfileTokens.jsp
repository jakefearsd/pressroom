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

<%-- Profile -> API tokens (spec 2026-10-13, section 3; criterion 15).
     RouteSweepIT finds this page by #profileTokensPage. --%>
<div id="profileTokensPage">
    <p class="subtitle"><spring:message code="apiTokens.profile.subtitle"/></p>

    <h2 class="section-head"><spring:message code="apiTokens.live"/></h2>
    <jsp:include page="/WEB-INF/jsps/core/TokenList.jsp"/>

    <h2 class="section-head"><spring:message code="apiTokens.new"/></h2>
    <jsp:include page="/WEB-INF/jsps/core/TokenForm.jsp"/>
</div>
