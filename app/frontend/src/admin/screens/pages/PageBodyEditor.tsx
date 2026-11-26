import { useState } from 'react';
import { CodeMirrorField } from '../../editor/CodeMirrorField';
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
 * The page body: the entry editor's CodeMirrorField in a Write pane, and the server's
 * rendering in a Preview pane. A small wrapper on purpose (no toolbar, split mode or word
 * count: the page JSP has none); the editor itself is the shared CodeMirrorField.
 */
export function PageBodyEditor({ value, onChange, render, onSave }: Props) {
  const t = useT();
  const [mode, setMode] = useState<'write' | 'preview'>('write');
  const [pane, setPane] = useState<Pane>({ state: 'idle' });

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
        <div hidden={mode !== 'write'}>
          <CodeMirrorField value={value} onChange={onChange} label={t('editor.textLabel')} placeholder=""
            shortcodes={[]} {...(onSave === undefined ? {} : { onSave })} />
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
