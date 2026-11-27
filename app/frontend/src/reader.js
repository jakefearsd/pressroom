/* The signed-in reader's client (0.18.0, Part E): built to
   pressroom-ui/scripts/pressroom-reader.js and loaded, deferred and
   versioned, only into a signed-in page (weblog.vm, the showReaderScript
   macro), which is already rendered and cached per user. A signed-out page
   carries only the small inline stub that keeps a Save or Follow intent and
   shows the provider's button, so anonymous pages stay small and shared.

   It closes the "You" menu on an outside click or Escape; then, on a page
   with the reader data element ([data-reader][data-signed-in]), it asks
   reader.rol once for the page's state, completes the stored intent once
   and confirms it inline (after a follow, "Also email me new posts" where
   offered), toggles Save and Follow, fills Continue reading, From blogs you
   follow, the Read marks and the server's Places, and posts one read after
   ten visible seconds. Every server string goes in with textContent and
   every link must be a path on this site. A plain script, not a module:
   app/frontend/test/reader.test.mjs runs this file as the browser does. */
(function () {
    // The "You" menu: closed by a click outside it or by Escape, as the blogs
    // menu is. Bound once per menu, whatever the page.
    Array.prototype.forEach.call(document.querySelectorAll('details.you-menu'), function (menu) {
        document.addEventListener('click', function (e) {
            if (menu.open && !menu.contains(e.target)) {
                menu.open = false;
            }
        });
        menu.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && menu.open) {
                menu.open = false;
                menu.querySelector('summary').focus();
            }
        });
    });

    var data = document.querySelector('[data-reader][data-signed-in]');
    if (!data) {
        return;
    }
    var endpoint = data.getAttribute('data-endpoint');
    var KEY = 'pressroom.intent';
    var MAX_AGE = 30 * 60 * 1000;
    var VISIBLE_MS = 10000;
    var saves = Array.prototype.slice.call(document.querySelectorAll('[data-reader-save]'));
    var follows = Array.prototype.slice.call(document.querySelectorAll('[data-reader-follow]'));

    function words(name) {
        return data.getAttribute('data-msg-' + name) || '';
    }

    // A link from the server must stay on this site: a path, on this origin.
    function sameOrigin(url) {
        if (typeof url !== 'string' || url.charAt(0) !== '/') {
            return false;
        }
        try {
            return new URL(url, window.location.href).origin === window.location.origin;
        } catch (e) {
            return false;
        }
    }

    function element(tag, className, text) {
        var node = document.createElement(tag);
        if (className) {
            node.className = className;
        }
        if (text) {
            node.textContent = text;
        }
        return node;
    }

    if (!sameOrigin(endpoint)) {
        return;
    }

    // The intent a signed-out click left (the inline stub, weblog.vm's
    // showReaderScript macro) is read and removed in one step, BEFORE anything
    // is posted: a reload, a second tab or a failed post can never repeat it.
    // One older than half an hour is simply dropped.
    function takeIntent() {
        var raw = null;
        try {
            raw = window.sessionStorage.getItem(KEY);
            window.sessionStorage.removeItem(KEY);
        } catch (e) {
            return null;
        }
        var intent = null;
        try {
            intent = JSON.parse(raw || 'null');
        } catch (e) {
            return null;
        }
        if (!intent || typeof intent.at !== 'number' || Date.now() - intent.at > MAX_AGE) {
            return null;
        }
        if (intent.action === 'save' && typeof intent.entry === 'string') {
            return intent;
        }
        if (intent.action === 'follow' && typeof intent.weblog === 'string') {
            return intent;
        }
        return null;
    }

    function note(control, text) {
        var line = control.querySelector('.reader-note');
        if (!line) {
            line = element('span', 'reader-note');
            line.setAttribute('role', 'status');
            control.appendChild(line);
        }
        line.textContent = text;
        return line;
    }

    // ------------------------------------------------------------ signed in
    var intent = takeIntent();
    var state = null;
    var waiting = [];

    function readJson(response) {
        var type = response.headers.get('Content-Type') || '';
        return type.indexOf('application/json') === -1 ? Promise.resolve(null) : response.json();
    }

    // Every write, with the CSRF header the state answer named; resolves to
    // the status and the JSON body (or null).
    function send(payload, keepalive) {
        var headers = { 'Accept': 'application/json', 'Content-Type': 'application/json' };
        headers[state.csrf.header] = state.csrf.token;
        return fetch(endpoint, { method: 'POST', credentials: 'same-origin', headers: headers,
            body: JSON.stringify(payload), keepalive: !!keepalive })
            .then(function (response) {
                return readJson(response).then(function (body) {
                    return { status: response.status, body: body };
                }, function () {
                    return { status: response.status, body: null };
                });
            });
    }

    function whenReady(work) {
        if (state) {
            work();
        } else {
            waiting.push(work);
        }
    }

    function press(control, on) {
        var button = control.querySelector('button');
        if (!button) {
            return;
        }
        button.setAttribute('aria-pressed', on ? 'true' : 'false');
        button.textContent = control.getAttribute(on ? 'data-label-on' : 'data-label-off');
    }

    function saveControlsFor(entry) {
        return saves.filter(function (c) { return c.getAttribute('data-entry') === entry; });
    }

    function followControlsFor(handle) {
        return follows.filter(function (c) { return c.getAttribute('data-weblog') === handle; });
    }

    function setSaved(entry, on, confirmOn) {
        return send({ action: on ? 'save' : 'unsave', entry: entry }).then(function (answer) {
            var ok = answer.status === 200;
            saveControlsFor(entry).forEach(function (control) {
                if (ok) {
                    press(control, on);
                }
                if (control === confirmOn) {
                    note(control, ok ? (on ? words('save-done') : '') : words('failed'));
                }
            });
        }, function () {
            if (confirmOn) {
                note(confirmOn, words('failed'));
            }
        });
    }

    function offerNewsletter(control, handle) {
        if (!state.newsletter || state.newsletter.available !== true) {
            return;
        }
        var offer = element('button', 'reader-newsletter', words('newsletter'));
        offer.type = 'button';
        offer.setAttribute('data-umami-event', 'newsletter');
        offer.setAttribute('data-umami-event-via', 'follow');
        offer.addEventListener('click', function () {
            offer.disabled = true;
            send({ action: 'subscribe', weblog: handle }).then(function (answer) {
                var outcome = answer.body && answer.body.outcome;
                var text = outcome === 'confirm' ? words('newsletter-confirm')
                    : outcome === 'already' ? words('newsletter-already')
                        : outcome === 'unavailable' ? words('newsletter-unavailable') : words('failed');
                offer.remove();
                note(control, text);
            }, function () {
                offer.disabled = false;
                note(control, words('newsletter-unavailable'));
            });
        });
        control.appendChild(offer);
    }

    function setFollowing(handle, on, confirmOn) {
        return send({ action: on ? 'follow' : 'unfollow', weblog: handle }).then(function (answer) {
            var ok = answer.status === 200;
            followControlsFor(handle).forEach(function (control) {
                if (ok) {
                    press(control, on);
                }
                var offer = control.querySelector('.reader-newsletter');
                if (offer && !on) {
                    offer.remove();
                }
                if (control === confirmOn) {
                    note(control, ok ? (on ? words('follow-done') : '') : words('failed'));
                    if (ok && on) {
                        offerNewsletter(control, handle);
                    }
                }
            });
        }, function () {
            if (confirmOn) {
                note(confirmOn, words('failed'));
            }
        });
    }

    function wireSignedIn() {
        saves.forEach(function (control) {
            var button = control.querySelector('button');
            button.addEventListener('click', function () {
                setSaved(control.getAttribute('data-entry'), button.getAttribute('aria-pressed') !== 'true', control);
            });
        });
        follows.forEach(function (control) {
            var button = control.querySelector('button');
            button.addEventListener('click', function () {
                setFollowing(control.getAttribute('data-weblog'), button.getAttribute('aria-pressed') !== 'true',
                    control);
            });
        });
    }

    // The rows: Continue reading and From blogs you follow, each hidden
    // while it would be empty. Titles and blog names are text, links paths.
    function fillRows() {
        var rows = document.querySelectorAll('[data-reader-row]');
        Array.prototype.forEach.call(rows, function (row) {
            var kind = row.getAttribute('data-reader-row');
            var items = kind === 'continue' ? state.continueReading : state.followedPosts;
            var list = row.querySelector('ul');
            if (!Array.isArray(items) || !list) {
                return;
            }
            items.forEach(function (item) {
                if (!item || !sameOrigin(item.url)) {
                    return;
                }
                var li = element('li', 'reader-row-item');
                var link = element('a', 'reader-row-title', item.title);
                link.href = item.url;
                li.appendChild(link);
                li.appendChild(element('span', 'reader-row-blog', item.weblogName));
                list.appendChild(li);
            });
            row.hidden = list.children.length === 0;
        });
    }

    // A small Read label after each list title the reader has read.
    function markRead() {
        if (!Array.isArray(state.readIds)) {
            return;
        }
        var read = {};
        state.readIds.forEach(function (id) { read[id] = true; });
        var titles = document.querySelectorAll('[data-reader-entry]');
        Array.prototype.forEach.call(titles, function (title) {
            var next = title.nextElementSibling;
            if (read[title.getAttribute('data-reader-entry')] === true
                    && !(next && next.classList.contains('reader-read-mark'))) {
                title.insertAdjacentElement('afterend', element('span', 'reader-read-mark', words('read')));
            }
        });
    }

    // Places you've been, from the reader's history on the server: the same
    // on every device. The browser's own list is not read or written here.
    function fillPlaces() {
        if (!Array.isArray(state.places)) {
            return;
        }
        var here = data.getAttribute('data-weblog');
        var others = state.places.filter(function (p) {
            return p && p.handle !== here && sameOrigin(p.url);
        }).slice(0, 5);
        var lists = document.querySelectorAll('[data-places]');
        Array.prototype.forEach.call(lists, function (container) {
            if (!others.length || container.querySelector('ul')) {
                return;
            }
            var ul = element('ul', 'places-been-list');
            others.forEach(function (p) {
                var li = element('li');
                var link = element('a', null, p.name);
                link.href = p.url;
                li.appendChild(link);
                ul.appendChild(li);
            });
            container.appendChild(ul);
            container.hidden = false;
        });
    }

    // The read beacon: one read per page view, after ten seconds the page has
    // been visible (time in a background tab does not count).
    function startBeacon() {
        var entry = data.getAttribute('data-read-entry');
        if (!entry) {
            return;
        }
        var seen = 0;
        var since = 0;
        var timer = null;
        var sent = false;
        function fire() {
            timer = null;
            sent = true;
            document.removeEventListener('visibilitychange', change);
            whenReady(function () {
                send({ action: 'read', entry: entry }, true).catch(function () {});
            });
        }
        function change() {
            if (sent) {
                return;
            }
            if (document.visibilityState === 'visible') {
                if (!timer) {
                    since = Date.now();
                    timer = setTimeout(fire, Math.max(0, VISIBLE_MS - seen));
                }
            } else if (timer) {
                clearTimeout(timer);
                timer = null;
                seen += Date.now() - since;
            }
        }
        document.addEventListener('visibilitychange', change);
        change();
    }

    function complete() {
        if (!intent) {
            return;
        }
        if (intent.action === 'save') {
            setSaved(intent.entry, true, saveControlsFor(intent.entry)[0] || null);
        } else {
            var controls = followControlsFor(intent.weblog);
            var at = controls.filter(function (c) { return c.getAttribute('data-via') === intent.via; })[0];
            setFollowing(intent.weblog, true, at || controls[0] || null);
        }
    }

    var query = [];
    if (saves.length) {
        query.push('entry=' + encodeURIComponent(saves[0].getAttribute('data-entry')));
    }
    if (follows.length) {
        query.push('weblog=' + encodeURIComponent(follows[0].getAttribute('data-weblog')));
    }
    var include = [];
    if (document.querySelector('[data-reader-row="continue"]')) {
        include.push('continue');
    }
    if (document.querySelector('[data-reader-row="followed"]')) {
        include.push('followed');
    }
    if (document.querySelector('[data-reader-entry]')) {
        include.push('readIds');
    }
    if (document.querySelector('[data-places-source]')) {
        include.push('places');
    }
    if (include.length) {
        query.push('include=' + include.join(','));
    }
    startBeacon();
    fetch(endpoint + (query.length ? '?' + query.join('&') : ''), {
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { 'Accept': 'application/json' }
    }).then(function (response) {
        return response.ok ? readJson(response) : null;
    }).then(function (answer) {
        if (!answer || answer.signedIn !== true || !answer.csrf) {
            return;
        }
        state = answer;
        saves.forEach(function (control) { press(control, answer.saved === true); });
        follows.forEach(function (control) { press(control, answer.following === true); });
        fillRows();
        markRead();
        fillPlaces();
        wireSignedIn();
        complete();
        waiting.splice(0).forEach(function (work) { work(); });
        data.setAttribute('data-ready', '');
    }).catch(function () {});
})();
