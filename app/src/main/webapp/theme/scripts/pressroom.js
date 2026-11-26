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
/* This function is used to set cookies */
function setCookie(name, value, expires, path, domain, secure=true, sameSite=true) {
  document.cookie = name + "=" + escape (value) +
    ((expires) ? "; expires=" + expires.toGMTString() : "") +
    ((path) ? "; path=" + path : "") +
    ((domain) ? "; domain=" + domain : "") + ((secure) ? "; secure" : "") +
    ((sameSite) ? "; SameSite=Strict" : "");
}

/* This function is used to get cookies */
function getCookie(name) {
	var prefix = name + "=";
	var start = document.cookie.indexOf(prefix);

	if (start===-1) {
		return null;
	}

	var end = document.cookie.indexOf(";", start+prefix.length);
	if (end===-1) {
		end=document.cookie.length;
	}

	var value=document.cookie.substring(start+prefix.length, end);
	return unescape(value);
}

/* This function is used to delete cookies */
function deleteCookie(name,path,domain) {
  if (getCookie(name)) {
    document.cookie = name + "=" +
      ((path) ? "; path=" + path : "") +
      ((domain) ? "; domain=" + domain : "") +
      "; expires=Thu, 01-Jan-70 00:00:01 GMT";
  }
}

/*
 * Confirmation prompts, driven by a data-confirm attribute rather than an
 * inline onclick/onsubmit handler.
 *
 * THE INLINE FORM FAILS OPEN, WHICH IS WHY IT IS BANNED HERE. Writing
 * onclick="return confirm('${fn:escapeXml(msg)}')" puts an HTML escape into a
 * JS-string position: the HTML parser decodes &#039; back to a literal
 * apostrophe BEFORE the JS is compiled, so one apostrophe anywhere in the
 * message -- a translated value, or an address like o'brien@example.com --
 * terminates the string, the handler fails to compile, and the click proceeds
 * WITH NO CONFIRMATION AT ALL. The destructive action just happens.
 *
 * In an attribute value there is no second parser: fn:escapeXml is the
 * correct escape for that position, the browser hands dataset.confirm the
 * exact literal text, and quotes and apostrophes are simply characters.
 *
 * Both events are handled, and THE DIVISION BETWEEN THEM IS LOAD-BEARING: a
 * click prompt and a submit prompt for the same action means two dialogs for
 * one click, and the second one reads like a bug to the operator.
 *
 *   - click  owns data-confirm on a CONTROL: the button that routes by
 *            formaction (Maintenance), a link, anything inside a form.
 *   - submit owns data-confirm on the FORM itself, which is the only way to
 *            catch a submit with no click behind it (Enter in a text field).
 *
 * The boundary is enforced in one place: the click handler's walk up the
 * ancestors STOPS at the form. Without that stop it finds a form-level
 * attribute, prompts, allows the native submit, and the submit handler --
 * seeing the same attribute -- prompts again. That is not hypothetical; it
 * shipped for one round on UserEdit's send-password-link form.
 *
 * Capture phase, so this runs before any other handler commits to the action.
 */
(function () {
    "use strict";

    function confirmed(element) {
        var message = element.getAttribute("data-confirm");
        return !message || window.confirm(message);
    }

    // Deliberately stops BEFORE the form: a form's own data-confirm belongs to
    // the submit handler, and answering it here too would prompt twice.
    function nearestConfirmableControl(node) {
        while (node && node.nodeType === 1 && node.tagName !== "FORM") {
            if (node.hasAttribute && node.hasAttribute("data-confirm")) {
                return node;
            }
            node = node.parentNode;
        }
        return null;
    }

    document.addEventListener("click", function (event) {
        var target = nearestConfirmableControl(event.target);
        if (target && !confirmed(target)) {
            event.preventDefault();
            event.stopPropagation();
        }
    }, true);

    document.addEventListener("submit", function (event) {
        // Only the form's OWN attribute, never a descendant's: a control's
        // prompt was already answered by the click handler above, which is
        // why that handler stops at the form and this one does not climb.
        var form = event.target;
        if (form.hasAttribute && form.hasAttribute("data-confirm") && !confirmed(form)) {
            event.preventDefault();
            event.stopPropagation();
        }
    }, true);
})();

/*
 * Signing out clears this browser's autosaved drafts (pressroom-draft.js,
 * keys "pressroom.draft.v1:..."). localStorage belongs to the browser, not
 * to the account, and keeps a draft for 30 days: on a shared machine the
 * next person to sign in should not find the last one's unsaved entries in
 * storage. The keys also carry the user's id, so a session that simply
 * expired never offers one member another's draft; this is the clean-up for
 * the sign-out that is deliberate. Everything else in storage is kept.
 *
 * Delegated, like data-confirm above: the Logout link (bannerStatus.jsp) is
 * on every admin page, and this file is the one every admin page loads. A
 * matrix ";jsessionid=..." that c:url adds without cookies still matches.
 */
document.addEventListener('click', function (event) {
    var link = event.target && event.target.closest ? event.target.closest('a[href]') : null;
    if (!link || !/\/pressroom-ui\/logout\.rol(?:[;?#]|$)/.test(link.getAttribute('href') || '')) {
        return;
    }
    try {
        var store = window.localStorage;
        var doomed = [];
        for (var i = 0; i < store.length; i++) {
            var key = store.key(i);
            if (key && key.indexOf('pressroom.draft.v1:') === 0) {
                doomed.push(key);
            }
        }
        doomed.forEach(function (key) { store.removeItem(key); });
    } catch (e) {
        /* storage blocked: then nothing was ever stored either */
    }
});
