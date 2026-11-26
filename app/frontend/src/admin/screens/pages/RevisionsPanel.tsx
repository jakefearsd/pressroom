import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { call } from '../../api/client';
import { usePageRevisions, type PageView } from '../../api/pages';
import { DateTime } from '../../components/DateTime';
import { ProblemBanner } from '../../components/ProblemBanner';
import { useApi } from '../../context';
import { useT } from '../../i18n/messages';

const DIFF_COLOURS: Record<string, string> = {
  '+': 'color-mix(in srgb, var(--good) 15%, var(--surface))',
  '-': 'color-mix(in srgb, var(--bad) 15%, var(--surface))'
};

/** A unified diff, one block per line: added and removed lines tinted, the rest soft (as the JSP's modal). */
function Diff({ text }: { text: string }) {
  const t = useT();
  if (text === '') {
    return <p className="revision-diff-same">{t('pageEdit.revisionSame')}</p>;
  }
  return (
    <pre style={{ whiteSpace: 'pre-wrap' }}>
      {text.split('\n').map((line, index) => {
        const sign = line.startsWith('+') && !line.startsWith('+++') ? '+'
          : line.startsWith('-') && !line.startsWith('---') ? '-' : ' ';
        const colour = DIFF_COLOURS[sign];
        return (
          <span key={index} className={sign === '+' ? 'diff-added' : sign === '-' ? 'diff-removed' : 'diff-same'}
                style={{ display: 'block', ...(colour === undefined ? { color: 'var(--ink-soft)' } : { backgroundColor: colour }) }}>
            {line}
          </span>
        );
      })}
    </pre>
  );
}

function DiffModal({ handle, pageId, revisionId, onClose }: {
  handle: string; pageId: string; revisionId: string; onClose: () => void;
}) {
  const t = useT();
  const api = useApi();
  const diff = useQuery({
    queryKey: ['page-revision', handle, pageId, revisionId],
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/pages/{id}/revisions/{revisionId}',
      { params: { path: { handle, id: pageId, revisionId } } })),
    staleTime: 0
  });
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('keydown', onKey); };
  }, [onClose]);
  return createPortal(
    <>
      <div id="revision-diff-modal" className="modal fade show" style={{ display: 'block' }} tabIndex={-1} role="dialog"
           aria-modal="true" aria-labelledby="revision-diff-modal-title">
        <div className="modal-dialog modal-lg">
          <div className="modal-content">
            <div className="modal-header">
              <p id="revision-diff-modal-title" className="modal-title">{t('weblogEdit.revisionCompare')}</p>
              <button type="button" className="btn-close" aria-label={t('generic.close')} onClick={onClose} />
            </div>
            <div className="modal-body" id="revisionDiffBody">
              {diff.isPending ? t('weblogEdit.previewLoading') : null}
              {diff.isError ? t('weblogEdit.previewFailed') : null}
              {diff.isSuccess ? <Diff text={diff.data.diff ?? ''} /> : null}
            </div>
          </div>
        </div>
      </div>
      <div className="modal-backdrop fade show" />
    </>,
    document.body
  );
}

/**
 * The saved page's revisions (`#pageRevisionsCard`): compare, restore (guarded by the text
 * the editor was loaded with), and keep or release. `onRestored` gets the page as restored; the
 * editor reloads from it, so it also says "restored" (this panel is rebuilt by then).
 */
export function RevisionsPanel({ handle, page, onRestored }: {
  handle: string; page: PageView; onRestored: (page: PageView) => void;
}) {
  const t = useT();
  const api = useApi();
  const queries = useQueryClient();
  const pageId = page.id ?? '';
  const revisions = usePageRevisions(handle, pageId);
  const [comparing, setComparing] = useState<string | null>(null);
  const [messages, setMessages] = useState<string[]>([]);
  const path = (revisionId: string) => ({ params: { path: { handle, id: pageId, revisionId } } });
  const refresh = () => queries.invalidateQueries({ queryKey: ['page-revisions', handle, pageId] });

  const restore = useMutation({
    mutationFn: (revisionId: string) => call(api.POST('/v1/weblogs/{handle}/pages/{id}/revisions/{revisionId}/restore', {
      ...path(revisionId), body: { expectedContentHash: page.contentHash ?? '' }
    })),
    onSuccess: async (restored) => {
      await refresh();
      onRestored(restored);
    }
  });
  const keep = useMutation({
    mutationFn: ({ revisionId, kept }: { revisionId: string; kept: boolean }) => call(api.POST(
      kept ? '/v1/weblogs/{handle}/pages/{id}/revisions/{revisionId}/unkeep'
        : '/v1/weblogs/{handle}/pages/{id}/revisions/{revisionId}/keep', path(revisionId))),
    onSuccess: async (summary) => {
      setMessages([summary.kept === true ? t('weblogEdit.revisionKept') : t('weblogEdit.revisionUnkept')]);
      await refresh();
    }
  });

  const list = revisions.data ?? [];
  if (list.length === 0) {
    return null;
  }
  return (
    <div id="pageRevisionsCard" className="editor-box editor-rail-extra">
      <p className="rail-group-label">{t('pageEdit.revisions')}</p>
      <p className="pagetip">{t('pageEdit.revisionsTip')}</p>
      <ProblemBanner messages={messages} error={restore.error ?? keep.error} />
      <table className="table table-sm" id="pageRevisionsTable">
        <tbody>
          {list.flatMap((revision) => {
            const id = revision.id ?? '';
            const kept = revision.kept === true;
            return [
              <tr key={id}>
                <td><DateTime value={revision.created} /></td>
                <td>{revision.creator}</td>
                <td>
                  <button type="button" className="btn btn-link btn-sm revision-diff-button"
                          onClick={() => { setComparing(id); }}>
                    {t('weblogEdit.revisionCompare')}
                  </button>
                </td>
                <td>
                  <button type="button" className="btn btn-outline-secondary btn-sm revision-restore-button"
                          onClick={() => { restore.mutate(id); }}>
                    {t('weblogEdit.revisionRestore')}
                  </button>
                </td>
                <td>
                  {kept ? <><span className="status-pill revision-kept-marker">{t('weblogEdit.revisionKeptLabel')}</span>{' '}</> : null}
                  <button type="button" className="btn btn-link btn-sm revision-keep-button"
                          onClick={() => { keep.mutate({ revisionId: id, kept }); }}>
                    {kept ? t('weblogEdit.revisionRelease') : t('weblogEdit.revisionKeep')}
                  </button>
                </td>
              </tr>,
              ...(revision.note === undefined || revision.note === '' ? [] : [
                <tr key={`${id}:note`} className="revision-note-row">
                  <td colSpan={5} className="revision-note">{revision.note}</td>
                </tr>
              ])
            ];
          })}
        </tbody>
      </table>
      {comparing === null ? null : (
        <DiffModal handle={handle} pageId={pageId} revisionId={comparing} onClose={() => { setComparing(null); }} />
      )}
    </div>
  );
}
