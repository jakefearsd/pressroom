import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { call } from '../api/client';
import { COMMENTS_PAGE, useCommentQueue, type QueueComment } from '../api/listQueries';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { DateTime } from '../components/DateTime';
import { EmptyState } from '../components/EmptyState';
import { Pager } from '../components/Pager';
import { ProblemBanner } from '../components/ProblemBanner';
import { useApi } from '../context';
import { PageTitle } from '../frame/PageTitle';
import { useT } from '../i18n/messages';
import { failureText } from '../lib/errors';

/** The comments waiting for approval on every blog the caller may moderate, with the blog named on each row. */
export function CommentQueue() {
  const t = useT();
  const api = useApi();
  const queries = useQueryClient();
  const [params] = useSearchParams();
  const offset = Math.max(0, Number(params.get('offset') ?? 0) || 0);
  const list = useCommentQueue('PENDING', offset);
  const [done, setDone] = useState<string[]>([]);
  const [failed, setFailed] = useState<string[]>([]);
  const [deleting, setDeleting] = useState<QueueComment | null>(null);
  const [busy, setBusy] = useState(false);
  const hrefFor = (to: number) => (to === 0 ? '/comments' : `/comments?offset=${to}`);

  // The queue, the blog's own list and the menu counts (rail badge, home row) follow every moderation.
  const act = async (c: QueueComment, run: (handle: string, id: string) => Promise<unknown>, doneKey: string) => {
    const handle = c.weblog.handle;
    setBusy(true);
    setDone([]);
    setFailed([]);
    try {
      await run(handle, c.id ?? '');
      setDone([t(doneKey, 1)]);
    } catch (failure) {
      setFailed([t('bulk.itemFailed', c.authorUserName ?? c.id ?? '', failureText(failure))]);
    } finally {
      setBusy(false);
    }
    await Promise.all([
      queries.invalidateQueries({ queryKey: ['commentQueue'] }),
      queries.invalidateQueries({ queryKey: ['comments', handle] }),
      queries.invalidateQueries({ queryKey: ['me', 'weblogs'] })
    ]);
  };
  const setStatus = (to: 'PUBLISHED' | 'HIDDEN') => (handle: string, id: string) =>
    call(api.PATCH('/v1/weblogs/{handle}/comments/{id}', { params: { path: { handle, id } }, body: { status: to } }));
  const remove = (handle: string, id: string) =>
    call(api.DELETE('/v1/weblogs/{handle}/comments/{id}', { params: { path: { handle, id } } }));

  const items = list.data?.items ?? [];
  return (
    <>
      <PageTitle>{t('commentQueue.title')}</PageTitle>
      <div id="comment-queue-marker">
        <ProblemBanner messages={done} errors={failed} error={list.error} />
        {list.data === undefined ? null : items.length === 0 ? (
          <>
            <Pager offset={offset} limit={COMMENTS_PAGE} hasMore={false} hrefFor={hrefFor} />
            <EmptyState title={t('comments.none')} body={t('commentQueue.none')} />
          </>
        ) : (
          <>
            <Pager offset={offset} limit={COMMENTS_PAGE} hasMore={list.data.hasMore} hrefFor={hrefFor} />
            <div className="table-responsive">
              <table className="pressroomtable table table-striped" width="100%">
                <thead>
                  <tr>
                    <th scope="col" className="pressroomtable">{t('commentQueue.column.blog')}</th>
                    <th scope="col" className="pressroomtable">{t('comments.column.writer')}</th>
                    <th scope="col" className="pressroomtable">{t('comments.column.comment')}</th>
                    <th scope="col" className="pressroomtable">{t('comments.column.entry')}</th>
                    <th scope="col" className="pressroomtable">{t('comments.column.date')}</th>
                    <th scope="col" className="pressroomtable" />
                  </tr>
                </thead>
                <tbody>
                  {items.map((c) => (
                    <tr key={`${c.weblog.handle}/${c.id ?? ''}`}>
                      <td className="comment-queue-blog">
                        <Link to={`/weblogs/${encodeURIComponent(c.weblog.handle)}/comments?status=PENDING`}>{c.weblog.name}</Link>
                      </td>
                      <td>
                        <strong>{c.authorDisplayName}</strong><br />
                        <span className="data">{c.authorUserName}</span>
                      </td>
                      <td><div style={{ whiteSpace: 'pre-wrap' }}>{c.body}</div></td>
                      <td>
                        <Link to={`/weblogs/${encodeURIComponent(c.weblog.handle)}/entries/${encodeURIComponent(c.entryId ?? '')}`}>{c.entryTitle}</Link>
                      </td>
                      <td className="data"><DateTime value={c.created} /></td>
                      <td>
                        <button type="button" className="btn btn-outline-primary btn-sm" disabled={busy}
                                onClick={() => { void act(c, setStatus('PUBLISHED'), 'comments.approved'); }}>
                          {t('comments.approve')}
                        </button>
                        <button type="button" className="btn btn-outline-secondary btn-sm" disabled={busy}
                                onClick={() => { void act(c, setStatus('HIDDEN'), 'comments.hidden'); }}>
                          {t('comments.hide')}
                        </button>
                        <button type="button" className="btn btn-outline-danger btn-sm" disabled={busy}
                                onClick={() => { setDeleting(c); }}>
                          {t('comments.delete')}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager offset={offset} limit={COMMENTS_PAGE} hasMore={list.data.hasMore} hrefFor={hrefFor} />
          </>
        )}
      </div>
      {deleting === null ? null : (
        <ConfirmDialog title={t('comments.deleteOne.confirm')} confirmLabel={t('comments.delete')}
                       onConfirm={() => { const target = deleting; setDeleting(null); void act(target, remove, 'comments.deleted'); }}
                       onCancel={() => { setDeleting(null); }} />
      )}
    </>
  );
}
