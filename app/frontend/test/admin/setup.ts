import '@testing-library/jest-dom/vitest';

// jsdom has no layout: CodeMirror measures text through Range rects, so give it empty ones.
if (typeof Range !== 'undefined') {
  const none = Object.assign([], { item: () => null }) as unknown as DOMRectList;
  Range.prototype.getClientRects ??= () => none;
  Range.prototype.getBoundingClientRect ??= () => new DOMRect();
}
