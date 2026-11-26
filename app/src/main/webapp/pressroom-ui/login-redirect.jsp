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
<%@ page language="java" contentType="text/html; charset=UTF-8" %>
<%@ page import="com.wikantik.pressroom.business.*" %>
<%@ page import="com.wikantik.pressroom.pojos.*" %>
<%@ page import="com.wikantik.pressroom.ui.core.PressroomSession" %>
<%@ page import="org.springframework.web.context.support.WebApplicationContextUtils" %>
<%
// The session factory and the user lookup take their collaborators since the
// DI wave (plan Task 6b); a scriptlet reaches the provider bean through the
// web application context the way a bean would be injected.
//
// Signed in: the React admin's home, where every sign-in lands (the main menu
// it replaced was the landing for anyone with more than one weblog; the
// one-weblog shortcut to New entry went with it). Not signed in: the login page.
WebloggerProvider provider = WebApplicationContextUtils
        .getRequiredWebApplicationContext(application).getBean(WebloggerProvider.class);
Weblogger weblogger = provider.getWeblogger();
User user = PressroomSession.getPressroomSession(request, provider)
        .getAuthenticatedUser(weblogger.getUserManager());

if (user == null) {
    response.sendRedirect(request.getContextPath()+"/pressroom-ui/login.rol");
} else {
    response.sendRedirect(request.getContextPath()+"/pressroom-ui/app/");
}
%>
