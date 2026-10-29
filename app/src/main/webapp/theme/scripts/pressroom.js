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

/* This function is used to show/hide elements with a display:none style attribute */
function toggle(targetId) {
    if (document.getElementById) {
        target = document.getElementById(targetId);
    	if (target.style.display === "none") {
    		target.style.display = "";
    	} else {
    		target.style.display = "none";
    	}
    }
}

/* The toggleFolder and togglePlusMinus functions are for expanding/contracting folders */
function toggleFolder(targetId) {
    var expanded;
    if (document.getElementById) {
        target = document.getElementById(targetId);
    	if (target.style.display === "none") {
    		target.style.display = "";
            expanded = true;
    	} else {
    		target.style.display = "none";
            expanded = false;
    	}
        togglePlusMinus("i" + targetId);

        // set a cookie to remember this preference
        var expires = new Date();
        expires.setTime(expires.getTime() + 24 * 365 * 60 * 60 * 1000); // sets it for approx 365 days.
        setCookie("rfolder-"+targetId,expanded,expires,"/");
    }
}

function togglePlusMinus(targetId) {
    if (document.getElementById) {
        target = document.getElementById(targetId);
    	if (target.innerHTML === "+") {
    		target.innerHTML = "-";
    	} else {
    		target.innerHTML = "+";
    	}
    }
}

/* This function is to set folders to expand/contract based on a user's preference */
function folderPreference(folderId) {
    var folderCookie = getCookie("rfolder-"+folderId);
    if (folderCookie != null) { // we have user's last setting
        var folder = document.getElementById(folderId);
        var plusMinus = document.getElementById("i"+folderId);
        if (folderCookie === "true") { // show
            folder.style.display = "";
            plusMinus.innerHTML = "-";
        } else { // hide
            folder.style.display = "none";
            plusMinus.innerHTML = "+";
        }
    }
}

function toggleNextRow(e) {
    var checked;
    if (e.type === "checkbox") {
        checked = e.checked;
    } else if (e.type === "radio") {
        var v = e.value;
        checked = (v === "1" || v === "y" || v === "true");
    }
    // var nextRow = e.parentNode.parentNode.nextSibling;
    // the above doesn't work on Mozilla since it treats white space as nodes
    var thisRow = e.parentNode.parentNode;
    var tableBody = thisRow.parentNode;
    var nextRow = tableBody.getElementsByTagName("tr")[thisRow.rowIndex+1];

    if (checked === true) {
        nextRow.style.display = "";
    } else {
        nextRow.style.display = "none";
    }
}

function toggleControl(toggleId, targetId) {
    var expanded;
    if (document.getElementById) {
        target = document.getElementById(targetId);
        toggle = document.getElementById(toggleId);
    	if (target.style.display === "none") {
    		target.style.display = "";
            expanded = true;

    	} else {
    		target.style.display = "none";
            expanded = false;
    	}
        togglePlusMinus("i" + targetId);

        // set a cookie to remember this preference
        var expires = new Date();
        expires.setTime(expires.getTime() + 24 * 365 * 60 * 60 * 1000); // sets it for approx 365 days.
        setCookie("control_"+targetId,expanded,expires,"/");
    }
}

function isblank(s) {
   for (var i=0; i<s.length; s++) {
      var c = s.charAt(i);
      if ((c!==' ') && (c!=='\n') && (c!=='')) return false;
   }
    return true;
}

// Show the document's title on the status bar
window.defaultStatus=document.title;

// Toggle check boxes
function toggleFunctionAll(toggleValue) {
	var inputs = document.getElementsByTagName('input');
	for(var i = 0; i < inputs.length ; i++) {
		if(inputs[i].name !== "control" && inputs[i].type === 'checkbox' && inputs[i].disabled === false ) {
			if (inputs[i].checked === true){
				inputs[i].checked = !inputs[i].checked;
			} else{
				inputs[i].checked = toggleValue;
			}
		}
	}
}

function toggleFunction(toggleValue,name) {
	var inputs = document.getElementsByName(name);
	for(var i = 0; i < inputs.length ; i++) {
		if(inputs[i].type === 'checkbox' && inputs[i].disabled === false) {
           inputs[i].checked = toggleValue;
		}
	}
}

function isValidUrl(url) {
    return /^(http|https|ftp):\/\/[a-z0-9]+([\-\.]{1}[a-z0-9]+)*\.[a-z]{2,5}(:[0-9]{1,5})?(\/.*)?$/i.test(url);
}

function validateEmail(email) {
    var re = /^(([^<>()\[\]\\.,;:\s@"]+(\.[^<>()\[\]\\.,;:\s@"]+)*)|(".+"))@((\[[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}])|(([a-zA-Z\-0-9]+\.)+[a-zA-Z]{2,}))$/;
    return re.test(email);
}
// This file is also loaded on public weblog pages (see the comment form macro
// in weblog.vm) purely for the cookie/validateComments helpers above, and those
// pages never load jQuery or jquery-validate. Guard the jQuery-validate wiring
// below -- which only targets the admin "validate-form"/"validate-email"
// classes (see CreateWeblog.jsp) -- so it does not throw on pages without jQuery.
if (typeof jQuery !== "undefined") {
    $(document).ready(function () {
        jQuery("form.validate-form").validate();
        // Email fields get jquery-validate's own email rule and nothing more.
        //
        // There used to be an extra regex here requiring the top-level domain
        // to be 2-4 characters. That silently rejected every address at a
        // modern TLD -- .photography, .gallery, .travel, .studio, .email --
        // and there was no way for the person typing to tell why, because the
        // message just said the address was invalid. It also blocked the whole
        // form: jquery-validate refuses to submit, so a weblog simply could
        // not be created with such an address.
        jQuery( ".validate-email" ).rules( "add", {
            minlength: 3,
            maxlength: 255,
            email: true
        });
    });
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
 * A form-level data-confirm may also carry data-confirm-when, a CSS selector
 * evaluated against the form itself: the prompt fires only when the form
 * currently has a match. Members.jsp is the one caller -- whether removing a
 * member needs confirming depends on which of several radios across the whole
 * table is checked, a decision no single control can answer for itself, so
 * this is the one legitimate reason for a form-level (rather than
 * per-control) data-confirm.
 *
 * Capture phase, so this runs before any other handler commits to the action.
 */
(function () {
    "use strict";

    function confirmed(element) {
        var message = element.getAttribute("data-confirm");
        return !message || window.confirm(message);
    }

    // A form's own data-confirm is unconditional unless it also names
    // data-confirm-when, in which case it only applies while the form has a
    // live match for that selector.
    function formNeedsConfirming(form) {
        var when = form.getAttribute("data-confirm-when");
        return !when || form.querySelector(when) !== null;
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
        if (form.hasAttribute && form.hasAttribute("data-confirm")
                && formNeedsConfirming(form) && !confirmed(form)) {
            event.preventDefault();
            event.stopPropagation();
        }
    }, true);
})();

// Selection bars: shown while any checkbox in the form named by
// data-selection-bar is checked; the count text comes from the bar's own
// data-template ("{0} selected"), so the string stays in the bundle.
document.addEventListener('change', function (event) {
    var box = event.target;
    if (!(box instanceof HTMLInputElement) || box.type !== 'checkbox') { return; }
    var form = box.form;
    if (!form) { return; }
    var bar = document.querySelector('.selection-bar[data-selection-bar="' + form.id + '"]');
    if (!bar) { return; }
    var checked = form.querySelectorAll('input[type=checkbox]:checked:not([data-select-all])').length;
    bar.hidden = checked === 0;
    var count = bar.querySelector('.selection-count');
    if (count) { count.textContent = count.dataset.template.replace('{0}', checked); }
});

/*
 * A control marked data-submit-on-change submits its own form the moment its
 * value changes -- the Entries sidebar's sort <select> is the first caller.
 *
 * Delegated and attribute-driven for the same reason data-confirm is (see the
 * long comment above): an inline onchange="this.form.submit()" is JavaScript
 * living in an HTML attribute, where the HTML parser has already decoded
 * whatever the JSP escaped before the script ever compiles.
 *
 * requestSubmit(), not submit(): the native submit() method skips the form's
 * own submit handlers, which on this page would step around the data-confirm
 * prompt above and any future guard registered the same way.
 */
document.addEventListener('change', function (event) {
    var control = event.target;
    if (!control || !control.hasAttribute || !control.hasAttribute('data-submit-on-change')) { return; }
    var form = control.form || (control.closest && control.closest('form'));
    if (!form) { return; }
    if (form.requestSubmit) {
        form.requestSubmit();
    } else {
        form.submit();
    }
});

/*
 * Validation errors point at the field they name.
 *
 * A controller that refuses a value calls BaseController.addFieldError, which
 * records the control's DOM id alongside the message; the three admin layouts
 * render the joined ids as <body data-invalid-fields="a b c">. This marks each
 * one and focuses the first, so a form with thirty fields does not leave the
 * author hunting for whichever box the banner is about.
 *
 * Every step is null-guarded because THIS FILE ALSO LOADS ON PUBLIC WEBLOG
 * PAGES (see the jQuery guard above), where no layout ever writes the
 * attribute -- a page with nothing wrong must be a silent no-op, not a
 * console error on every blog post.
 *
 * The class and the aria state are set together on purpose: .is-invalid is
 * the red border a sighted reader sees (pressroom.css defines it in terms of
 * --bad), aria-invalid is the same fact for a screen reader, and shipping one
 * without the other means the marker exists for only half the audience.
 */
document.addEventListener('DOMContentLoaded', function () {
    var body = document.body;
    if (!body || !body.dataset) { return; }
    var ids = body.dataset.invalidFields;
    if (!ids) { return; }

    var first = null;
    ids.trim().split(/\s+/).forEach(function (id) {
        if (!id) { return; }
        var field = document.getElementById(id);
        // A field that is not on this page is not an error: a validation may
        // name a control the current branch of the form did not render (the
        // template Action select only exists once actions are available).
        if (!field) { return; }
        field.classList.add('is-invalid');
        field.setAttribute('aria-invalid', 'true');
        if (!first) { first = field; }
    });

    // Scrolling is wanted, not suppressed -- the refused field is frequently
    // below the fold on a long settings form, which is the whole reason the
    // banner alone was not enough. This runs on DOMContentLoaded, so it wins
    // over any autofocus attribute the page carries, which is correct: the
    // field that needs fixing outranks the field you would start a fresh form
    // in.
    if (first) { first.focus({ preventScroll: false }); }
});
