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
<%-- Container-level error page (registered on HttpStatus.NOT_FOUND/BAD_REQUEST
     in boot/WebContainerConfig.java): reached for both a genuinely unknown
     admin URL and a rendering servlet's sendNotFound() (RenderingServletUtils),
     since both end in the container's own error dispatch. Deliberately does
     NOT use <fmt:message>/<spring:message>: those resolve locale from the
     request's Accept-Language (falling back to the server's default locale,
     which is how this used to render in German for a non-English client),
     and this page must also render correctly if the business tier or the
     Spring context never finished bootstrapping. Hardcoded English, no
     external stylesheet, no stack trace -- see the design sprint for a
     themed replacement; this is deliberately plain. --%>
<%-- session="false": an anonymous visitor's error page makes no session,
     so no Set-Cookie; and the home link is the context path, not a
     <c:url>, which rewrote it to /;jsessionid=... for crawlers to index. --%>
<%@ page session="false" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="jakarta.tags.functions" prefix="fn" %>
<!doctype html>
<html lang="en">
    <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="robots" content="noindex">
        <title>Page not found</title>
        <style>
            /* The quiet-instrument palette (roller-tokens.css), as literals:
               this page must render with no stylesheet and no Spring. */
            :root {
                color-scheme: light dark;
                --paper: #F7F9F9; --surface: #FFFFFF; --ink: #17262A;
                --ink-soft: #5A6E72; --line: #DCE4E4; --accent: #0F6E68;
            }
            @media (prefers-color-scheme: dark) {
                :root {
                    --paper: #131C1C; --surface: #1A2626; --ink: #DCE7E5;
                    --ink-soft: #8FA5A2; --line: #2A3838; --accent: #4FB3AA;
                }
            }
            body {
                margin: 0;
                padding: 4rem 1rem;
                background: var(--paper);
                color: var(--ink);
                font-family: "IBM Plex Sans", system-ui, sans-serif;
                line-height: 1.5;
            }
            .box {
                max-width: 32rem;
                margin: 0 auto;
                padding: 1.5rem;
                background: var(--surface);
                border: 1px solid var(--line);
                border-radius: 6px;
            }
            .code {
                margin: 0 0 .5rem;
                font-size: 12px;
                font-weight: 600;
                letter-spacing: .08em;
                text-transform: uppercase;
                color: var(--ink-soft);
            }
            h1 {
                margin: 0 0 .5rem;
                font-size: 1.25rem;
                font-weight: 600;
            }
            p {
                margin: 0 0 .75rem;
                color: var(--ink-soft);
            }
            a {
                color: var(--accent);
                font-weight: 600;
            }
        </style>
    </head>
    <body>
        <main class="box">
            <p class="code">404</p>
            <h1>Page not found</h1>
            <p>The page you requested could not be found on this server.</p>
            <%-- A 404 decided inside a weblog (PageServlet) leaves that
                 weblog's name and address on the request; the name is author
                 input, so both are escaped. --%>
            <c:choose>
            <c:when test="${not empty requestScope['roller.notFound.weblogUrl']}">
            <p><a href="${fn:escapeXml(requestScope['roller.notFound.weblogUrl'])}">Back to ${fn:escapeXml(requestScope['roller.notFound.weblogName'])}</a></p>
            </c:when>
            <c:otherwise>
            <p><a href="${fn:escapeXml(pageContext.request.contextPath)}/">Return to the home page</a></p>
            </c:otherwise>
            </c:choose>
        </main>
    </body>
</html>
