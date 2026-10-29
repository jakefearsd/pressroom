import { EditorView } from '@codemirror/view';
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { tags } from '@lezer/highlight';

/* Every colour is a design token (pressroom-tokens.css); no hex here. Emphasis
   is weight, never size (design-system.md, signature move 4). */
export const pressroomTheme = EditorView.theme({
  '&': {
    backgroundColor: 'var(--surface)',
    color: 'var(--ink)',
    fontFamily: 'var(--font-ui)',
    fontSize: '14.5px',
    border: '1px solid var(--line)',
    borderRadius: '6px'
  },
  '&.cm-focused': { outline: '2px solid var(--focus)', outlineOffset: '1px' },
  '.cm-content': { padding: '12px 14px', lineHeight: '1.6', caretColor: 'var(--ink)' },
  '.cm-line': { padding: '0' },
  '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--ink)' },
  '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': {
    backgroundColor: 'var(--accent-quiet)'
  },
  '.cm-gutters': { display: 'none' },
  '.cm-placeholder': { color: 'var(--ink-soft)', fontStyle: 'normal' },
  '.cm-scroller': { fontFamily: 'inherit', overflow: 'auto' },
  '.cm-panels': { backgroundColor: 'var(--paper)', color: 'var(--ink)', borderColor: 'var(--line)' },
  '.cm-searchMatch': { backgroundColor: 'var(--accent-quiet)', outline: '1px solid var(--accent)' },
  '.cm-tooltip': { backgroundColor: 'var(--surface)', border: '1px solid var(--line)', color: 'var(--ink)' },
  '.cm-tooltip-autocomplete ul li[aria-selected]': { backgroundColor: 'var(--accent-quiet)', color: 'var(--ink)' },
  '.pressroom-shortcode': { color: 'var(--accent)', fontFamily: 'var(--font-data)', fontSize: '13.5px' }
});

const style = HighlightStyle.define([
  { tag: tags.heading, fontWeight: '600' },
  { tag: tags.strong, fontWeight: '600' },
  { tag: tags.emphasis, fontStyle: 'italic' },
  { tag: tags.strikethrough, textDecoration: 'line-through' },
  { tag: tags.monospace, fontFamily: 'var(--font-data)', fontSize: '13.5px' },
  { tag: tags.link, color: 'var(--accent)', textDecoration: 'underline' },
  { tag: tags.url, color: 'var(--accent)' },
  { tag: tags.quote, color: 'var(--ink-soft)' },
  { tag: tags.processingInstruction, color: 'var(--ink-soft)' },
  { tag: tags.meta, color: 'var(--ink-soft)' }
]);

export const pressroomHighlighting = syntaxHighlighting(style);
