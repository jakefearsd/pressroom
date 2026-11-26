import { useEffect, useImperativeHandle, useRef, type Ref } from 'react';
import { useLatest } from './useLatest';
import { create, type PressroomEditor, type ShortcodeOption } from '../../editor.js';

/** What the surface and the screen may do to the editor: editor.js's own API plus the one in-place replace. */
export interface EditorHandle extends PressroomEditor {
  /** Replaces the first occurrence of `needle`, leaving the caret where it is; false when it is gone. */
  replaceInEditor(needle: string, replacement: string): boolean;
}

export interface CodeMirrorFieldProps {
  /** The text; a change that did not come from typing (a restored draft, a reload) is pushed into the editor. */
  value: string;
  onChange: (text: string) => void;
  label: string;
  placeholder: string;
  shortcodes: readonly ShortcodeOption[];
  onSave?: () => void;
  onPublish?: () => void;
  onHelp?: () => void;
  onBold?: () => void;
  onItalic?: () => void;
  onLink?: () => void;
  onUpload?: (files: File[]) => void;
  ref?: Ref<EditorHandle>;
}

/**
 * The CodeMirror 6 editor of editor.js (the same `create()` the JSP editors mount, not a
 * fork) inside React. editor.js wants a textarea it can hide and a wrapper it inserts before
 * it, so both live in a host div React renders no children into: React never sees the DOM
 * the editor adds. The editor is created once; callbacks are read through a ref so a new
 * render never rebuilds it (and a rebuild would lose the caret and the undo history).
 */
export function CodeMirrorField(props: CodeMirrorFieldProps) {
  const host = useRef<HTMLDivElement>(null);
  const editor = useRef<EditorHandle | null>(null);
  const { ref: handleRef, value } = props;
  const latest = useLatest(props);

  useImperativeHandle(handleRef, () => ({
    get view() { return editor.current?.view as EditorHandle['view']; },
    getValue: () => editor.current?.getValue() ?? '',
    setValue: (text) => { editor.current?.setValue(text); },
    insert: (text) => { editor.current?.insert(text); },
    focus: () => { editor.current?.focus(); },
    destroy: () => { editor.current?.destroy(); },
    wrapSelection: (before, after, fallback) => { editor.current?.wrapSelection(before, after, fallback); },
    toggleLinePrefix: (prefix) => { editor.current?.toggleLinePrefix(prefix); },
    scrollFraction: () => editor.current?.scrollFraction() ?? 0,
    stats: () => editor.current?.stats() ?? { words: 0, minutes: 0 },
    replaceInEditor(needle, replacement) {
      const view = editor.current?.view;
      if (view === undefined) {
        return false;
      }
      const at = view.state.doc.toString().indexOf(needle);
      if (at < 0) {
        return false;
      }
      view.dispatch({ changes: { from: at, to: at + needle.length, insert: replacement } });
      return true;
    }
  }), []);

  useEffect(() => {
    const element = host.current;
    if (element === null) {
      return undefined;
    }
    const textarea = document.createElement('textarea');
    textarea.id = 'edit_content';
    textarea.rows = 18;
    textarea.value = latest.current.value;
    element.append(textarea);
    // Each callback is looked up when it fires, but only offered to editor.js when this screen
    // supplied one: a key with no callback is left to CodeMirror (see keys.js).
    const via = (name: 'onSave' | 'onPublish' | 'onHelp' | 'onBold' | 'onItalic' | 'onLink') =>
      latest.current[name] === undefined ? {} : { [name]: () => { latest.current[name]?.(); } };
    const created = create({
      textarea,
      label: latest.current.label,
      placeholder: latest.current.placeholder,
      shortcodes: latest.current.shortcodes,
      ...via('onSave'), ...via('onPublish'), ...via('onHelp'), ...via('onBold'), ...via('onItalic'), ...via('onLink'),
      ...(latest.current.onUpload === undefined ? {} : { onUpload: (files: File[]) => { latest.current.onUpload?.(files); } }),
      onChange: () => { latest.current.onChange(created.getValue()); }
    });
    editor.current = created as EditorHandle;
    // The browser suite's seam (it/support/Editor.java), the names the JSP editors' page script gave: the one
    // place a test reads or replaces the text without naming the editor's DOM.
    const seam = window as unknown as Record<string, unknown>;
    seam['pressroomGetEntryText'] = () => created.getValue();
    seam['pressroomSetEntryText'] = (text: string) => { created.setValue(text); };
    return () => {
      delete seam['pressroomGetEntryText'];
      delete seam['pressroomSetEntryText'];
      created.destroy();
      textarea.remove();
      editor.current = null;
    };
  }, [latest]);

  useEffect(() => {
    const current = editor.current;
    if (current !== null && current.getValue() !== value) {
      current.setValue(value);
    }
  }, [value]);

  return <div className="editor-write" ref={host} />;
}
