/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

/*
 * Double-submit guard for the slow, expensive or irreversible admin actions:
 * a bulk upload, a theme import, a crop, a newsletter send. A second click
 * before the first response arrives runs the whole operation twice, and for
 * the newsletter that means the list is mailed twice with no undo.
 *
 * Opt in per form: `class="guard-submit"` on the <form>, and optionally
 * `data-busy-label` on the button that should change text while it works.
 *
 * THE FORMACTION CAVEAT IS THE WHOLE REASON THIS FILE EXISTS RATHER THAN A
 * ONE-LINER. A submit button's name/value pair is only sent if the button is
 * enabled AT THE MOMENT THE BROWSER SERIALISES THE FORM. Disabling it inside
 * the submit handler therefore drops the name/value -- and both Entries.jsp
 * and Maintenance.jsp route entirely on which submit button was clicked
 * (`name="duplicateId"`, `formaction=...`), so the naive version silently
 * sends the wrong request. Deferring the disable to a setTimeout(0) lets the
 * default action commit first, then blocks the second click.
 *
 * It is deliberately a visual/interaction guard only: nothing here is a
 * substitute for the server refusing a duplicate. A user with script off, or
 * a doubled request from a flaky network, still reaches the controller.
 */
(function () {
    "use strict";

    function busy(button) {
        if (!button || button.disabled) {
            return;
        }
        var label = button.getAttribute("data-busy-label");
        if (label) {
            if (button.tagName === "INPUT") {
                button.value = label;
            } else {
                button.textContent = label;
            }
        }
        button.disabled = true;
        button.setAttribute("aria-busy", "true");
    }

    document.addEventListener("submit", function (event) {
        // pressroom.js's own submit listener answers a form-level data-confirm
        // FIRST (both are capturing listeners on document, so registration
        // order decides) and calls preventDefault() on Cancel. Without this
        // check the button still gets disabled on that same event, and
        // nothing ever re-enables it -- one Cancel permanently disables the
        // control, no submit ever having happened.
        if (event.defaultPrevented) {
            return;
        }
        var form = event.target;
        if (!form.classList || !form.classList.contains("guard-submit")) {
            return;
        }
        // submitter is the clicked control; older engines fall back to
        // whatever has focus, which is the same element in practice.
        var button = event.submitter || document.activeElement;
        if (!button || (button.type !== "submit" && button.type !== "image")) {
            return;
        }
        window.setTimeout(function () {
            busy(button);
        }, 0);
    }, true);
})();
