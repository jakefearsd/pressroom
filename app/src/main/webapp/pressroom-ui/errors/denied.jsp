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
<%-- Reached via RedirectController's "/access-denied.rol", which now
     returns the ".denied" view name so PressroomViewResolver wraps this
     fragment in the ".tiles-errorpage" layout (head/banner/footer, the same
     design-system CSS -- pressroom-tokens.css/pressroom.css -- every other admin
     page loads) instead of forwarding straight to this raw JSP. Deliberately
     does NOT use <fmt:message>/<spring:message>: those resolve locale from
     the request's Accept-Language (falling back to the server's default
     locale), which is how this used to render in German for a non-English
     client -- see 404.jsp's equivalent comment for the same fix applied to
     the container-level error pages. Hardcoded English. --%>
<div class="card card-body">
    <h1 class="pressroom-page-title" id="accessDenied">Access denied</h1>
    <p>You don't have permission to do that. The usual reasons:</p>
    <ul>
        <li>The entry belongs to a weblog you can't edit -- or you already
            submitted it for review, which hands it to a weblog admin.</li>
        <li>You submitted a stale page left over from an earlier sign-in
            under a different account. Reload and try again.</li>
        <li>You signed in with the wrong capitalization of your username.
            Sign out and back in with the correct one.</li>
    </ul>
    <p>If none of those fit, ask your site administrator.</p>
</div>
