import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

/* theme/scripts/pressroom.js is a plain browser script (loaded by head.jsp on
   every admin page and by weblog.vm on public ones), not a module, so it is
   run here the way a browser runs it: as a classic script in a context whose
   globals are a minimal window and document. Its function declarations become
   globals of that context. */

const SOURCE = readFileSync(new URL('../../src/main/webapp/theme/scripts/pressroom.js', import.meta.url), 'utf8');

function memoryStorage(entries) {
  const map = new Map(Object.entries(entries));
  return {
    get length() { return map.size; },
    key(i) { return [...map.keys()][i] ?? null; },
    getItem(k) { return map.has(k) ? map.get(k) : null; },
    setItem(k, v) { map.set(k, String(v)); },
    removeItem(k) { map.delete(k); },
    keys() { return [...map.keys()]; }
  };
}

function load(storage) {
  const listeners = [];
  const document = {
    title: 't',
    cookie: '',
    addEventListener(type, fn, capture) { listeners.push({ type, fn, capture }); }
  };
  const context = { document, localStorage: storage, URL };
  context.window = context;
  vm.createContext(context);
  vm.runInContext(SOURCE, context);
  context.fire = (type, event) => listeners.filter((l) => l.type === type).forEach((l) => l.fn(event));
  return context;
}

/* A link's click event as the delegated handler sees it: closest() walks to the anchor. */
function clickOn(href) {
  const anchor = { getAttribute: (name) => (name === 'href' ? href : null) };
  anchor.closest = (selector) => (selector === 'a[href]' ? anchor : null);
  return {
    target: anchor,
    preventDefault() {},
    stopPropagation() {}
  };
}

test('the retired admin helpers are gone', () => {
  const context = load(memoryStorage({}));
  for (const name of ['isblank', 'toggleControl', 'toggle', 'toggleFolder', 'togglePlusMinus',
    'folderPreference', 'toggleNextRow', 'toggleFunction', 'toggleFunctionAll', 'isValidUrl', 'validateEmail']) {
    assert.equal(typeof context[name], 'undefined', name);
  }
  assert.equal(typeof context.getCookie, 'function', 'the sign-in page still remembers the user name');
});

test('signing out clears every autosaved draft and nothing else', () => {
  const storage = memoryStorage({
    'pressroom.draft.v1::u1:blog:entryEdit:e1': '{}',
    'pressroom.draft.v1::u2:blog:pageEdit:new': '{}',
    'pressroom.lastCategory.v1::blog': 'c1',
    'unrelated': 'x'
  });
  const context = load(storage);

  context.fire('click', clickOn('/pressroom-ui/menu.rol'));
  assert.equal(storage.length, 4, 'another link clears nothing');

  context.fire('click', clickOn('/blog/pressroom-ui/logout.rol;jsessionid=ABC'));
  assert.deepEqual(storage.keys(), ['pressroom.lastCategory.v1::blog', 'unrelated']);
});

test('signing out still works when storage is blocked', () => {
  const blocked = {};
  Object.defineProperty(blocked, 'length', { get() { throw new Error('SecurityError'); } });
  const context = load(blocked);
  assert.doesNotThrow(() => context.fire('click', clickOn('/pressroom-ui/logout.rol')));
});
