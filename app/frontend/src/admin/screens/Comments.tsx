import { useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { call } from '../api/client';
import { COMMENTS_PAGE, useComments, type CommentView } from '../api/listQueries';
import { ApiError } from '../api/problem';
import { BulkProgress } from '../components/BulkProgress';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { DateTime } from '../components/DateTime';
import { EmptyState } from '../components/EmptyState';
import { Pager } from '../components/Pager';
import { ProblemBanner } from '../components/ProblemBanner';
import { SelectionBar } from '../components/SelectionBar';
import { useApi } from '../context';
import { useFieldErrors } from '../forms/useFieldErrors';
import { PageTitle } from '../frame/PageTitle';
import { useT } from '../i18n/messages';
import { useBulk } from '../lib/bulk';
import { failureText } from '../lib/errors';

const TABS = ['PENDING', 'PUBLISHED', 'HIDDEN'] as const;
type Tab = (typeof TABS)[number];

/** The three tab labels and the three empty-state sentences are keyed per tab (listed for MessageKeyTest). */
const TAB_KEYS: Record<Tab, { label: string; none: string }> = {
  PENDING: { label: 'comments.tab.pending', none: 'comments.none.pending' },
  PUBLISHED: { label: 'comments.tab.published', none: 'comments.none.published' },
  HIDDEN: { label: 'comments.tab.hidden', none: 'comments.none.hidden' }
};

/** A HIDDEN comment with no body is one its author deleted: it can only be deleted for good. */
const isTombstone = (c: CommentView) => c.status === 'HIDDEN' && (c.body ?? '') === '';

function search(params: URLSearchParams, changes: Record<string, string | null>): string {
  const next = new URLSearchParams(params);
  for (const [name, value] of Object.entries(changes)) {
    if (value === null) { next.delete(name); } else { next.set(name, value); }
  }
  const query = next.toString();
  return query === '' ? '' : `?${query}`;
}

/** Reader-comment moderation (Comments.jsp): tabs, paging, row and bulk actions, replies. */
export function Comments() {
  const t = useT();
  const handle = useParams()['handle'] ?? '';
  const [params] = useSearchParams();
  const requested = params.get('status');
  const status: Tab = TABS.find((tab) => tab === requested) ?? 'PENDING';
  const entryId = params.get('entry') ?? undefined;
  const offset = Math.max(0, Number(params.get('offset') ?? 0) || 0);
  const list = useComments(handle, { status, entryId, offset });
  const base = `/weblogs/${encodeURIComponent(handle)}/comments`;
  const hrefFor = (to: number) => `${base}${search(params, { offset: to === 0 ? null : String(to) })}`;

  return (
    <>
      <PageTitle>{t('comments.title')}</PageTitle>
      <nav className="nav nav-tabs" aria-label={t('comments.title')}>
        {TABS.map((tab) => (
          <Link key={tab} to={`${base}${search(params, { status: tab, offset: null })}`}
                className={`nav-link${status === tab ? ' active' : ''}`}
                {...(status === tab ? { 'aria-current': 'page' as const } : {})}>
            {t(TAB_KEYS[tab].label)}
          </Link>
        ))}
      </nav>
      {status === 'HIDDEN' ? null : <p className="form-text">{t('comments.hideVsDelete')}</p>}
      {list.error !== null ? <ProblemBanner error={list.error} /> : null}
      {list.data === undefined ? null : (
        <>
          <Pager offset={offset} limit={COMMENTS_PAGE} hasMore={list.data.hasMore} hrefFor={hrefFor} />
          <CommentRows key={params.toString()} handle={handle} status={status} comments={list.data.items} />
          <Pager offset={offset} limit={COMMENTS_PAGE} hasMore={list.data.hasMore} hrefFor={hrefFor} />
        </>
      )}
    </>
  );
}

function CommentRows({ handle, status, comments }: { handle: string; status: Tab; comments: CommentView[] }) {
  const t = useT();
  const api = useApi();
  const queries = useQueryClient();
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [messages, setMessages] = useState<string[]>([]);
  const [errors, setErrors] = useState<string[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [deleting, setDeleting] = useState<{ targets: CommentView[]; many: boolean } | null>(null);
  const bulk = useBulk<CommentView>();

  const refresh = () => queries.invalidateQueries({ queryKey: ['comments', handle] });
  const path = (c: CommentView) => ({ params: { path: { handle, id: c.id ?? '' } } });
  const reset = () => { setMessages([]); setErrors([]); setError(null); };
  const chosen = comments.filter((c) => c.id !== undefined && selected.has(c.id));

  const moderate = async (targets: CommentView[], action: (c: CommentView) => Promise<unknown>, doneKey: string) => {
    reset();
    const outcome = await bulk.run(targets, action);
    setMessages(outcome.ok > 0 || outcome.failed.length === 0 ? [t(doneKey, outcome.ok)] : []);
    setErrors(outcome.failed.map((f) => t('bulk.itemFailed', f.item.authorUserName ?? f.item.id ?? '', failureText(f.error))));
    setSelected(new Set());
    await refresh();
  };

  const setStatus = (to: 'PUBLISHED' | 'HIDDEN') => (c: CommentView) =>
    call(api.PATCH('/v1/weblogs/{handle}/comments/{id}', { ...path(c), body: { status: to } }));
  const remove = (c: CommentView) => call(api.DELETE('/v1/weblogs/{handle}/comments/{id}', path(c)));
  const approve = (targets: CommentView[]) => moderate(targets, setStatus('PUBLISHED'), 'comments.approved');
  const hide = (targets: CommentView[]) => moderate(targets, setStatus('HIDDEN'), 'comments.hidden');
  const deleteThem = async (targets: CommentView[]) => {
    setDeleting(null);
    await moderate(targets, remove, 'comments.deleted');
  };
  const toggle = (id: string, on: boolean) => {
    setSelected((now) => {
      const next = new Set(now);
      if (on) { next.add(id); } else { next.delete(id); }
      return next;
    });
  };

  if (comments.length === 0) {
    return (
      <>
        <ProblemBanner messages={messages} errors={errors} error={error} />
        <EmptyState title={t('comments.none')} body={t(TAB_KEYS[status].none)} />
      </>
    );
  }

  const busy = bulk.progress !== null;
  return (
    <>
      <ProblemBanner messages={messages} errors={errors} error={error} />
      <SelectionBar count={chosen.length} formId="commentsModerationForm">
        <button type="button" className="btn btn-primary" disabled={busy} onClick={() => { void approve(chosen); }}>
          {t('comments.approve')}
        </button>
        <button type="button" className="btn btn-secondary" disabled={busy} onClick={() => { void hide(chosen); }}>
          {t('comments.hide')}
        </button>
        <button type="button" className="btn btn-danger" id="commentsDeleteSelected" disabled={busy}
                onClick={() => { setDeleting({ targets: chosen, many: true }); }}>
          {t('comments.delete')}
        </button>
      </SelectionBar>
      <BulkProgress progress={bulk.progress} />
      <div className="table-responsive">
        <table className="pressroomtable table table-striped" width="100%">
          <thead>
            <tr>
              <th scope="col" className="pressroomtable" style={{ width: '3%' }}>
                <input type="checkbox" id="selectAllComments" className="form-check-input" title={t('weblogEntryQuery.selectAll')}
                       checked={chosen.length === comments.length}
                       onChange={(event) => { setSelected(event.target.checked ? new Set(comments.flatMap((c) => (c.id === undefined ? [] : [c.id]))) : new Set()); }} />
              </th>
              <th scope="col" className="pressroomtable">{t('comments.column.writer')}</th>
              <th scope="col" className="pressroomtable">{t('comments.column.comment')}</th>
              <th scope="col" className="pressroomtable" style={{ width: '20%' }}>{t('comments.column.entry')}</th>
              <th scope="col" className="pressroomtable">{t('comments.column.date')}</th>
              <th scope="col" className="pressroomtable" />
            </tr>
          </thead>
          <tbody>
            {comments.map((c) => {
              const id = c.id ?? '';
              const tombstone = isTombstone(c);
              return (
                <tr key={id}>
                  <td>
                    <input type="checkbox" className="form-check-input comment-select" aria-label={c.authorUserName}
                           checked={selected.has(id)} onChange={(event) => { toggle(id, event.target.checked); }} />
                  </td>
                  <td>
                    <strong>{c.authorDisplayName}</strong>
                    {c.authorIsOwner === true ? <> <span className="status-pill">{t('comments.author')}</span></> : null}
                    <br />
                    <span className="data">{c.authorUserName}</span>
                  </td>
                  <td>
                    {tombstone ? <em>{t('comments.tombstone')}</em> : <div style={{ whiteSpace: 'pre-wrap' }}>{c.body}</div>}
                    {c.reply !== undefined && c.reply !== '' ? (
                      <div className="ms-3 mt-2">
                        <span className="status-pill">{t('comments.author')}</span>{' '}
                        <span style={{ whiteSpace: 'pre-wrap' }}>{c.reply}</span>
                      </div>
                    ) : null}
                    {c.status === 'PUBLISHED' && c.replyTo === undefined && (c.reply === undefined || c.reply === '') ? (
                      <ReplyForm handle={handle} comment={c} onPosted={() => { reset(); setMessages([t('comments.replied')]); void refresh(); }} />
                    ) : null}
                  </td>
                  <td>
                    <Link to={`/weblogs/${encodeURIComponent(handle)}/entries/${encodeURIComponent(c.entryId ?? '')}`}>{c.entryTitle}</Link>
                  </td>
                  <td className="data"><DateTime value={c.created} /></td>
                  <td>
                    {c.status !== 'PUBLISHED' && !tombstone ? (
                      <button type="button" className="btn btn-outline-primary btn-sm" disabled={busy} onClick={() => { void approve([c]); }}>
                        {t('comments.approve')}
                      </button>
                    ) : null}
                    {c.status !== 'HIDDEN' ? (
                      <button type="button" className="btn btn-outline-secondary btn-sm" disabled={busy} onClick={() => { void hide([c]); }}>
                        {t('comments.hide')}
                      </button>
                    ) : null}
                    <button type="button" className="btn btn-outline-danger btn-sm" disabled={busy} onClick={() => { setDeleting({ targets: [c], many: false }); }}>
                      {t('comments.delete')}
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {deleting === null ? null : !deleting.many ? (
        <ConfirmDialog title={t('comments.deleteOne.confirm')} confirmLabel={t('comments.delete')}
                       onConfirm={() => { void deleteThem(deleting.targets); }} onCancel={() => { setDeleting(null); }} />
      ) : (
        <ConfirmDialog title={t('comments.bulkDeleteConfirm')} confirmLabel={t('comments.delete')}
                       onConfirm={() => { void deleteThem(deleting.targets); }} onCancel={() => { setDeleting(null); }}>
          <p>{t('comments.bulkDeleteWarning')}</p>
          <p className="data">{t('selection.count', deleting.targets.length)}</p>
        </ConfirmDialog>
      )}
    </>
  );
}

/** The `<details>` reply box under a published comment that has no reply yet. */
function ReplyForm({ handle, comment, onPosted }: { handle: string; comment: CommentView; onPosted: () => void }) {
  const t = useT();
  const api = useApi();
  const [body, setBody] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [posting, setPosting] = useState(false);
  const bodyId = `reply-body-${comment.id ?? ''}`;
  const fields = useFieldErrors(error, () => bodyId);

  const post = async () => {
    setPosting(true);
    setError(null);
    try {
      await call(api.POST('/v1/weblogs/{handle}/comments/{id}/replies', {
        params: { path: { handle, id: comment.id ?? '' } }, body: { body }
      }));
      setBody('');
      onPosted();
    } catch (failure) {
      setError(failure);
    } finally {
      setPosting(false);
    }
  };

  return (
    <details>
      <summary>{t('comments.reply')}</summary>
      <textarea {...fields.props('body', 'form-control')} rows={3} maxLength={4000} required value={body}
                aria-label={t('comments.reply')} onChange={(event) => { setBody(event.target.value); }} />
      {error === null ? null : (
        <div className="invalid-feedback d-block" role="alert">
          {error instanceof ApiError ? error.messages.join(' ') : failureText(error)}
        </div>
      )}
      <button type="button" className="btn btn-primary btn-sm" disabled={posting || body.trim() === ''} onClick={() => { void post(); }}>
        {t('comments.reply.post')}
      </button>
    </details>
  );
}
