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

<nav class="navbar navbar-expand-md pressroom-topbar">
    <div class="container-fluid">

        <%-- A <span>, not an <a href="#">: during installation there is no
             home to link to, and an anchor that navigates nowhere is a
             control a keyboard user can tab to and activate for nothing. --%>
        <span class="navbar-brand"><spring:message code="installer.bannerTitleLeft"/></span>

        <button type="button" class="navbar-toggler collapsed"
                data-bs-toggle="collapse" data-bs-target="#navbar" aria-expanded="false" aria-controls="navbar"
                aria-label="<spring:message code='generic.toggle'/>">
            <span class="visually-hidden"><spring:message code="generic.toggle"/></span>
            <span class="navbar-toggler-icon"></span>
        </button>

        <div id="navbar" class="navbar-collapse collapse">

            <ul class="navbar-nav">
            </ul>

            <ul class="navbar-nav ms-auto">
                <li class="nav-item"><a class="nav-link" href="<c:url value='/'/>"><spring:message code="installer.bannerTitleRight"/></a></li>
            </ul>

        </div>
    </div>
</nav>
