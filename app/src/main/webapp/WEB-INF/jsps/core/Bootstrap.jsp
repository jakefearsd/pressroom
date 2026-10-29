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

<%-- The tables are present and current but Roller is not running: either it
     has not been started yet (the installer's last step), or a start just
     failed (startFailed). Anonymous, and with installation.type=auto every
     public request lands here while Roller cannot start, so a failure names
     the problem and where to read about it, nothing more: the exception and
     its trace are in the server log only. Either way the one action is the
     same, starting Roller. --%>
<c:choose>
<c:when test="${startFailed}">
    <h1 class="pressroom-page-title"><spring:message code="installer.startFailed"/></h1>
    <p><spring:message code="installer.startFailedExplanation"/></p>
</c:when>
<c:otherwise>
    <h1 class="pressroom-page-title"><spring:message code="installer.notStarted"/></h1>
</c:otherwise>
</c:choose>

<jsp:include page="/WEB-INF/jsps/core/InstallStart.jsp"/>

<div class="mb-4"></div>
