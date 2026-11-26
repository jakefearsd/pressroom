/* eslint-disable react-hooks/refs --
   The editor handle and the latest-commands ref are read only inside event handlers and effects (a
   toolbar click, a key, a scroll); the lint cannot see that through the closures built each render. */
import { useEffect, useRef, useState, type ReactNode, type RefObject } from 'react';
import { useApi } from '../context';
import { useT } from '../i18n/messages';
import { CodeMirrorField, type EditorHandle } from './CodeMirrorField';
import { PreviewPane, type PreviewHandle } from './PreviewPane';
import { SHORTCODE_CARDS } from './shortcodeCards';
import { uploadImage } from './upload';
import { useLatest } from './useLatest';

export type SaveState = 'notSaved' | 'unsaved' | 'savedLocally' | 'saved';
type Mode = 'write' | 'split' | 'preview';
export const ERROR_MS = 8000;

const TOOLS: ReadonlyArray<{ cmd: string; icon: string; labelKey: string; shortcut?: string } | 'sep'> = [
  { cmd: 'bold', icon: 'bi-type-bold', labelKey: 'editor.bold', shortcut: 'Ctrl+B' },
  { cmd: 'italic', icon: 'bi-type-italic', labelKey: 'editor.italic', shortcut: 'Ctrl+I' },
  { cmd: 'heading', icon: 'bi-type-h2', labelKey: 'editor.heading' },
  'sep',
  { cmd: 'quote', icon: 'bi-blockquote-left', labelKey: 'editor.quote' },
  { cmd: 'ul', icon: 'bi-list-ul', labelKey: 'editor.bulletList' },
  { cmd: 'ol', icon: 'bi-list-ol', labelKey: 'editor.numberedList' },
  'sep',
  { cmd: 'link', icon: 'bi-link-45deg', labelKey: 'editor.link', shortcut: 'Ctrl+K' },
  { cmd: 'code', icon: 'bi-code', labelKey: 'editor.code' },
  { cmd: 'table', icon: 'bi-table', labelKey: 'editor.table' },
  { cmd: 'image', icon: 'bi-image', labelKey: 'weblogEdit.insertMediaFile' }
];

/**
 * The mode labels are composed (`editor.mode.${m}`), which MessageKeyTest cannot see; the keys are
 * listed here: editor.mode.write editor.mode.split editor.mode.preview
 */
const isMac = () => /Mac|iP(hone|[oa]d)/.test(navigator.platform);
const modifier = () => (isMac() ? '⌘' : 'Ctrl');

function readMode(key: string): Mode {
  try {
    const stored = window.localStorage.getItem(key);
    return stored === 'split' || stored === 'preview' ? stored : 'write';
  } catch {
    return 'write';
  }
}

interface Props {
  handle: string;
  entryId: string | undefined;
  contextPath: string;
  text: string;
  onTextChange: (text: string) => void;
  saveState: SaveState;
  /** Message key of the guide's Ctrl+Enter row (weblogEdit.post here). */
  primaryActionKey: string;
  /** Message key of the guide's Ctrl+S row: weblogEdit.save, or weblogEdit.update on a live entry. */
  saveActionKey: string;
  onSave: () => void;
  onPublish: () => void;
  /** Opens the media chooser to insert at the cursor. */
  onChooseMedia: () => void;
  /** The editor, for the screen's own inserts (a media pick) and focus moves. */
  editorRef: RefObject<EditorHandle | null>;
}

/**
 * The writing surface of EditorSurface.jsp + EditorScript.jsp: toolbar, write/split/preview
 * mode, the CodeMirror editor, the preview, status line (words, save state, upload refusals)
 * and the writing guide. Ctrl+S and Ctrl+Enter are bound inside the editor (CodeMirror's own
 * keymap claims both) and on the document for focus anywhere else.
 */
export function EditorSurface(props: Props) {
  const t = useT();
  const api = useApi();
  const modeKey = `pressroom.editor.mode.v1:${props.contextPath}`;
  const [mode, setModeState] = useState<Mode>(() => readMode(modeKey));
  const [menuOpen, setMenuOpen] = useState(false);
  const [guideOpen, setGuideOpen] = useState(false);
  const [stats, setStats] = useState({ words: 0, minutes: 0 });
  const [refusals, setRefusals] = useState<Array<{ id: number; message: string }>>([]);
  const editor = props.editorRef;
  const preview = useRef<PreviewHandle>(null);
  const nextRefusal = useRef(0);
  const { onSave, onPublish, onChooseMedia, onTextChange, handle } = props;

  const setMode = (next: Mode) => {
    setModeState(next);
    try {
      window.localStorage.setItem(modeKey, next);
    } catch {
      /* storage unavailable: the choice simply does not persist */
    }
  };
  useEffect(() => {
    if (editor.current !== null) {
      setStats(editor.current.stats());
    }
  }, [props.text, editor]);

  const refuse = (message: string) => {
    const id = nextRefusal.current++;
    setRefusals((all) => [...all, { id, message }]);
    setTimeout(() => { setRefusals((all) => all.filter((r) => r.id !== id)); }, ERROR_MS);
  };

  const commands: Record<string, () => void> = {
    bold: () => { editor.current?.wrapSelection('**', '**', t('editor.boldPlaceholder')); },
    italic: () => { editor.current?.wrapSelection('*', '*', t('editor.italicPlaceholder')); },
    heading: () => { editor.current?.toggleLinePrefix('## '); },
    quote: () => { editor.current?.toggleLinePrefix('> '); },
    ul: () => { editor.current?.toggleLinePrefix('- '); },
    ol: () => { editor.current?.toggleLinePrefix('1. '); },
    link: () => { editor.current?.wrapSelection('[', '](https://)', t('editor.linkPlaceholder')); },
    code: () => { editor.current?.wrapSelection('`', '`', t('editor.codePlaceholder')); },
    table: () => {
      const header = t('editor.tableHeader');
      editor.current?.insert(`\n| ${header} | ${header} |\n| --- | --- |\n|  |  |\n`);
    },
    image: onChooseMedia,
    help: () => { setGuideOpen(true); }
  };
  const commandsRef = useLatest(commands);

  // Ctrl+S / Ctrl+Enter / Ctrl+/ with focus outside the editor (the title, the rail).
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.defaultPrevented || !(event.ctrlKey || event.metaKey)) {
        return;
      }
      if (event.key === 's' || event.key === 'S') {
        event.preventDefault();
        onSave();
      } else if (event.key === 'Enter') {
        event.preventDefault();
        onPublish();
      } else if (event.key === '/') {
        event.preventDefault();
        setGuideOpen(true);
      }
    };
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('keydown', onKey); };
  }, [onSave, onPublish]);

  useEffect(() => {
    if (!guideOpen) {
      return undefined;
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setGuideOpen(false);
      }
    };
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('keydown', onKey); };
  }, [guideOpen]);

  // Editor scroll drives the preview's, one message per animation frame.
  useEffect(() => {
    const view = editor.current?.view;
    if (mode === 'write' || view === undefined) {
      return undefined;
    }
    let queued = false;
    const onScroll = () => {
      if (queued) {
        return;
      }
      queued = true;
      window.requestAnimationFrame(() => {
        queued = false;
        preview.current?.scroll(editor.current?.scrollFraction() ?? 0);
      });
    };
    view.scrollDOM.addEventListener('scroll', onScroll);
    return () => { view.scrollDOM.removeEventListener('scroll', onScroll); };
  }, [mode, editor]);

  const upload = (files: File[]) => {
    const queue = [...files];
    const next = async (): Promise<void> => {
      const file = queue.shift();
      if (file === undefined) {
        return;
      }
      const placeholder = `[${t('editor.uploadingPlaceholder')} ${file.name.replace(/[[\]]/g, '')}…]`;
      editor.current?.insert(`${placeholder}\n`);
      const result = await uploadImage(api, handle, file);
      if (result.id !== undefined) {
        editor.current?.replaceInEditor(placeholder, `[image id="${result.id}"]`);
      } else {
        // Take the trailing newline the placeholder was inserted with; fall back to the bare text.
        if (editor.current?.replaceInEditor(`${placeholder}\n`, '') !== true) {
          editor.current?.replaceInEditor(placeholder, '');
        }
        refuse(result.detail ?? t('editor.uploadFailed'));
      }
      await next();
    };
    void next();
  };

  const shortcodes = SHORTCODE_CARDS.map((c) => ({ name: c.name, snippet: c.snippet, label: t(c.labelKey) }));
  const saveLabel = {
    notSaved: t('weblogEdit.unsaved'), unsaved: t('editor.status.unsaved'),
    savedLocally: t('editor.status.savedLocally'), saved: t('editor.status.saved')
  }[props.saveState];
  const mod = modifier();
  const tip = (label: string, shortcut?: string) => (shortcut === undefined ? label : `${label} (${shortcut.replace('Ctrl+', mod === 'Ctrl' ? 'Ctrl+' : mod)})`);

  return (
    <>
      <div className="editor-surface" id="editorSurface" data-mode={mode}>
        <div className="editor-toolbar" id="editorToolbar" role="toolbar" aria-label={t('editor.toolbar')}>
          {TOOLS.map((tool, index) => tool === 'sep'
            ? <span key={`sep${String(index)}`} className="editor-tool-sep" aria-hidden="true" />
            : (
              <button key={tool.cmd} type="button" className="editor-tool" data-cmd={tool.cmd}
                      title={tip(t(tool.labelKey), tool.shortcut)} {...(tool.shortcut ? { 'data-shortcut': true } : {})}
                      onClick={() => { commands[tool.cmd]?.(); }}>
                <span className={`bi ${tool.icon}`} aria-hidden="true" />
                <span className="visually-hidden">{t(tool.labelKey)}</span>
              </button>
            ))}
          <div className="dropdown d-inline-block" id="shortcodeInsertMenu">
            <button className="editor-tool dropdown-toggle" type="button" id="shortcodeInsertButton"
                    aria-expanded={menuOpen} onClick={() => { setMenuOpen((o) => !o); }}>
              {t('weblogEdit.insertShortcode')}
            </button>
            <ul className={`dropdown-menu${menuOpen ? ' show' : ''}`} aria-labelledby="shortcodeInsertButton">
              {SHORTCODE_CARDS.map((card) => (
                <li key={card.name}>
                  <button type="button" className="dropdown-item shortcode-card" data-shortcode={card.name}
                          data-snippet={card.snippet} data-chooser={String(card.usesMediaChooser)}
                          onClick={() => {
                            setMenuOpen(false);
                            if (card.usesMediaChooser) {
                              onChooseMedia();
                            } else {
                              editor.current?.insert(card.snippet);
                              editor.current?.focus();
                            }
                          }}>
                    {t(card.labelKey)}
                  </button>
                </li>
              ))}
            </ul>
          </div>
          <div className="editor-mode" id="editorMode" role="radiogroup" aria-label={t('editor.mode')}>
            {(['write', 'split', 'preview'] as const).map((m) => (
              <button key={m} type="button" role="radio" aria-checked={mode === m} data-mode={m}
                      onClick={() => { setMode(m); }}>
                {t(`editor.mode.${m}`)}
              </button>
            ))}
          </div>
          <button type="button" className="editor-tool editor-help" data-cmd="help" data-shortcut
                  title={tip(t('editor.guide'), 'Ctrl+/')} onClick={commands['help']}>
            <span className="bi bi-question-circle" aria-hidden="true" />
            <span className="visually-hidden">{t('editor.guide')}</span>
          </button>
        </div>
        <div className="editor-panes">
          <CodeMirrorField ref={editor} value={props.text} onChange={onTextChange} label={t('editor.textLabel')}
                           placeholder={t('editor.placeholder')} shortcodes={shortcodes}
                           onSave={onSave} onPublish={onPublish} onHelp={commands['help']}
                           onBold={() => { commandsRef.current['bold']?.(); }}
                           onItalic={() => { commandsRef.current['italic']?.(); }}
                           onLink={() => { commandsRef.current['link']?.(); }}
                           onUpload={upload} />
          <PreviewPane ref={preview} handle={props.handle} entryId={props.entryId} text={props.text}
                       visible={mode !== 'write'} scrollFraction={() => editor.current?.scrollFraction() ?? 0} />
        </div>
        <div className="editor-status" id="editorStatus">
          <span id="editorStatusWords">{t('editor.status.words', stats.words, stats.minutes)}</span>
          <span id="editorStatusSave">{saveLabel}</span>
          <span className="editor-status-alerts" id="editorStatusAlerts" role="status">
            {refusals.map((r) => <span key={r.id} className="is-error">{r.message}</span>)}
          </span>
        </div>
      </div>
      {guideOpen ? <Guide modifier={mod} saveActionKey={props.saveActionKey} primaryActionKey={props.primaryActionKey}
                          onClose={() => { setGuideOpen(false); }} /> : null}
    </>
  );
}

/** The writing guide: an offcanvas beside the editor rather than a modal over it. */
function Guide({ modifier: mod, saveActionKey, primaryActionKey, onClose }: {
  modifier: string; saveActionKey: string; primaryActionKey: string; onClose: () => void;
}) {
  const t = useT();
  const key = (rest: ReactNode) => <><kbd className="editor-mod">{mod}</kbd>+{rest}</>;
  return (
    <div className="offcanvas offcanvas-end editor-guide show" style={{ visibility: 'visible' }} tabIndex={-1}
         id="editorGuide" aria-labelledby="editorGuideTitle">
      <div className="offcanvas-header">
        <p className="rail-group-label mb-0" id="editorGuideTitle">{t('editor.guide')}</p>
        <button type="button" className="btn-close" aria-label={t('generic.close')} onClick={onClose} />
      </div>
      <div className="offcanvas-body">
        <p className="rail-group-label">{t('editor.guide.markdown')}</p>
        <table className="editor-guide-table">
          <tbody>
            <tr><td><code>## {t('editor.heading')}</code></td><td>{t('editor.guide.h1')}</td></tr>
            <tr><td><code>**{t('editor.boldPlaceholder')}**</code></td><td>{t('editor.bold')}</td></tr>
            <tr><td><code>*{t('editor.italicPlaceholder')}*</code></td><td>{t('editor.italic')}</td></tr>
            <tr><td><code>[{t('editor.linkPlaceholder')}](https://…)</code></td><td>{t('editor.link')}</td></tr>
            <tr><td><code>- {t('editor.guide.item')}</code></td><td>{t('editor.bulletList')}</td></tr>
            <tr><td><code>1. {t('editor.guide.item')}</code></td><td>{t('editor.numberedList')}</td></tr>
            <tr><td><code>&gt; {t('editor.guide.quote')}</code></td><td>{t('editor.quote')}</td></tr>
            <tr><td><code>`{t('editor.codePlaceholder')}`</code></td><td>{t('editor.code')}</td></tr>
            <tr><td><code>| a | b |</code></td><td>{t('editor.table')}</td></tr>
          </tbody>
        </table>
        <p className="rail-group-label">{t('editor.guide.shortcodes')}</p>
        <table className="editor-guide-table">
          <tbody>
            {SHORTCODE_CARDS.map((card) => (
              <tr key={card.name} data-shortcode={card.name}><td><code>{card.snippet}</code></td><td>{t(card.labelKey)}</td></tr>
            ))}
          </tbody>
        </table>
        <p className="rail-group-label">{t('editor.guide.shortcuts')}</p>
        <table className="editor-guide-table">
          <tbody>
            <tr><td>{key(<kbd>S</kbd>)}</td><td>{t(saveActionKey)}</td></tr>
            <tr><td>{key(<kbd>Enter</kbd>)}</td><td>{t(primaryActionKey)}</td></tr>
            <tr><td>{key(<><kbd>B</kbd> / <kbd>I</kbd></>)}</td><td>{t('editor.bold')} / {t('editor.italic')}</td></tr>
            <tr><td>{key(<kbd>K</kbd>)}</td><td>{t('editor.link')}</td></tr>
            <tr><td>{key(<kbd>F</kbd>)}</td><td>{t('editor.find')}</td></tr>
            <tr><td>{key(<kbd>/</kbd>)}</td><td>{t('editor.guide')}</td></tr>
            <tr><td><kbd>Esc</kbd> <kbd>Tab</kbd></td><td>{t('editor.guide.leaveEditor')}</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  );
}
