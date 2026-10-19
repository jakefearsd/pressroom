import test from 'node:test';
import assert from 'node:assert/strict';
import { languageOf, lineCount, gutterText, copyableText, copyText, mergeLabels, DEFAULT_LABELS } from '../src/content-core.js';
import { highlight, REGISTERED_LANGUAGES } from '../src/highlighter.js';

test('the language comes from the language-* class commonmark writes, lowercased', () => {
  assert.equal(languageOf('language-java'), 'java');
  assert.equal(languageOf('hljs language-TypeScript'), 'typescript');
  assert.equal(languageOf('language-c++'), 'c++');
  assert.equal(languageOf(''), null);
  assert.equal(languageOf(undefined), null);
  assert.equal(languageOf('lang-java'), null, 'only the class commonmark emits');
  assert.equal(languageOf('notlanguage-java'), null, 'a whole class, not a suffix');
});

test('the trailing newline commonmark ends a block with is not a line', () => {
  assert.equal(lineCount('int x = 1;\n'), 1);
  assert.equal(lineCount('a\nb\nc\n'), 3);
  assert.equal(lineCount('a\nb'), 2);
  assert.equal(lineCount('a\n\n'), 2, 'an authored blank last line still counts');
  assert.equal(lineCount(''), 0);
  assert.equal(lineCount('\n'), 1);
});

test('the gutter numbers every line, one per row', () => {
  assert.equal(gutterText(3), '1\n2\n3');
  assert.equal(gutterText(1), '1');
  assert.equal(gutterText(0), '');
});

test('copying takes the code without the block\'s trailing newline', () => {
  assert.equal(copyableText('int x = 1;\n'), 'int x = 1;');
  assert.equal(copyableText('a\nb'), 'a\nb');
  assert.equal(copyableText('a\n\n'), 'a\n', 'only the one newline commonmark adds');
  assert.equal(copyableText(''), '');
});

test('copy uses the async clipboard when there is one', async () => {
  const written = [];
  const ok = await copyText('x', {
    clipboard: { writeText: async (t) => { written.push(t); } },
    fallback: () => { throw new Error('the fallback must not run'); }
  });
  assert.equal(ok, true);
  assert.deepEqual(written, ['x']);
});

test('copy falls back when the clipboard is missing or refuses', async () => {
  const seen = [];
  assert.equal(await copyText('a', { clipboard: undefined, fallback: (t) => { seen.push(t); return true; } }), true);
  assert.equal(await copyText('b', {
    clipboard: { writeText: async () => { throw new Error('NotAllowedError'); } },
    fallback: (t) => { seen.push(t); return true; }
  }), true);
  assert.deepEqual(seen, ['a', 'b']);
});

test('copy reports failure rather than throwing when nothing can copy', async () => {
  assert.equal(await copyText('a', { clipboard: null, fallback: () => false }), false);
  assert.equal(await copyText('a', { clipboard: null, fallback: () => { throw new Error('no'); } }), false);
  assert.equal(await copyText('a', {}), false);
});

test('the curated languages are registered, aliases included', () => {
  for (const lang of ['java', 'javascript', 'typescript', 'python', 'bash', 'shell', 'sql',
    'xml', 'css', 'json', 'yaml', 'go', 'rust', 'kotlin', 'markdown', 'diff',
    'dockerfile', 'properties', 'ini', 'plaintext']) {
    assert.ok(REGISTERED_LANGUAGES.includes(lang), lang + ' must be registered');
  }
  assert.equal(REGISTERED_LANGUAGES.length, 20, 'curated, not the whole catalogue');
  // the aliases the brief names, which hljs carries on the registered grammars
  assert.notEqual(highlight('<b>x</b>', 'html'), null, 'html is xml');
  assert.notEqual(highlight('[a]\nb = 1', 'toml'), null, 'toml is ini');
  assert.notEqual(highlight('echo hi', 'sh'), null, 'sh is bash');
});

test('a known language is highlighted into hljs spans, escaped', () => {
  const html = highlight('public class A { int x = 1; } // <b>', 'java');
  assert.match(html, /<span class="hljs-keyword">public<\/span>/);
  assert.match(html, /hljs-comment/);
  assert.ok(html.includes('&lt;b&gt;'), 'the code text is escaped, never markup: ' + html);
  assert.ok(!html.includes('<b>'), html);
});

test('an unknown or absent language is never auto-detected', () => {
  assert.equal(highlight('defmodule A do end', 'elixir'), null);
  assert.equal(highlight('public class A {}', null), null);
  assert.equal(highlight('public class A {}', ''), null);
  assert.equal(highlight('public class A {}', undefined), null);
});

test('the page\'s own words win, but a missing or empty one falls back', () => {
  assert.deepEqual(mergeLabels({ copy: 'Kopieren', copied: '', copyFailed: null }), {
    copy: 'Kopieren', copied: DEFAULT_LABELS.copied, copyFailed: DEFAULT_LABELS.copyFailed,
    anchor: DEFAULT_LABELS.anchor
  });
  assert.deepEqual(mergeLabels(undefined), { ...DEFAULT_LABELS });
  assert.equal(mergeLabels({ stray: 'x' }).stray, undefined, 'only the known labels');
});
