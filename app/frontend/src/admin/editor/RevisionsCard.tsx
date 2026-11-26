import { useState } from 'react';
import { createPortal } from 'react-dom';
import { DateTime } from '../components/DateTime';
import { useT } from '../i18n/messages';

export interface RevisionRow {
  id?: string | undefined;
  created?: string | undefined;
  creator?: string | undefined;
  note?: string | undefined;
  kept?: boolean | undefined;
}

export interface RevisionDiff {
  /** Unified diff of the revision's text against the current text ('' when the same). */
  text: string;
  title: { before: string; after: string };
  summary: { before: string; after: string };
}

interface Props {
  revisions: readonly RevisionRow[];
  busy: boolean;
  onDiff: (id: string) => Promise<RevisionDiff>;
  onRestore: (id: string) => void;
  onKeep: (id: string, kept: boolean) => void;
}

function lineKind(line: string): 'diff-added' | 'diff-removed' | 'diff-same' {
  return line.startsWith('+') ? 'diff-added' : line.startsWith('-') ? 'diff-removed' : 'diff-same';
}

/** The lines of a unified diff worth showing: hunks without the ---/+++ file header. */
export function diffLines(unified: string): string[] {
  return unified.split('\n').filter((l, i) => !(i < 2 && (l.startsWith('---') || l.startsWith('+++'))) && l !== '');
}

/** A diff line as the JSP's pre showed it: "+ added", "- removed", "  same" (a hunk header stays as written). */
function shown(line: string): string {
  const mark = line.charAt(0);
  return mark === '+' || mark === '-' || mark === ' ' ? `${mark === ' ' ? ' ' : mark} ${line.slice(1)}` : `  ${line}`;
}

function Section({ label, lines }: { label: string; lines: string[] }) {
  return (
    <>
      <p className="rail-group-label">{label}</p>
      <pre>
        {lines.map((line, i) => <span key={`${String(i)}:${line}`} className={lineKind(line)}>{`${shown(line)}\n`}</span>)}
      </pre>
    </>
  );
}

const changed = (side: { before: string; after: string }) => (side.before === side.after ? [] : [`-${side.before}`, `+${side.after}`]);

/**
 * Every content-changing save leaves a revision: compare (a server-computed diff against the
 * entry as stored, shown in a modal), restore (itself undoable), and keep/release (a kept
 * revision is exempt from every retention limit).
 */
export function RevisionsCard({ revisions, busy, onDiff, onRestore, onKeep }: Props) {
  const t = useT();
  const [diff, setDiff] = useState<{ state: 'loading' } | { state: 'failed' } | { state: 'ready'; value: RevisionDiff } | null>(null);
  const compare = (id: string) => {
    setDiff({ state: 'loading' });
    onDiff(id).then(
      (value) => { setDiff({ state: 'ready', value }); },
      () => { setDiff({ state: 'failed' }); }
    );
  };
  if (revisions.length === 0) {
    return null;
  }
  return (
    <div id="entryRevisionsCard" className="editor-box editor-rail-extra">
      <p className="rail-group-label">{t('weblogEdit.revisions')}</p>
      <p className="pagetip">{t('weblogEdit.revisionsTip')}</p>
      <table className="table table-sm" id="entryRevisionsTable">
        <tbody>
          {revisions.flatMap((r) => {
            const id = r.id ?? '';
            return [
              <tr key={id}>
                <td><DateTime value={r.created} /></td>
                <td>{r.creator}</td>
                <td>
                  <button type="button" className="btn btn-link btn-sm revision-diff-button" onClick={() => { compare(id); }}>
                    {t('weblogEdit.revisionCompare')}
                  </button>
                </td>
                <td>
                  <button type="button" className="btn btn-outline-secondary btn-sm revision-restore-button" disabled={busy}
                          onClick={() => { onRestore(id); }}>
                    {t('weblogEdit.revisionRestore')}
                  </button>
                </td>
                <td>
                  {r.kept === true ? <span className="status-pill revision-kept-marker">{t('weblogEdit.revisionKeptLabel')}</span> : null}
                  <button type="button" className="btn btn-link btn-sm revision-keep-button" disabled={busy}
                          onClick={() => { onKeep(id, r.kept !== true); }}>
                    {t(r.kept === true ? 'weblogEdit.revisionRelease' : 'weblogEdit.revisionKeep')}
                  </button>
                </td>
              </tr>,
              ...(r.note === undefined || r.note === '' ? [] : [
                <tr key={`${id}-note`} className="revision-note-row"><td colSpan={5} className="revision-note">{r.note}</td></tr>
              ])
            ];
          })}
        </tbody>
      </table>
      {diff === null ? null : createPortal(
        <>
          <div id="revision-diff-modal" className="modal fade show" style={{ display: 'block' }} tabIndex={-1}
               role="dialog" aria-modal="true" aria-labelledby="revision-diff-modal-title">
            <div className="modal-dialog modal-lg">
              <div className="modal-content">
                <div className="modal-header">
                  <p id="revision-diff-modal-title" className="modal-title">{t('weblogEdit.revisionCompare')}</p>
                  <button type="button" className="btn-close" aria-label={t('generic.close')} onClick={() => { setDiff(null); }} />
                </div>
                <div className="modal-body" id="revisionDiffBody">
                  {diff.state === 'loading' ? t('weblogEdit.previewLoading') : null}
                  {diff.state === 'failed' ? t('weblogEdit.previewFailed') : null}
                  {diff.state === 'ready' ? <DiffBody value={diff.value} /> : null}
                </div>
              </div>
            </div>
          </div>
          <div className="modal-backdrop fade show" />
        </>,
        document.body
      )}
    </div>
  );
}

function DiffBody({ value }: { value: RevisionDiff }) {
  const t = useT();
  const title = changed(value.title);
  const summary = changed(value.summary);
  const content = diffLines(value.text);
  if (title.length === 0 && summary.length === 0 && content.length === 0) {
    return <div className="revision-diff"><p className="revision-diff-same">{t('weblogEdit.revisionSame')}</p></div>;
  }
  return (
    <div className="revision-diff">
      {title.length === 0 ? null : <Section label={t('weblogEdit.title')} lines={title} />}
      {summary.length === 0 ? null : <Section label={t('weblogEdit.summary')} lines={summary} />}
      {content.length === 0 ? null : <Section label={t('weblogEdit.content')} lines={content} />}
    </div>
  );
}
