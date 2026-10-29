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
<%@ page import="com.wikantik.pressroom.ui.core.util.menu.Menu" %>

<%-- Extract the menu from the request. The model attribute "menu" may collide
     with other attributes during request dispatching, so we safely cast it.
     Shared (via translation-time include) by tiles-tabbedpage.jsp and
     tiles-mainmenupage.jsp -- both render the same nav-rail markup from the
     "navMenu" this sets, so the extraction must not drift between them. --%>
<%
    Object menuObj = request.getAttribute("menu");
    Menu navMenu = (menuObj instanceof Menu) ? (Menu) menuObj : null;
    request.setAttribute("navMenu", navMenu);
%>
