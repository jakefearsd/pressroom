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

<%-- A blog's Traffic page. Everything is read from the local snapshot by
     TrafficController; the fragment below is shared with the Global page. The
     blog name is author text and message arguments are not escaped, so it is
     escaped here. --%>
<div id="trafficPage">
    <p class="subtitle">
        <spring:message code="traffic.subtitle" arguments="${[fn:escapeXml(actionWeblog.name)]}"/>
    </p>

    <jsp:include page="/WEB-INF/jsps/editor/TrafficBody.jsp"/>
</div>
