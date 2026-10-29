import { EditorState, Prec } from '@codemirror/state';
import { EditorView, keymap, placeholder as placeholderExt, drawSelection,
         dropCursor, ViewPlugin, Decoration } from '@codemirror/view';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { markdown, markdownLanguage, markdownKeymap } from '@codemirror/lang-markdown';
import { bracketMatching } from '@codemirror/language';
import { closeBrackets, autocompletion } from '@codemirror/autocomplete';
import { search, searchKeymap } from '@codemirror/search';
import { pressroomTheme, pressroomHighlighting } from './markdown-theme.js';
import { shortcodeRanges } from './shortcodes.js';
import { wordCount, readingMinutes } from './stats.js';
import { hostBindings } from './keys.js';

const shortcodeMark = Decoration.mark({ class: 'pressroom-shortcode' });

function shortcodePlugin(names) {
  return ViewPlugin.fromClass(class {
    constructor(view) { this.decorations = this.build(view); }
    update(update) { if (update.docChanged || update.viewportChanged) this.decorations = this.build(update.view); }
    build(view) {
      const ranges = [];
      for (const { from, to } of view.visibleRanges) {
        const text = view.state.doc.sliceString(from, to);
        for (const r of shortcodeRanges(text, names)) {
          ranges.push(shortcodeMark.range(from + r.from, from + r.to));
        }
      }
      return Decoration.set(ranges, true);
    }
  }, { decorations: (v) => v.decorations });
}

function shortcodeCompletion(shortcodes) {
  return autocompletion({
    override: [(context) => {
      const word = context.matchBefore(/\[\w*/);
      if (!word || (word.from === word.to && !context.explicit)) return null;
      return {
        from: word.from,
        options: shortcodes.map((s) => ({ label: '[' + s.name, detail: s.label, apply: s.snippet }))
      };
    }]
  });
}

/* Handles image files pasted or dropped into the editor; the host decides
   what an upload means (Task A7). Returns true when it consumed the event. */
function fileHandler(onUpload) {
  const files = (list) => Array.from(list || []).filter((f) => f.type.startsWith('image/'));
  return EditorView.domEventHandlers({
    paste(event) {
      const picked = files(event.clipboardData && event.clipboardData.files);
      if (picked.length === 0 || !onUpload) return false;
      event.preventDefault();
      onUpload(picked);
      return true;
    },
    drop(event) {
      const picked = files(event.dataTransfer && event.dataTransfer.files);
      if (picked.length === 0 || !onUpload) return false;
      event.preventDefault();
      onUpload(picked);
      return true;
    }
  });
}

export function create(options) {
  const textarea = options.textarea;
  const shortcodes = options.shortcodes || [];
  const names = shortcodes.map((s) => s.name);
  /* The contenteditable is what assistive technology lands on, and the
     labelled textarea it replaces is hidden -- so the textarea's own name
     moves across with it. */
  const label = options.label || textarea.getAttribute('aria-label') || '';
  const changeListener = EditorView.updateListener.of((update) => {
    if (update.docChanged) {
      textarea.value = update.state.doc.toString();
      if (options.onChange) options.onChange();
    }
  });

  const state = EditorState.create({
    doc: textarea.value,
    extensions: [
      history(),
      drawSelection(),
      dropCursor(),
      bracketMatching(),
      closeBrackets(),
      search({ top: true }),
      markdown({ base: markdownLanguage, addKeymap: false }),
      pressroomHighlighting,
      pressroomTheme,
      EditorView.lineWrapping,
      EditorView.contentAttributes.of({ 'aria-label': label }),
      placeholderExt(options.placeholder || ''),
      shortcodePlugin(names),
      shortcodeCompletion(shortcodes),
      fileHandler(options.onUpload),
      /* Above defaultKeymap: see keys.js for why each key is here. */
      Prec.highest(keymap.of(hostBindings(options))),
      keymap.of([...markdownKeymap, ...searchKeymap, ...historyKeymap, ...defaultKeymap, indentWithTab]),
      changeListener
    ]
  });

  const wrapper = document.createElement('div');
  wrapper.className = 'pressroom-editor';
  textarea.parentNode.insertBefore(wrapper, textarea);
  textarea.hidden = true;
  const view = new EditorView({ state, parent: wrapper });

  function replaceSelection(text) {
    view.dispatch(view.state.replaceSelection(text));
    view.focus();
  }

  return {
    view,
    getValue: () => view.state.doc.toString(),
    setValue: (text) => view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: text } }),
    insert: replaceSelection,
    focus: () => view.focus(),
    destroy: () => { view.destroy(); wrapper.remove(); textarea.hidden = false; },
    wrapSelection(before, after, fallback) {
      const { from, to } = view.state.selection.main;
      const selected = view.state.doc.sliceString(from, to) || fallback;
      view.dispatch({
        changes: { from, to, insert: before + selected + after },
        selection: { anchor: from + before.length, head: from + before.length + selected.length }
      });
      view.focus();
    },
    toggleLinePrefix(prefix) {
      const line = view.state.doc.lineAt(view.state.selection.main.from);
      const has = line.text.startsWith(prefix);
      view.dispatch({
        changes: has ? { from: line.from, to: line.from + prefix.length, insert: '' }
                     : { from: line.from, insert: prefix }
      });
      view.focus();
    },
    scrollFraction() {
      const s = view.scrollDOM;
      const max = s.scrollHeight - s.clientHeight;
      return max <= 0 ? 0 : s.scrollTop / max;
    },
    stats() {
      const words = wordCount(view.state.doc.toString());
      return { words, minutes: readingMinutes(words) };
    }
  };
}
