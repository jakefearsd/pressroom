/** Types for the CodeMirror editor bundle's entry (editor.js), used by the React admin's body editors. */
export interface EditorOptions {
  textarea: HTMLTextAreaElement;
  label?: string;
  placeholder?: string;
  shortcodes?: { name: string; label?: string; snippet?: string }[];
  onChange?: () => void;
  onSave?: () => void;
  onPublish?: () => void;
}

export interface EditorHandle {
  getValue(): string;
  setValue(text: string): void;
  focus(): void;
  destroy(): void;
  stats(): { words: number; minutes: number };
}

export function create(options: EditorOptions): EditorHandle;
