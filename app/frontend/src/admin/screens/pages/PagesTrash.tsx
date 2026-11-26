import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { call } from '../../api/client';
import { usePages, type PageView } from '../../api/pages';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { DateTime } from '../../components/DateTime';
import { EmptyState } from '../../components/EmptyState';
import { ProblemBanner } from '../../components/ProblemBanner';
import { useApi } from '../../context';
import { PageTitle } from '../../frame/PageTitle';
import { useT } from '../../i18n/messages';
import { Subtitle } from './Subtitle';
import { isSiteSpace } from './urls';

/**
 * The page trash (PagesTrash.jsp): restore a page as a draft, or delete it for good.
 * Not reproduced: the retention sentence and the "Deleted permanently" date column, which
 * need the retention setting the API does not expose yet.
 */
export function PagesTrash() {
  const t = useT();
  const api = useApi();
  const queries = useQueryClient();
  const handle = useParams()['handle'] ?? '';
  const trashQuery = usePages(handle, 'TRASHED');
  const [messages, setMessages] = useState<string[]>([]);
  const [confirming, setConfirming] = useState<PageView | null>(null);

  const refresh = () => Promise.all([
    queries.invalidateQueries({ queryKey: ['pages', handle] }),
    queries.invalidateQueries({ queryKey: ['page', handle] })
  ]);
  const restore = useMutation({
    mutationFn: async (page: PageView) => {
      await call(api.POST('/v1/weblogs/{handle}/pages/{id}/restore', { params: { path: { handle, id: page.id ?? '' } } }));
      return page;
    },
    onSuccess: async (page) => {
      setMessages([t('pages.trash.restored', page.slug ?? '')]);
      await refresh();
    }
  });
  const forever = useMutation({
    mutationFn: async (page: PageView) => {
      await call(api.POST('/v1/weblogs/{handle}/pages/{id}/delete-forever', { params: { path: { handle, id: page.id ?? '' } } }));
      return page;
    },
    onSuccess: async (page) => {
      setConfirming(null);
      setMessages([t('pages.trash.deletedForever', page.slug ?? '')]);
      await refresh();
    },
    onError: () => { setConfirming(null); }
  });

  const pages = trashQuery.data ?? [];
  const subtitle = isSiteSpace(handle) ? t('pages.trash.subtitle.site') : t('pages.trash.subtitle', handle);

  return (
    <>
      <PageTitle>{t('pages.trash.title')}</PageTitle>
      <Subtitle text={subtitle} />
      <ProblemBanner messages={messages} error={trashQuery.error ?? restore.error ?? forever.error} />
      <div className="list-actions">
        <Link to=".." relative="path" className="btn btn-outline-secondary btn-sm">{t('pages.trash.backToPages')}</Link>
      </div>
      <div id="pages-trash-list-marker">
        {pages.length > 0 ? (
          <div className="table-responsive">
            <table className="pressroomtable table table-striped" width="100%">
              <thead>
                <tr>
                  <th scope="col" className="pressroomtable">{t('weblogPagesForm.slug')}</th>
                  <th scope="col" className="pressroomtable">{t('weblogEdit.title')}</th>
                  <th scope="col" className="pressroomtable" style={{ width: '15%' }}>{t('trash.column.trashed')}</th>
                  <th scope="col" className="pressroomtable" style={{ width: '10%' }} />
                  <th scope="col" className="pressroomtable" style={{ width: '10%' }} />
                </tr>
              </thead>
              <tbody>
                {pages.map((p) => (
                  <tr key={p.id} className="trashed-page-row" data-page-slug={p.slug}>
                    <td className="data">{`/${p.slug ?? ''}`}</td>
                    <td>{p.title}</td>
                    <td className="data"><DateTime value={p.trashedAt} /></td>
                    <td>
                      <button type="button" className="btn btn-outline-secondary btn-sm page-restore-btn"
                              onClick={() => { restore.mutate(p); }}>
                        {t('pages.trash.restore')}
                      </button>
                    </td>
                    <td>
                      <button type="button" className="btn btn-link p-0 align-baseline border-0 text-danger page-delete-forever-btn"
                              onClick={() => { setConfirming(p); }}>
                        {t('pages.trash.deleteForever')}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : trashQuery.isSuccess ? (
          <EmptyState title={t('pages.trash.none.title')} body={t('pages.trash.none.body')} />
        ) : null}
      </div>
      {confirming === null ? null : (
        <ConfirmDialog
          title={`${t('pages.trash.deleteForeverConfirm')}: '${confirming.title ?? ''}'?`}
          confirmLabel={t('pages.trash.deleteForever')}
          onConfirm={() => { forever.mutate(confirming); }}
          onCancel={() => { setConfirming(null); }}>
          {confirming.strand === true ? <p>{t('pages.trash.deleteForeverStrandConfirm')}</p> : null}
        </ConfirmDialog>
      )}
    </>
  );
}
