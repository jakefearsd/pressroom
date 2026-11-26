import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { runPool } from '../../api/pool';
import { SUBMISSIONS_PAGE_SIZE, useSubmissionActions, useSubmissions, type SubmissionView } from '../../api/people';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { DateTime } from '../../components/DateTime';
import { EmptyState } from '../../components/EmptyState';
import { Markup, markupArg } from '../../components/Markup';
import { ProblemBanner } from '../../components/ProblemBanner';
import { SelectionBar } from '../../components/SelectionBar';
import { PageTitle } from '../../frame/PageTitle';
import { useT } from '../../i18n/messages';
import { RequireLevel, bannerError, failureLine, useHandle } from './shared';

const BULK_CONCURRENCY = 4;
const PREVIEW_LENGTH = 140;

export function Inbox() {
  const handle = useHandle();
  return <RequireLevel level="post" handle={handle}><InboxBody handle={handle} /></RequireLevel>;
}

function pageOf(raw: string | null): number {
  const page = Number.parseInt(raw ?? '', 10);
  return Number.isFinite(page) && page > 0 ? page : 0;
}

function source(s: SubmissionView): string {
  if (s.entryAnchor !== undefined && s.entryAnchor !== '') {
    return s.entryAnchor;
  }
  return s.pageSlug !== undefined && s.pageSlug !== '' ? `/${s.pageSlug}` : '—';
}

function InboxBody({ handle }: { handle: string }) {
  const t = useT();
  const [params] = useSearchParams();
  const page = pageOf(params.get('page'));
  const query = useSubmissions(handle, page);
  const actions = useSubmissionActions(handle);
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [confirming, setConfirming] = useState(false);
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
  const [notice, setNotice] = useState<string[]>([]);
  const [problems, setProblems] = useState<string[]>([]);

  const items = query.data?.items ?? [];
  const total = query.data?.total ?? 0;
  const base = `/weblogs/${encodeURIComponent(handle)}/inbox`;
  const allChecked = items.length > 0 && items.every((s) => selected.has(s.id));

  const toggle = (id: string, on: boolean) => {
    setSelected((current) => {
      const next = new Set(current);
      if (on) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  };

  const deleteSelected = async () => {
    setConfirming(false);
    const doomed = items.filter((s) => selected.has(s.id));
    setNotice([]);
    setProblems([]);
    setProgress({ done: 0, total: doomed.length });
    const failures = await runPool(doomed, BULK_CONCURRENCY, (s) => actions.remove(s.id),
      (done) => { setProgress({ done, total: doomed.length }); });
    await actions.refresh();
    setProgress(null);
    setSelected(new Set());
    const removed = doomed.length - failures.length;
    setNotice(removed > 0 ? [t('submissions.deleted', String(removed))] : []);
    setProblems(failures.map((f) => failureLine(f.item.subject ?? f.item.id, f.error)));
  };

  return (
    <>
      <PageTitle>{t('submissions.title')}</PageTitle>
      <ProblemBanner messages={notice} errors={problems} error={bannerError(query.error)} />
      <p className="subtitle"><Markup text={t('submissions.subtitle', markupArg(handle))} /></p>

      {page > 0 || (page + 1) * SUBMISSIONS_PAGE_SIZE < total ? (
        <nav aria-label={t('pager.label')}>
          <div className="d-flex justify-content-between">
            {page > 0 ? (
              <Link to={`${base}?page=${String(page - 1)}`} className="btn btn-outline-secondary previous">
                <span aria-hidden="true">&larr;</span> {t('pager.newer')}
              </Link>
            ) : null}
            {(page + 1) * SUBMISSIONS_PAGE_SIZE < total ? (
              <Link to={`${base}?page=${String(page + 1)}`} className="btn btn-outline-secondary next ms-auto">
                {t('pager.older')} <span aria-hidden="true">&rarr;</span>
              </Link>
            ) : null}
          </div>
        </nav>
      ) : null}

      {query.data !== undefined && items.length === 0 ? (
        <EmptyState title={t('empty.inquiries.title')} body={t('empty.inquiries.body')} />
      ) : null}
      {items.length > 0 ? (
        <form id="submissionsDeleteForm" onSubmit={(e) => { e.preventDefault(); }}>
          <SelectionBar count={selected.size} formId="submissionsDeleteForm">
            <button type="button" className="btn btn-danger" id="submissionsDeleteSelected"
                    disabled={progress !== null} onClick={() => { setConfirming(true); }}>
              {t('generic.delete.selected')}
            </button>
            {progress === null ? null : (
              <span className="data" role="status">{t('submissions.deleteProgress', progress.done, progress.total)}</span>
            )}
          </SelectionBar>
          <div className="table-responsive">
            <table className="pressroomtable table table-striped" width="100%">
              <thead>
                <tr>
                  <th scope="col" className="pressroomtable" style={{ width: '3%' }}>
                    <input type="checkbox" id="selectAllSubmissions" className="form-check-input"
                           title={t('weblogEntryQuery.selectAll')} aria-label={t('weblogEntryQuery.selectAll')}
                           checked={allChecked}
                           onChange={(e) => { setSelected(e.target.checked ? new Set(items.map((s) => s.id)) : new Set()); }} />
                  </th>
                  <th scope="col" className="pressroomtable">{t('submissions.column.received')}</th>
                  <th scope="col" className="pressroomtable">{t('submissions.column.from')}</th>
                  <th scope="col" className="pressroomtable">{t('submissions.column.subject')}</th>
                  <th scope="col" className="pressroomtable">{t('submissions.column.message')}</th>
                  <th scope="col" className="pressroomtable">{t('submissions.column.source')}</th>
                </tr>
              </thead>
              <tbody>
                {items.map((s) => {
                  const message = s.message ?? '';
                  const long = message.length > PREVIEW_LENGTH;
                  return (
                    <tr key={s.id}>
                      <td>
                        <input type="checkbox" className="form-check-input submission-select"
                               aria-label={s.subject ?? s.id} checked={selected.has(s.id)}
                               onChange={(e) => { toggle(s.id, e.target.checked); }} />
                      </td>
                      <td className="data"><DateTime value={s.created} /></td>
                      <td>
                        {s.name}<br />
                        {s.email === undefined ? null : <a href={`mailto:${s.email}`}>{s.email}</a>}
                      </td>
                      <td>{s.subject}</td>
                      <td>
                        {long ? (
                          <>
                            {message.slice(0, PREVIEW_LENGTH)}&hellip;
                            <details>
                              <summary>{t('submissions.showFullMessage')}</summary>
                              <p>{message}</p>
                            </details>
                          </>
                        ) : message}
                      </td>
                      <td>{source(s)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </form>
      ) : null}

      {confirming ? (
        <ConfirmDialog title={t('submissions.bulkDeleteConfirm')} confirmLabel={t('generic.delete')}
                       onConfirm={() => { void deleteSelected(); }} onCancel={() => { setConfirming(false); }}>
          <p>{t('submissions.bulkDeleteWarning')}</p>
          <p className="data">{t('selection.count', selected.size)}</p>
        </ConfirmDialog>
      ) : null}
    </>
  );
}
