/* The DOM-free half of the reader's content enhancements (content.js): the
   rules a code block's toolbar, gutter and copy button follow, kept apart so
   node --test can pin them without a browser. */

/* The words a reader sees. The page passes its own (the weblog's locale, from
   the content-asset macro's data attributes); these only stand in when it
   does not. */
export const DEFAULT_LABELS = Object.freeze({
  copy: 'Copy',
  copied: 'Copied',
  copyFailed: 'Copy failed',
  anchor: 'Link to this section'
});

/* The page's labels over the defaults. A label the page left empty (a
   message key with no text) or did not send falls back rather than leaving a
   blank button; anything that is not one of ours is dropped. */
export function mergeLabels(given) {
  const merged = { ...DEFAULT_LABELS };
  for (const key of Object.keys(DEFAULT_LABELS)) {
    const value = given && given[key];
    if (typeof value === 'string' && value.trim() !== '') merged[key] = value;
  }
  return merged;
}

/* The fenced block's language, from the class commonmark writes on <code>
   ("language-java"), or null for an untagged block. Lowercased, because an
   author's ```Java and ```java are the same language. */
export function languageOf(className) {
  const match = /(?:^|\s)language-(\S+)/.exec(className || '');
  return match ? match[1].toLowerCase() : null;
}

/* Lines in a block's text. commonmark ends every fenced block with a newline,
   which is not a line of the author's code; any blank line before it is. */
export function lineCount(text) {
  if (!text) return 0;
  const body = text.endsWith('\n') ? text.slice(0, -1) : text;
  return body.split('\n').length;
}

/* The gutter's text: one number per line, one per row. */
export function gutterText(count) {
  const numbers = [];
  for (let i = 1; i <= count; i++) numbers.push(String(i));
  return numbers.join('\n');
}

/* What the copy button puts on the clipboard: the code, without the newline
   commonmark appended. Never the gutter, which is not in the code element. */
export function copyableText(text) {
  if (!text) return '';
  return text.endsWith('\n') ? text.slice(0, -1) : text;
}

/* Copies text, answering true or false and never throwing. The async
   clipboard needs a secure context and can refuse (permissions, an
   unfocused document); the fallback is whatever the page can do without it
   (content.js passes the execCommand route). Both are injected so the
   decision can be tested without a browser. */
export async function copyText(text, { clipboard, fallback } = {}) {
  if (clipboard && typeof clipboard.writeText === 'function') {
    try {
      await clipboard.writeText(text);
      return true;
    } catch (refused) {
      // fall through to the fallback
    }
  }
  if (typeof fallback !== 'function') return false;
  try {
    return Boolean(fallback(text));
  } catch (failed) {
    return false;
  }
}
