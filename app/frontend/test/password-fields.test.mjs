import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

/* theme/scripts/pressroom-password.js is a plain browser script. Its DOM
   behaviour is proven by PasswordFieldsIT in a real browser; this covers the
   one pure decision, run the way theme-scripts.test.mjs runs pressroom.js:
   as a classic script in a context with a minimal document. */

const SOURCE = readFileSync(new URL('../../src/main/webapp/theme/scripts/pressroom-password.js', import.meta.url), 'utf8');

function load() {
  const document = {
    currentScript: null,
    readyState: 'complete',
    addEventListener() {},
    querySelectorAll() { return []; }
  };
  const context = { document, addEventListener() {} };
  context.window = context;
  vm.createContext(context);
  vm.runInContext(SOURCE, context);
  return context;
}

test('nothing is said until the confirm field has something in it', () => {
  const { passwordMatchState } = load();
  assert.equal(passwordMatchState('', ''), 'empty');
  assert.equal(passwordMatchState('abc', ''), 'empty');
});

test('identical passwords match', () => {
  assert.equal(load().passwordMatchState('abc', 'abc'), 'match');
});

test('different passwords do not match', () => {
  const { passwordMatchState } = load();
  assert.equal(passwordMatchState('abc', 'abd'), 'mismatch');
  assert.equal(passwordMatchState('', 'x'), 'mismatch');
});

test('the comparison is exact: a trailing space is a different password', () => {
  assert.equal(load().passwordMatchState('abc ', 'abc'), 'mismatch');
});
