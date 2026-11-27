import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { JSDOM } from 'jsdom';

/* The reader's two scripts (0.18.0, Part E), run the way the browser runs
   them: once per page load, in a jsdom window with a scripted fetch, a fake
   clock and a fake setTimeout.
   - BUNDLE, src/reader.js (built to pressroom-reader.js), loads only into a
     signed-in page; a plain script, so its source IS what the browser runs.
   - STUB, the inline signed-out script in weblog.vm's showReaderScript
     macro, between <script data-reader-stub> and </script>. Its one
     Velocity reference, the sign-in's URL inside a string, is given a value
     here. */

const BUNDLE = readFileSync(new URL('../src/reader.js', import.meta.url), 'utf8');
const TEMPLATE = readFileSync(new URL('../../src/main/webapp/WEB-INF/velocity/weblog.vm', import.meta.url), 'utf8');
const START = '<script data-reader-stub>';
const STUB = TEMPLATE.slice(TEMPLATE.indexOf(START) + START.length,
  TEMPLATE.indexOf('</script>', TEMPLATE.indexOf(START)))
  .replace('$utils.escapeJavaScript($utils.ssoStartUrl)', '\\/pressroom-ui\\/sso\\/start');

const ENDPOINT = '/pressroom-ui/rendering/reader.rol';
const MSGS = ' data-msg-save-done="Saved to your account." data-msg-follow-done="You follow this blog."'
  + ' data-msg-newsletter="Also email me new posts" data-msg-newsletter-confirm="Check your inbox to confirm."'
  + ' data-msg-newsletter-already="Already subscribed." data-msg-newsletter-unavailable="Try again later."'
  + ' data-msg-failed="That did not work." data-msg-read="Read"';

function control(kind, attrs, label) {
  return `<span class="reader-${kind}" data-reader-${kind} ${attrs} data-label-off="${label}"`
    + ` data-label-on="${label}ed" data-sign-in="Sign in to ${kind}">`
    + `<button type="button" aria-pressed="false">${label}</button></span>`;
}

function page({ signedIn = false, readEntry = null, extra = '' } = {}) {
  return '<!doctype html><html><body>'
    + control('save', 'data-entry="e1" data-via="save"', 'Save')
    + control('follow', 'data-weblog="blog1" data-via="follow"', 'Follow')
    + extra
    + (signedIn ? `<span hidden data-reader data-endpoint="${ENDPOINT}" data-weblog="blog1" data-signed-in`
      + (readEntry ? ` data-read-entry="${readEntry}"` : '') + MSGS + '></span>' : '')
    + '</body></html>';
}

const STATE = {
  signedIn: true, name: 'Ivy', initials: 'IR', author: false,
  csrf: { header: 'X-CSRF-TOKEN', token: 'tok' },
  saved: false, following: false, newsletter: { available: true }
};

/** A page load: a fresh DOM over the given sessionStorage contents, the script run once. */
function load(html, { state = STATE, storage = {}, answers = {}, helper = true, visible = true, hold = false, bundle = null } = {}) {
  const dom = new JSDOM(html, { url: 'https://site.test/blog1/entry/one', runScripts: 'outside-only' });
  const { window } = dom;
  for (const [k, v] of Object.entries(storage)) {
    window.sessionStorage.setItem(k, v);
  }
  const calls = [];
  const clock = { now: 1_000_000 };
  const timers = [];
  let visibility = visible ? 'visible' : 'hidden';
  const held = [];
  Object.defineProperty(window.document, 'visibilityState', { get: () => visibility, configurable: true });
  window.Date.now = () => clock.now;
  window.setTimeout = (fn, ms) => { const t = { fn, at: clock.now + ms, live: true }; timers.push(t); return t; };
  window.clearTimeout = (t) => { if (t) { t.live = false; } };
  window.fetch = (url, options = {}) => {
    const method = options.method || 'GET';
    const body = options.body ? JSON.parse(options.body) : null;
    calls.push({ url, method, body, headers: options.headers || {}, keepalive: options.keepalive,
      intentLeft: window.sessionStorage.getItem('pressroom.intent') });
    let status = 200;
    let json = method === 'GET' ? state : { ok: true };
    if (body && answers[body.action]) {
      ({ status, json } = answers[body.action]);
    }
    const response = {
      ok: status >= 200 && status < 300,
      status,
      headers: { get: () => 'application/json' },
      json: () => Promise.resolve(json)
    };
    if (hold && method === 'GET') {
      return new Promise((resolve) => held.push(() => resolve(response)));
    }
    return Promise.resolve(response);
  };
  if (helper) {
    window.pressroomGoogleSignIn = (via, label) => {
      const a = window.document.createElement('a');
      a.className = 'g-signin';
      a.setAttribute('data-umami-event-via', via);
      a.textContent = label;
      return a;
    };
  }
  window.eval((bundle ?? !!window.document.querySelector('[data-signed-in]')) ? BUNDLE : STUB);
  return {
    release: () => held.splice(0).forEach((go) => go()),
    window, document: window.document, calls, storage: window.sessionStorage,
    posts: () => calls.filter((c) => c.method === 'POST'),
    advance(ms) {
      clock.now += ms;
      for (const t of timers.filter((x) => x.live && x.at <= clock.now)) {
        t.live = false;
        t.fn();
      }
    },
    setVisibility(v) {
      visibility = v;
      window.document.dispatchEvent(new window.Event('visibilitychange'));
    }
  };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));
async function settled() {
  for (let i = 0; i < 10; i++) {
    await settle();
  }
}

test('the bundle sets server strings as text; the stub is small and holds none of the bundle', () => {
  assert.ok(BUNDLE.length > 5000, 'found the bundle');
  assert.ok(!BUNDLE.includes('innerHTML'));
  assert.ok(STUB.length > 300 && STUB.length < 1600, `the stub is ${STUB.length} characters`);
  for (const part of ['reader.rol', 'fetch(', 'visibilitychange', 'data-reader-row', 'innerHTML', '#']) {
    assert.ok(!STUB.includes(part), part);
  }
});

test('signed out: a stale intent is dropped, and a click keeps the intent and shows the sign-in in place', async () => {
  const stale = JSON.stringify({ action: 'save', entry: 'old', at: 999_000 });
  const p = load(page(), { storage: { 'pressroom.intent': stale } });
  await settled();
  assert.equal(p.storage.getItem('pressroom.intent'), null, 'a cancelled sign-in leaves no intent behind');
  assert.equal(p.calls.length, 0, 'a signed-out page asks the server nothing');

  p.document.querySelector('[data-reader-save] button').click();
  const kept = JSON.parse(p.storage.getItem('pressroom.intent'));
  assert.deepEqual({ action: kept.action, entry: kept.entry }, { action: 'save', entry: 'e1' });
  const link = p.document.querySelector('[data-reader-save] a.g-signin');
  assert.ok(link, 'the provider button replaces the control');
  assert.equal(link.getAttribute('data-umami-event-via'), 'save');
  assert.equal(link.textContent, 'Sign in to save');
  assert.equal(p.document.querySelector('[data-reader-save] button'), null);

  p.document.querySelector('[data-reader-follow] button').click();
  const follow = JSON.parse(p.storage.getItem('pressroom.intent'));
  assert.deepEqual({ action: follow.action, weblog: follow.weblog, via: follow.via },
    { action: 'follow', weblog: 'blog1', via: 'follow' });
});

test('intentCompletesOnce: back signed in, the intent is removed before it is posted, and a reload does not repeat it', async () => {
  const intent = JSON.stringify({ action: 'save', entry: 'e1', at: 1_000_000 - 60_000 });
  const first = load(page({ signedIn: true }), { storage: { 'pressroom.intent': intent } });
  await settled();
  const saves = first.posts().filter((c) => c.body.action === 'save');
  assert.equal(saves.length, 1);
  assert.equal(saves[0].body.entry, 'e1');
  assert.equal(saves[0].headers['X-CSRF-TOKEN'], 'tok', 'with the CSRF header the state named');
  assert.equal(saves[0].intentLeft, null, 'the intent was gone before the post');
  const button = first.document.querySelector('[data-reader-save] button');
  assert.equal(button.getAttribute('aria-pressed'), 'true');
  assert.ok(first.document.querySelector('[data-reader]').hasAttribute('data-ready'), 'marked ready once shown');
  assert.equal(first.document.querySelector('[data-reader-save] .reader-note').textContent, 'Saved to your account.');

  assert.equal(first.storage.getItem('pressroom.intent'), null, 'the key is cleared');
  const carried = {};
  for (let i = 0; i < first.storage.length; i++) {
    carried[first.storage.key(i)] = first.storage.getItem(first.storage.key(i));
  }

  const reload = load(page({ signedIn: true }), { storage: carried });
  await settled();
  assert.equal(reload.posts().length, 0, 'the reload, over the same storage, posts nothing');
});

test('an intent older than half an hour is dropped, not completed', async () => {
  const old = JSON.stringify({ action: 'save', entry: 'e1', at: 1_000_000 - 31 * 60_000 });
  const p = load(page({ signedIn: true }), { storage: { 'pressroom.intent': old } });
  await settled();
  assert.equal(p.posts().length, 0);
  assert.equal(p.storage.getItem('pressroom.intent'), null);
});

test('signed in: the controls show the state and toggle it', async () => {
  const p = load(page({ signedIn: true }), { state: { ...STATE, saved: true } });
  await settled();
  const get = p.calls[0];
  assert.equal(get.method, 'GET');
  assert.ok(get.url.startsWith(ENDPOINT + '?entry=e1&weblog=blog1'), get.url);
  const save = p.document.querySelector('[data-reader-save] button');
  assert.equal(save.getAttribute('aria-pressed'), 'true');
  assert.equal(save.textContent, 'Saveed');
  save.click();
  await settled();
  assert.deepEqual(p.posts()[0].body, { action: 'unsave', entry: 'e1' });
  assert.equal(save.getAttribute('aria-pressed'), 'false');
});

test('a follow offers the newsletter, and each subscribe answer is its own calm line', async () => {
  for (const [answer, text] of [
    [{ status: 200, json: { ok: true, outcome: 'confirm' } }, 'Check your inbox to confirm.'],
    [{ status: 200, json: { ok: true, outcome: 'already' } }, 'Already subscribed.'],
    [{ status: 503, json: { outcome: 'unavailable' } }, 'Try again later.']]) {
    const p = load(page({ signedIn: true }), { answers: { subscribe: answer } });
    await settled();
    p.document.querySelector('[data-reader-follow] button').click();
    await settled();
    const control = p.document.querySelector('[data-reader-follow]');
    assert.equal(control.querySelector('button').getAttribute('aria-pressed'), 'true');
    assert.equal(control.querySelector('.reader-note').textContent, 'You follow this blog.');
    const offer = control.querySelector('button.reader-newsletter');
    assert.equal(offer.textContent, 'Also email me new posts');
    offer.click();
    await settled();
    assert.deepEqual(p.posts().at(-1).body, { action: 'subscribe', weblog: 'blog1' });
    assert.equal(control.querySelector('.reader-note').textContent, text);
    assert.equal(control.querySelector('button.reader-newsletter'), null);
  }
});

test('no newsletter offer where none is available', async () => {
  const p = load(page({ signedIn: true }), { state: { ...STATE, newsletter: { available: false } } });
  await settled();
  p.document.querySelector('[data-reader-follow] button').click();
  await settled();
  assert.equal(p.document.querySelector('button.reader-newsletter'), null);
});

test('the read beacon: one read after ten visible seconds, time hidden not counted', async () => {
  const p = load(page({ signedIn: true, readEntry: 'e1' }));
  await settled();
  p.advance(4000);
  p.setVisibility('hidden');
  p.advance(60_000);
  assert.equal(p.posts().length, 0, 'four visible seconds are not enough');
  p.setVisibility('visible');
  p.advance(5999);
  assert.equal(p.posts().length, 0);
  p.advance(1);
  await settled();
  const reads = p.posts().filter((c) => c.body.action === 'read');
  assert.equal(reads.length, 1);
  assert.equal(reads[0].body.entry, 'e1');
  assert.equal(reads[0].keepalive, true);
  p.setVisibility('hidden');
  p.setVisibility('visible');
  p.advance(20_000);
  await settled();
  assert.equal(p.posts().filter((c) => c.body.action === 'read').length, 1, 'once per page view');
});

test('rows, read marks and places are filled as text, and an empty row stays hidden', async () => {
  const extra = '<section data-reader-row="continue" hidden><ul></ul></section>'
    + '<section data-reader-row="followed" hidden><ul></ul></section>'
    + '<a href="/blog1/entry/one" data-reader-entry="e1">One</a><a href="/blog1/entry/two" data-reader-entry="e2">Two</a>'
    + '<nav data-places data-places-source="server" hidden></nav>';
  const state = {
    ...STATE,
    continueReading: [{ id: 'e1', title: '<b>Bold</b> & co', url: '/blog1/entry/one', weblogName: 'Blog <1>' },
      { id: 'x', title: 'Elsewhere', url: 'https://evil.test/x', weblogName: 'Evil' }],
    followedPosts: [],
    readIds: ['e1'],
    places: [{ handle: 'blog1', name: 'Here', url: '/blog1/' }, { handle: 'blog2', name: 'There <i>', url: '/blog2/' }]
  };
  const p = load(page({ signedIn: true, extra }), { state });
  await settled();
  assert.ok(p.calls[0].url.endsWith('include=continue,followed,readIds,places'), p.calls[0].url);
  const cont = p.document.querySelector('[data-reader-row="continue"]');
  assert.equal(cont.hidden, false);
  const links = cont.querySelectorAll('a');
  assert.equal(links.length, 1, 'a link off this site is left out');
  assert.equal(links[0].textContent, '<b>Bold</b> & co');
  assert.equal(links[0].querySelector('b'), null, 'never parsed as markup');
  assert.equal(p.document.querySelector('[data-reader-row="followed"]').hidden, true, 'empty stays hidden');
  const marks = p.document.querySelectorAll('.reader-read-mark');
  assert.equal(marks.length, 1);
  assert.equal(marks[0].previousElementSibling.getAttribute('data-reader-entry'), 'e1');
  const places = p.document.querySelector('[data-places]');
  assert.equal(places.hidden, false);
  assert.deepEqual([...places.querySelectorAll('a')].map((a) => a.textContent), ['There <i>'],
    'the current blog is left out');
});

test('a signed-out state answer (an expired session) changes nothing', async () => {
  const p = load(page({ signedIn: true }), { state: { signedIn: false } });
  await settled();
  assert.equal(p.document.querySelector('[data-reader-save] button').getAttribute('aria-pressed'), 'false');
  p.document.querySelector('[data-reader-save] button').click();
  await settled();
  assert.equal(p.posts().length, 0);
});

test('a signed-out click without the head helper still offers a plain sign-in link', async () => {
  const p = load(page(), { helper: false });
  p.document.querySelector('[data-reader-follow] button').click();
  const link = p.document.querySelector('[data-reader-follow] a');
  assert.ok(link, 'a link replaces the button');
  assert.equal(link.getAttribute('href'), '/pressroom-ui/sso/start?return=%2Fblog1%2Fentry%2Fone');
  assert.equal(link.textContent, 'Sign in to follow');
  assert.equal(JSON.parse(p.storage.getItem('pressroom.intent')).action, 'follow');
});

test('a follow intent confirms on the control the reader clicked (by via)', async () => {
  const extra = control('follow', 'data-weblog="blog1" data-via="article-end"', 'Follow');
  const intent = JSON.stringify({ action: 'follow', weblog: 'blog1', via: 'article-end', at: 1_000_000 });
  const p = load(page({ signedIn: true, extra }), { storage: { 'pressroom.intent': intent } });
  await settled();
  const [masthead, end] = p.document.querySelectorAll('[data-reader-follow]');
  assert.equal(p.posts().filter((c) => c.body.action === 'follow').length, 1);
  assert.equal(masthead.querySelector('button').getAttribute('aria-pressed'), 'true', 'every control shows it');
  assert.equal(masthead.querySelector('.reader-note'), null);
  assert.equal(end.querySelector('.reader-note').textContent, 'You follow this blog.');
});

test('a beacon due before the state answer waits for it, then posts once', async () => {
  const p = load(page({ signedIn: true, readEntry: 'e1' }), { hold: true });
  p.advance(10_000);
  await settled();
  assert.equal(p.posts().length, 0, 'no CSRF token yet');
  p.release();
  await settled();
  assert.equal(p.posts().filter((c) => c.body.action === 'read').length, 1);
});

test('a page loaded in a hidden tab starts counting only once it is shown', async () => {
  const p = load(page({ signedIn: true, readEntry: 'e1' }), { visible: false });
  await settled();
  p.advance(60_000);
  assert.equal(p.posts().length, 0);
  p.setVisibility('visible');
  p.advance(10_000);
  await settled();
  assert.equal(p.posts().filter((c) => c.body.action === 'read').length, 1);
});

test('the You menu closes on a click outside it and on Escape (any signed-in page, data element or not)', () => {
  const html = '<!doctype html><html><body><details class="you-menu" open><summary>IR</summary>'
    + '<ul><li><a href="/x">Saved</a></li></ul></details><p id="out">elsewhere</p></body></html>';
  const p = load(html, { bundle: true });
  const menu = p.document.querySelector('details.you-menu');
  menu.querySelector('a').dispatchEvent(new p.window.MouseEvent('click', { bubbles: true }));
  assert.equal(menu.open, true, 'a click inside keeps it open');
  p.document.getElementById('out').dispatchEvent(new p.window.MouseEvent('click', { bubbles: true }));
  assert.equal(menu.open, false);
  menu.open = true;
  menu.dispatchEvent(new p.window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  assert.equal(menu.open, false);
});
