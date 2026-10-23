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
<%--
  The session-expiry banner, as a <jsp:include> for the source editors
  (TemplateEdit.jsp, StylesheetEdit.jsp). It is the same bar and the same
  arithmetic PageEdit.jsp and EntryEdit.jsp carry inline: an author can spend
  an hour on a template or a stylesheet as easily as on an entry, and a save
  that lands on the login screen loses the lot. Purely local arithmetic
  against the container's own maxInactiveInterval -- no endpoint, no polling,
  nothing that would itself keep the session alive. The timer restarts on any
  input, so it measures inactivity the same way the container does; clock
  drift can only make it warn EARLY, the safe direction.

  Include it where the bar should appear, above the field being edited.
--%>
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<div id="sessionExpiryBar" class="draft-bar" hidden role="status" aria-live="polite"
     data-timeout="${pageContext.session.maxInactiveInterval}">
    <span class="draft-bar-text"><spring:message code="session.expiringSoon"/></span>
</div>

<script>
    document.addEventListener('DOMContentLoaded', function () {
        var bar = document.getElementById('sessionExpiryBar');
        if (!bar) {
            return;
        }
        var timeout = parseInt(bar.dataset.timeout, 10);
        if (!(timeout > 180)) {
            // A session shorter than the warning lead time would show the
            // banner permanently, which teaches people to ignore it.
            return;
        }
        var warnAfterMs = (timeout - 120) * 1000;
        var timer = null;
        var arm = function () {
            bar.hidden = true;
            window.clearTimeout(timer);
            timer = window.setTimeout(function () {
                bar.hidden = false;
            }, warnAfterMs);
        };
        ['keydown', 'click', 'input'].forEach(function (type) {
            document.addEventListener(type, arm, true);
        });
        arm();
    });
</script>
