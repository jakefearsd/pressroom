import { useEffect, useRef, useState } from 'react';
import { create, type EditorHandle } from '../../../editor.js';
import { useT } from '../../i18n/messages';
import { PagePreview } from './PagePreview';

export interface BodyPreview {
  html: string;
  warnings: readonly string[];
}

interface Props {
  /** The Markdown text; a change from outside (restored draft or revision) replaces the editor's. */
  value: string;
  onChange: (text: string) => void;
  /** Renders the text on the server (the preview pane). */
  render: (text: string) => Promise<BodyPreview>;
  onSave?: () => void;
}

type Pane = { state: 'idle' } | { state: 'loading' } | { state: 'failed' } | { state: 'ready'; preview: BodyPreview };

/**
 * The page body: the CodeMirror editor of src/editor.js in a Write pane, and the server's
 * rendering in a Preview pane. A SMALL local wrapper on purpose (no toolbar, split mode or
 * word count): the entry editor's shared React component replaces it, with the same props
 * shape, when that lands.
 */
export function PageBodyEditor({ value, onChange, render, onSave }: Props) {
  const t = useT();
  const host = useRef<HTMLDivElement>(null);
  const area = useRef<HTMLTextAreaElement>(null);
  const handle = useRef<EditorHandle | null>(null);
  const latest = useRef({ onChange, onSave });
  const [mode, setMode] = useState<'write' | 'preview'>('write');
  const [pane, setPane] = useState<Pane>({ state: 'idle' });

  useEffect(() => {
    latest.current = { onChange, onSave };
  });

  useEffect(() => {
    if (area.current === null) {
      return undefined;
    }
    area.current.value = value;
    const editor = create({
      textarea: area.current,
      label: t('editor.textLabel'),
      onChange: () => { latest.current.onChange(editor.getValue()); },
      onSave: () => { latest.current.onSave?.(); }
    });
    handle.current = editor;
    return () => {
      editor.destroy();
      handle.current = null;
    };
    // The editor is created once; later values arrive through the effect below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (handle.current !== null && handle.current.getValue() !== value) {
      handle.current.setValue(value);
    }
  }, [value]);

  const show = (next: 'write' | 'preview') => {
    setMode(next);
    if (next === 'preview') {
      setPane({ state: 'loading' });
      render(value).then(
        (preview) => { setPane({ state: 'ready', preview }); },
        () => { setPane({ state: 'failed' }); }
      );
    }
  };

  return (
    <div className="editor-surface" id="editorSurface" data-mode={mode}>
      <div className="editor-toolbar" role="toolbar" aria-label={t('editor.toolbar')}>
        <div className="editor-mode" id="editorMode" role="radiogroup" aria-label={t('editor.mode')}>
          <button type="button" role="radio" aria-checked={mode === 'write'} onClick={() => { show('write'); }}>
            {t('editor.mode.write')}
          </button>
          <button type="button" role="radio" aria-checked={mode === 'preview'} onClick={() => { show('preview'); }}>
            {t('editor.mode.preview')}
          </button>
        </div>
      </div>
      <div className="editor-panes">
        <div className="editor-write" hidden={mode !== 'write'} ref={host}>
          <textarea ref={area} id="edit_content" rows={18} aria-label={t('editor.textLabel')} defaultValue={value} />
        </div>
        {mode === 'preview' ? (
          <div className="editor-preview-pane" id="editorPreviewPane" aria-live="polite">
            {pane.state === 'loading' ? t('weblogEdit.previewLoading') : null}
            {pane.state === 'failed' ? t('weblogEdit.previewFailed') : null}
            {pane.state === 'ready' ? <PagePreview html={pane.preview.html} /> : null}
          </div>
        ) : null}
      </div>
    </div>
  );
}
