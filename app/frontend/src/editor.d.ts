/** Types for editor.js (the CodeMirror 6 setup the JSP editors and the React admin share); the .js is not forked. */
import type { EditorView } from '@codemirror/view';

export interface ShortcodeOption {
  name: string;
  label: string;
  snippet: string;
}

export interface EditorOptions {
  textarea: HTMLTextAreaElement;
  label?: string;
  placeholder?: string;
  shortcodes?: readonly ShortcodeOption[];
  onChange?: () => void;
  onUpload?: (files: File[]) => void;
  onSave?: () => void;
  onPublish?: () => void;
  onHelp?: () => void;
  onBold?: () => void;
  onItalic?: () => void;
  onLink?: () => void;
}

export interface PressroomEditor {
  view: EditorView;
  getValue(): string;
  setValue(text: string): void;
  insert(text: string): void;
  focus(): void;
  destroy(): void;
  wrapSelection(before: string, after: string, fallback: string): void;
  toggleLinePrefix(prefix: string): void;
  scrollFraction(): number;
  stats(): { words: number; minutes: number };
}

export function create(options: EditorOptions): PressroomEditor;
