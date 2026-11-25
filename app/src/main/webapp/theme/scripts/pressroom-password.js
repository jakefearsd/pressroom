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
 * Password fields: a show/hide button, so a person can see what a password
 * manager actually filled in (a stale saved password once cost an hour that
 * one glance would have saved), and a live match line under a confirm field.
 * Developer notes: docs/dev/admin-ui.md, "Password fields".
 *
 * Loaded only through WEB-INF/jsps/tiles/passwordFields.jsp, which carries
 * the translated labels as data-*-label attributes on this script tag. A
 * field opts in with data-reveal and must have an id.
 *
 * THE INPUT IS NEVER MOVED. The page wraps it in a .input-group and the
 * button is inserted after it. Moving the input (to wrap it here) would drop
 * focus: the sign-in page focuses the password box from an inline script
 * that runs before this one enhances, and the reset page uses autofocus.
 *
 * Every field starts hidden, and every revealed field in a form goes back to
 * type="password" when that form submits (capture phase, so before any other
 * handler), so browsers and password managers still see a password field.
 *
 * This is a convenience only. The server checks everything it checked before.
 */

/**
 * What the match line under a confirm field says: nothing ("empty") until
 * the confirm field has content, then "match" or "mismatch". Exact
 * comparison, as the server's. A global so the node test can reach it.
 */
function passwordMatchState(password, confirm) {
    "use strict";
    if (!confirm) {
        return "empty";
    }
    return password === confirm ? "match" : "mismatch";
}

(function () {
    "use strict";

    var script = document.currentScript;

    function label(name) {
        return (script && script.getAttribute("data-" + name + "-label")) || "";
    }

    var SHOW = label("show");
    var HIDE = label("hide");

    /** Each enhanced input with its button, for the submit-time reset. */
    var revealed = [];

    function setRevealed(pair, on) {
        pair.input.type = on ? "text" : "password";
        pair.button.textContent = on ? HIDE : SHOW;
        pair.button.setAttribute("aria-pressed", on ? "true" : "false");
    }

    function enhanceReveal(input) {
        if (!input.id || input.hasAttribute("data-reveal-ready")) {
            return;
        }
        input.setAttribute("data-reveal-ready", "");
        var button = document.createElement("button");
        button.type = "button";
        button.className = "btn btn-outline-secondary password-reveal";
        button.setAttribute("aria-controls", input.id);
        var pair = { input: input, button: button };
        setRevealed(pair, false);
        button.addEventListener("click", function () {
            setRevealed(pair, input.type === "password");
        });
        input.insertAdjacentElement("afterend", button);
        revealed.push(pair);
    }

    var MATCH = label("match");
    var MISMATCH = label("mismatch");

    /**
     * The live line under a confirm field (data-match="<id of the new
     * password>"). It goes after the field's .input-group so it sits below
     * the button, and is a polite live region so a screen reader hears it.
     */
    function enhanceMatch(confirm) {
        var password = document.getElementById(confirm.getAttribute("data-match"));
        if (!password || !confirm.id || confirm.hasAttribute("data-match-ready")) {
            return;
        }
        confirm.setAttribute("data-match-ready", "");
        var line = document.createElement("div");
        line.id = confirm.id + "-match";
        line.className = "form-text password-match";
        line.setAttribute("aria-live", "polite");
        var group = confirm.parentElement && confirm.parentElement.classList.contains("input-group")
            ? confirm.parentElement : confirm;
        group.insertAdjacentElement("afterend", line);

        function update() {
            var state = passwordMatchState(password.value, confirm.value);
            line.textContent = state === "match" ? MATCH : state === "mismatch" ? MISMATCH : "";
            line.classList.toggle("is-match", state === "match");
            line.classList.toggle("is-mismatch", state === "mismatch");
        }
        // input for typing; change for a password manager's fill.
        ["input", "change"].forEach(function (type) {
            password.addEventListener(type, update);
            confirm.addEventListener(type, update);
        });
        update();
    }

    function enhance() {
        Array.prototype.forEach.call(document.querySelectorAll("input[data-reveal]"), enhanceReveal);
        Array.prototype.forEach.call(document.querySelectorAll("input[data-match]"), enhanceMatch);
    }

    document.addEventListener("submit", function (event) {
        revealed.forEach(function (pair) {
            if (event.target.contains(pair.input)) {
                setRevealed(pair, false);
            }
        });
    }, true);

    // Leaving the page hides everything, or Back restores the page from the
    // back/forward cache with the password still showing.
    window.addEventListener("pagehide", function () {
        revealed.forEach(function (pair) {
            setRevealed(pair, false);
        });
    });

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", enhance);
    } else {
        enhance();
    }
}());
