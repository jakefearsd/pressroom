/* Syntax highlighting for the reader's code blocks: highlight.js core with a
   curated, explicitly registered set of grammars -- the languages a technical
   post on this site is written in, not the 190-odd the full build carries.

   Never auto-detected. highlightAuto guesses from the text and guesses wrong
   often enough to colour prose as Perl; an author who wants colour names the
   language on the fence, and an unnamed or unknown one is left plain (it
   keeps its copy button and line numbers, which do not depend on this). */
import HighlightJS from 'highlight.js/lib/core';
import bash from 'highlight.js/lib/languages/bash';
import css from 'highlight.js/lib/languages/css';
import diff from 'highlight.js/lib/languages/diff';
import dockerfile from 'highlight.js/lib/languages/dockerfile';
import go from 'highlight.js/lib/languages/go';
import ini from 'highlight.js/lib/languages/ini';
import java from 'highlight.js/lib/languages/java';
import javascript from 'highlight.js/lib/languages/javascript';
import json from 'highlight.js/lib/languages/json';
import kotlin from 'highlight.js/lib/languages/kotlin';
import markdown from 'highlight.js/lib/languages/markdown';
import plaintext from 'highlight.js/lib/languages/plaintext';
import properties from 'highlight.js/lib/languages/properties';
import python from 'highlight.js/lib/languages/python';
import rust from 'highlight.js/lib/languages/rust';
import shell from 'highlight.js/lib/languages/shell';
import sql from 'highlight.js/lib/languages/sql';
import typescript from 'highlight.js/lib/languages/typescript';
import xml from 'highlight.js/lib/languages/xml';
import yaml from 'highlight.js/lib/languages/yaml';

const GRAMMARS = {
  bash, css, diff, dockerfile, go, ini, java, javascript, json, kotlin,
  markdown, plaintext, properties, python, rust, shell, sql, typescript, xml, yaml
};

/* Our own instance, not the shared singleton: nothing else on the page can
   register into it or reconfigure it. */
const hljs = HighlightJS.newInstance();
for (const [name, grammar] of Object.entries(GRAMMARS)) hljs.registerLanguage(name, grammar);

/* The registered names, for the test that keeps the set curated. Aliases the
   grammars declare (html for xml, toml for ini, sh for bash, js, ts, py, yml
   ...) resolve through getLanguage without being listed here. */
export const REGISTERED_LANGUAGES = Object.freeze(hljs.listLanguages());

/* Highlighted HTML for text in the named language, or null when the language
   is absent or not one of ours. The result is highlight.js's own markup:
   spans with hljs-* classes around text it has HTML-escaped. */
export function highlight(text, language) {
  if (!language || !hljs.getLanguage(language)) return null;
  return hljs.highlight(text, { language, ignoreIllegals: true }).value;
}
