import '@testing-library/jest-dom/vitest';

// CodeMirror measures text with Range geometry, which jsdom does not implement.
const emptyRects = () => ({ length: 0, item: () => null, [Symbol.iterator]: function* () { /* none */ } }) as unknown as DOMRectList;
const emptyRect = () => new DOMRect(0, 0, 0, 0);
Range.prototype.getClientRects = emptyRects;
Range.prototype.getBoundingClientRect = emptyRect;
