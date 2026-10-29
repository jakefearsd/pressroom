/* The reader's content enhancements, bundled as pressroom-content.js and loaded
   only by the content-asset macro in WEB-INF/velocity/weblog.vm, and only on a
   page that has something for it to do (a fenced code block or a heading id
   inside entry or page content).

   The macro owns the contract -- which containers are content, and the
   selectors that find unprocessed blocks and headings in them -- and hands it
   in on every call, so the page's lazy-load check and this bundle can never
   disagree about what "something to do" means. The styles live in the macro
   too: they must apply before, and without, this script.

   enhance() is re-runnable over a root that partly overlaps an earlier one:
   every block and heading it finishes is marked, and the selectors it is
   given skip marked ones. The editor's live preview depends on that, calling
   it again after each swap of the article's innerHTML. */
import { mergeLabels, languageOf, lineCount, gutterText, copyableText, copyText } from './content-core.js';
import { highlight } from './highlighter.js';

/* How long the copy button says what happened before it says Copy again. */
const FEEDBACK_MS = 2000;

function element(doc, tag, className, text) {
  const el = doc.createElement(tag);
  if (className) el.className = className;
  if (text !== undefined) el.textContent = text;
  return el;
}

/* The route for a page with no async clipboard (plain http on a LAN host is
   the common case): a throwaway textarea and the legacy copy command. */
function execCommandCopy(doc, text) {
  const area = element(doc, 'textarea');
  area.value = text;
  area.setAttribute('readonly', '');
  area.style.position = 'fixed';
  area.style.top = '-1000px';
  area.style.opacity = '0';
  doc.body.appendChild(area);
  try {
    area.select();
    return doc.execCommand('copy');
  } finally {
    area.remove();
  }
}

/* Nothing could copy: select the code, so the reader's own copy shortcut
   takes exactly the code and nothing else. */
function selectForHand(doc, code) {
  const selection = doc.defaultView && doc.defaultView.getSelection();
  if (selection) selection.selectAllChildren(code);
}

function copyButton(doc, code, text, labels) {
  const button = element(doc, 'button', 'pressroom-code-copy', labels.copy);
  button.type = 'button';
  button.setAttribute('aria-live', 'polite');
  let reset = null;
  button.addEventListener('click', async () => {
    const nav = doc.defaultView && doc.defaultView.navigator;
    const copied = await copyText(text, {
      clipboard: nav && nav.clipboard,
      fallback: (t) => execCommandCopy(doc, t)
    });
    if (!copied) selectForHand(doc, code);
    button.textContent = copied ? labels.copied : labels.copyFailed;
    button.classList.toggle('is-copied', copied);
    clearTimeout(reset);
    reset = setTimeout(() => {
      button.textContent = labels.copy;
      button.classList.remove('is-copied');
    }, FEEDBACK_MS);
  });
  return button;
}

/* <pre><code class="language-x"> becomes:
     div.pressroom-code
       div.pressroom-code-bar      span.pressroom-code-lang (when tagged), the copy button
       div.pressroom-code-body     span.pressroom-code-lines (multi-line only), the original pre
   The gutter sits outside the pre, so the pre's horizontal scroll moves the
   code and not the numbers, and neither selecting the pre nor the copy button
   can take a number with the code. */
function enhanceCodeBlock(doc, code, labels) {
  const pre = code.parentElement;
  if (!pre || pre.hasAttribute('data-pressroom-code')) return;
  pre.setAttribute('data-pressroom-code', '1');

  const text = code.textContent;
  const language = languageOf(code.className);
  const highlighted = highlight(text, language);
  if (highlighted !== null) {
    code.innerHTML = highlighted;
    code.classList.add('hljs');
  }

  const block = element(doc, 'div', 'pressroom-code');
  if (language) block.setAttribute('data-language', language);
  const bar = element(doc, 'div', 'pressroom-code-bar');
  if (language) bar.appendChild(element(doc, 'span', 'pressroom-code-lang', language));
  bar.appendChild(copyButton(doc, code, copyableText(text), labels));
  const body = element(doc, 'div', 'pressroom-code-body');

  pre.parentNode.insertBefore(block, pre);
  block.appendChild(bar);
  block.appendChild(body);
  const lines = lineCount(text);
  if (lines > 1) {
    const gutter = element(doc, 'span', 'pressroom-code-lines', gutterText(lines));
    gutter.setAttribute('aria-hidden', 'true');
    body.appendChild(gutter);
  }
  body.appendChild(pre);
}

function addHeadingAnchor(doc, heading, labels) {
  if (heading.hasAttribute('data-pressroom-anchor') || !heading.id) return;
  heading.setAttribute('data-pressroom-anchor', '1');
  const anchor = element(doc, 'a', 'pressroom-heading-anchor', '#');
  anchor.href = '#' + heading.id;
  anchor.setAttribute('aria-label', labels.anchor);
  heading.appendChild(anchor);
}

/* options: { code, headings, labels } -- code and headings are the macro's
   selectors (unprocessed <code> in a content <pre>, unprocessed h2-h4 with an
   id in content); labels are the weblog's words for the controls. */
export function enhance(root, options = {}) {
  const scope = root || document;
  const doc = scope.ownerDocument || scope;
  const labels = mergeLabels(options.labels);
  if (options.code) {
    for (const code of scope.querySelectorAll(options.code)) enhanceCodeBlock(doc, code, labels);
  }
  if (options.headings) {
    for (const heading of scope.querySelectorAll(options.headings)) addHeadingAnchor(doc, heading, labels);
  }
}
