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
 * Local draft recovery for the entry and page editors.
 *
 * Deliberately local: see docs/superpowers/specs/2026-08-13-w3-autosave-design.md.
 * The short version is that a server-side autosave would multiply
 * weblogentry_revision rows by the autosave rate against a retention default of
 * -1 (keep everything), and would have to invent a real entry row for anything
 * anyone started typing. The work that actually gets lost -- tab crash, wrong
 * tab closed, session expired while the laptop slept -- is all lost in the same
 * browser it was typed in, which is exactly what localStorage covers.
 *
 * Used in: EntryEdit.jsp, PageEdit.jsp. Loaded as a plain static script rather
 * than a JSP include (unlike ajax-user.js) because nothing here needs JSP
 * interpolation -- every translated string arrives through data- attributes on
 * the bar element.
 */
(function (window, document) {
    'use strict';

    var PREFIX = 'pressroom.draft.v1:';
    var DEBOUNCE_MS = 2000;
    var MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000;

    /*
     * localStorage is not merely "might be full": reading window.localStorage
     * at all throws when storage is blocked (some enterprise policies, some
     * private-browsing modes). Probe it once, and treat any failure as "this
     * page has no autosave" rather than letting it break the editor.
     */
    function openStorage() {
        try {
            var store = window.localStorage;
            var probe = PREFIX + 'probe';
            store.setItem(probe, '1');
            store.removeItem(probe);
            return store;
        } catch (e) {
            return null;
        }
    }

    /*
     * Fields that are identity or plumbing rather than content, by NAME -- not
     * by type="hidden", because bean.featuredImageId and bean.ogImageId are
     * hidden inputs carrying real author choices made through the image
     * pickers, and losing those on a recovery is exactly the sort of work that
     * is annoying to redo.
     *
     * Only the three universal ones live here. Anything Pressroom-page-specific
     * comes in through options.exclude, because the right answer differs
     * between the two editors and the module has no business guessing:
     *
     *   - the editor's own textarea (bean.text on entries, bean.content on
     *     pages) -- its authoritative value arrives through the getText/setText
     *     seam and is already captured as snapshot.text. Capturing it again
     *     doubles every snapshot and hands differs() a possibly-stale second
     *     copy of the same content to compare independently of the first.
     *   - bean.status ON ENTRIES ONLY. Restoring it there is inert
     *     (EntryEditController overwrites it from the submit button's
     *     formaction), but COMPARING it is not: doEntryEditSave mutates the
     *     bean and forwards, so the page rendered right after a successful
     *     Post carries PUBLISHED while the snapshot taken at submit holds
     *     DRAFT. differs() would see a difference, raise a phantom "unsaved
     *     changes" bar over a save that had just succeeded, and keep raising it
     *     on every later visit -- eventually offering days-old text over
     *     something newer. On PAGES bean.status must stay in: it is a visible
     *     <select> the author sets, and PageBean.copyTo writes it straight
     *     through.
     */
    function isStructural(el, csrfName, exclude) {
        if (!el.name) {
            return true;
        }
        if (el.type === 'file' || el.type === 'submit'
                || el.type === 'button' || el.type === 'reset') {
            return true;
        }
        if (el.name === csrfName || el.name === 'bean.id' || el.name === 'weblog') {
            return true;
        }
        for (var i = 0; i < exclude.length; i++) {
            if (el.name === exclude[i]) {
                return true;
            }
        }
        return false;
    }

    /*
     * Radios and checkboxes both let multiple inputs share a name (a radio
     * group, or a set of same-named checkboxes), so keying by name alone
     * would let the last one in the group overwrite the rest.
     */
    function fieldKey(el) {
        return (el.type === 'radio' || el.type === 'checkbox')
                ? el.name + '=' + el.value : el.name;
    }

    function readFields(form, csrfName, exclude) {
        var fields = {};
        var els = form.elements;
        for (var i = 0; i < els.length; i++) {
            var el = els[i];
            if (isStructural(el, csrfName, exclude)) {
                continue;
            }
            fields[fieldKey(el)] =
                (el.type === 'checkbox' || el.type === 'radio') ? !!el.checked : el.value;
        }
        return fields;
    }

    function writeFields(form, csrfName, exclude, fields) {
        var els = form.elements;
        for (var i = 0; i < els.length; i++) {
            var el = els[i];
            if (isStructural(el, csrfName, exclude)) {
                continue;
            }
            var key = fieldKey(el);
            if (!Object.prototype.hasOwnProperty.call(fields, key)) {
                continue;
            }
            if (el.type === 'checkbox' || el.type === 'radio') {
                el.checked = !!fields[key];
            } else {
                el.value = fields[key];
            }
            // Setting .value/.checked in script fires nothing, so anything the
            // page derives from a field stays stale: the SEO card's Event rows
            // keep hiding while the restored jsonLdType says EVENT, the
            // featured-image thumbnail keeps showing the server's pick. The
            // values submit correctly either way, but a Restore that visibly
            // does nothing to half the form reads as a Restore that failed.
            dispatch(el, 'change');
        }
    }

    /* A change event the page's own handlers will see, IE-compatible-ish. */
    function dispatch(el, type) {
        try {
            var event;
            if (typeof window.Event === 'function') {
                event = new window.Event(type, { bubbles: true });
            } else {
                event = document.createEvent('HTMLEvents');
                event.initEvent(type, true, false);
            }
            el.dispatchEvent(event);
        } catch (e) {
            /* a page handler that throws is not this module's problem */
        }
    }

    /*
     * Content comparison, not timestamps. A timestamp comparison against the
     * entry's updateTime has to reconcile a browser clock, a server clock and
     * the weblog's timezone, and is wrong whenever any of the three drift.
     * Either the browser is holding something the server is not, or it is not.
     */
    function differs(stored, current) {
        if (stored.text !== current.text) {
            return true;
        }
        var seen = {};
        var keys = Object.keys(stored.fields).concat(Object.keys(current.fields));
        for (var i = 0; i < keys.length; i++) {
            var key = keys[i];
            if (seen[key]) {
                continue;
            }
            seen[key] = true;
            // A field present on only one side compares via
            // String(undefined) ("undefined" vs. the other side's real
            // value), which reads as a difference. Deliberate, not a bug:
            // the failure mode is a spurious recovery offer when the form's
            // field set has changed since the snapshot was taken, and the
            // author just reads the bar and clicks Discard. Making this
            // lenient about missing keys would fail the other way --
            // masking a real difference and losing recoverable work -- so
            // do not "fix" this into a leniency check.
            if (String(stored.fields[key]) !== String(current.fields[key])) {
                return true;
            }
        }
        return false;
    }

    /*
     * Both editors name their title field bean.title. Hardcoded rather than
     * configurable because a title is the one field every editor here has, and
     * an option nobody would ever pass a different value for is just a way to
     * get it wrong.
     */
    function sameTitle(a, b) {
        return String(a.fields['bean.title']) === String(b.fields['bean.title']);
    }

    function readSnapshot(store, key) {
        var snapshot;
        try {
            var raw = store.getItem(key);
            if (!raw) {
                return null;
            }
            snapshot = JSON.parse(raw);
        } catch (e) {
            return null;
        }
        if (!snapshot || !snapshot.fields || typeof snapshot.text !== 'string') {
            return null;
        }
        return snapshot;
    }

    /*
     * Without this, every draft ever abandoned on this installation stays in
     * the browser forever. Best-effort: housekeeping must never be the reason
     * the editor fails to load.
     */
    function sweep(store) {
        try {
            var now = new Date().getTime();
            var doomed = [];
            for (var i = 0; i < store.length; i++) {
                var key = store.key(i);
                if (!key || key.indexOf(PREFIX) !== 0) {
                    continue;
                }
                var snapshot = readSnapshot(store, key);
                if (!snapshot || typeof snapshot.at !== 'number'
                        || (now - snapshot.at) > MAX_AGE_MS) {
                    doomed.push(key);
                }
            }
            for (var j = 0; j < doomed.length; j++) {
                store.removeItem(doomed[j]);
            }
        } catch (e) {
            /* nothing to do */
        }
    }

    function install(options) {
        if (!options || !options.form || !options.key
                || typeof options.getText !== 'function') {
            return;
        }
        var store = openStorage();
        if (!store) {
            return;
        }

        var form = options.form;
        var key = options.key;
        var csrfName = options.csrfName || '_csrf';
        var exclude = options.exclude || [];
        var bar = options.bar || null;
        var timer = null;
        var stopped = false;

        sweep(store);

        /*
         * options.getText() is the caller's editor read seam, and every
         * other operation in this module is guarded against failure -- this
         * one was not. A caller that installs before its editor is fully
         * constructed (CLAUDE.md already documents one such race, the
         * editor against the page's own ready handler) would have thrown a
         * TypeError straight out of install(), into the host page's
         * $(document).ready, aborting the rest of that handler. Returning
         * null here instead means a broken read seam disables the module for
         * this page rather than breaking the editor around it.
         */
        function current() {
            var text;
            try {
                text = options.getText();
            } catch (e) {
                return null;
            }
            return { at: 0, fields: readFields(form, csrfName, exclude), text: text };
        }

        function drop(which) {
            try {
                store.removeItem(which);
            } catch (e) {
                /* nothing to do */
            }
        }

        function save() {
            if (stopped) {
                return;
            }
            var snapshot = current();
            if (!snapshot) {
                return;
            }
            snapshot.at = new Date().getTime();
            try {
                store.setItem(key, JSON.stringify(snapshot));
            } catch (e) {
                // Quota exhausted mid-session. Stop trying rather than
                // throwing on every keystroke; the editor matters more than
                // the safety net.
                stopped = true;
                return;
            }
            // A save that actually landed, for anything else on the page
            // that cares -- today, the status line's word-count-and-save-state
            // indicator (Task A8), which listens on the bar element rather
            // than reaching into this module's internals.
            if (bar) {
                dispatch(bar, 'pressroom-draft:saved');
            }
        }

        function schedule() {
            if (timer) {
                window.clearTimeout(timer);
            }
            timer = window.setTimeout(save, DEBOUNCE_MS);
        }

        function offer(snapshot) {
            if (!bar) {
                return;
            }
            var label = bar.querySelector('.draft-bar-text');
            var restoreButton = bar.querySelector('.draft-bar-restore');
            var discardButton = bar.querySelector('.draft-bar-discard');
            if (!label || !restoreButton || !discardButton) {
                return;
            }

            var when = new Date(snapshot.at || new Date().getTime());
            label.textContent = (label.getAttribute('data-template') || '')
                    .replace('{0}', when.toLocaleString());

            restoreButton.addEventListener('click', function () {
                writeFields(form, csrfName, exclude, snapshot.fields);
                if (typeof options.setText === 'function') {
                    options.setText(snapshot.text);
                }
                bar.textContent = bar.getAttribute('data-restored') || '';
                // Re-snapshot rather than drop: writeFields/setText set
                // values programmatically and dispatch no input/change
                // event, so without this the restored content would exist
                // only in the DOM -- a crash right after clicking Restore
                // would lose exactly the work this feature exists to
                // protect. The restored state still differs from what the
                // server rendered, so it will be offered again on a later
                // reload; that is correct, since the work genuinely is still
                // unsaved, and the offer disappears the moment the author
                // actually saves.
                save();
            });

            discardButton.addEventListener('click', function () {
                drop(key);
                bar.hidden = true;
            });

            bar.hidden = false;
        }

        var now = current();
        if (!now) {
            return;
        }

        /*
         * The PRIMARY key is compared on the whole form: a snapshot matching
         * what the server just rendered is one whose save went through, so it
         * is dropped silently, and anything else is unsaved work worth
         * offering.
         *
         * A STALE key is compared on the editor TEXT ALONE, and that asymmetry
         * is load-bearing rather than lazy. A stale key names a snapshot
         * written by a DIFFERENT page whose form state legitimately differs:
         * saving a new entry redirects to entryEdit, where copyFrom() has
         * populated bean.status and bean.pubTimeLocal that were empty on the
         * add form. A whole-form comparison therefore NEVER matches, the
         * entryAdd:new snapshot survives its own save, and the next author to
         * open a blank editor is offered the previous entry's text -- the exact
         * failure staleKeys exists to prevent. A browser IT caught this; the
         * unit tests could not, because the difference only appears once a real
         * save has round-tripped through the controller.
         *
         * Text equality is the specific signal "this new-entry draft became
         * this entry", and it must not be loosened further: a genuinely
         * different new-entry draft sitting in another tab has different text
         * and must survive.
         *
         * Text alone is not enough for that, though, which is why the title
         * has to match too. A perfectly ordinary move -- open an entry, copy
         * its body, paste it into a new-entry tab, start on the title -- leaves
         * a new-entry snapshot whose text is byte-identical to the entry's, and
         * reloading the ENTRY tab would then consume and delete it. Two
         * independent fields matching is a much stronger signal that this
         * new-entry draft is the thing that became this entry; a body pasted
         * but not yet titled does not match, and survives.
         */
        var keys = [key].concat(options.staleKeys || []);
        var offerable = null;
        for (var i = 0; i < keys.length; i++) {
            var snapshot = readSnapshot(store, keys[i]);
            if (!snapshot) {
                drop(keys[i]);
            } else if (keys[i] !== key) {
                if (snapshot.text === now.text && sameTitle(snapshot, now)) {
                    drop(keys[i]);
                }
            } else if (differs(snapshot, now)) {
                offerable = snapshot;
            } else {
                drop(keys[i]);
            }
        }
        if (offerable) {
            offer(offerable);
        }

        form.addEventListener('input', schedule, true);
        form.addEventListener('change', schedule, true);
        if (typeof options.onEditorChange === 'function') {
            // Same reasoning as current() above: registering with the
            // caller's editor is not this module's to trust blindly, and a
            // throw here must not propagate out of install() and abort
            // whatever the host page's ready handler still has left to do.
            try {
                options.onEditorChange(schedule);
            } catch (e) {
                /* nothing to do */
            }
        }

        /*
         * Save on submit rather than clearing. Clearing here would delete the
         * draft in the one case it is most needed: an expired session turns
         * the POST into a redirect to the login page, and everything typed
         * since the last save would be gone. Saving instead costs nothing --
         * a submit that succeeds comes back rendering the saved values, and
         * the matching snapshot is dropped on that load by the rule above.
         */
        form.addEventListener('submit', function () {
            if (timer) {
                window.clearTimeout(timer);
                timer = null;
            }
            save();
        });
    }

    window.pressroomDraft = { install: install };

}(window, document));
