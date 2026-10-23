import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { hostBindings } from '../src/keys.js';

/* The toolbar's tooltips and the writing guide advertise Ctrl+B / Ctrl+I /
   Ctrl+K. Unbound, Ctrl+I ran CodeMirror's selectParentSyntax and Ctrl+B /
   Ctrl+K fell through to the browser (bookmarks, search bar). "Mod" is
   CodeMirror's Ctrl-or-Cmd, so the same binding serves macOS. */

const bound = (options) => new Map(hostBindings(options).map((b) => [b.key, b.run]));

test('every advertised shortcut is bound', () => {
  const keys = bound({});
  for (const key of ['Mod-s', 'Mod-Enter', 'Mod-/', 'Mod-b', 'Mod-i', 'Mod-k']) {
    assert.ok(keys.has(key), key + ' must be bound');
  }
});

test('the formatting shortcuts run the host commands and consume the key', () => {
  const calls = [];
  const keys = bound({
    onBold: () => calls.push('bold'),
    onItalic: () => calls.push('italic'),
    onLink: () => calls.push('link')
  });
  assert.equal(keys.get('Mod-b')(), true);
  assert.equal(keys.get('Mod-i')(), true);
  assert.equal(keys.get('Mod-k')(), true);
  assert.deepEqual(calls, ['bold', 'italic', 'link']);
});

test('a key the host supplied no command for is left to CodeMirror', () => {
  const keys = bound({});
  for (const run of keys.values()) {
    assert.equal(run(), false);
  }
});

test('the editor installs the bindings above its default keymap', () => {
  const source = readFileSync(new URL('../src/editor.js', import.meta.url), 'utf8');
  assert.match(source, /Prec\.highest\(keymap\.of\(hostBindings\(options\)\)\)/);
});

test('the active-line highlight the theme hides is not installed', () => {
  const source = readFileSync(new URL('../src/editor.js', import.meta.url), 'utf8');
  assert.doesNotMatch(source, /highlightActiveLine/);
});

test('the writing surface carries an accessible name', () => {
  const source = readFileSync(new URL('../src/editor.js', import.meta.url), 'utf8');
  assert.match(source, /EditorView\.contentAttributes\.of\(\{ 'aria-label'/);
});
