import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { usePages, type PageView } from '../../api/pages';
import { call } from '../../api/client';
import { useMeta, useWeblog } from '../../api/queries';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { EmptyState } from '../../components/EmptyState';
import { ProblemBanner } from '../../components/ProblemBanner';
import { StatusPill } from '../../components/StatusPill';
import { useApi } from '../../context';
import { PageTitle } from '../../frame/PageTitle';
import { useT } from '../../i18n/messages';
import { Markup, markupArg } from '../../components/Markup';
import { runBulk } from '../../lib/bulk';
import { isSiteSpace, pageUrl, weblogBase } from './urls';


interface Progress {
  done: number;
  total: number;
}

/** The pages list (Pages.jsp): reorder, move to the trash, and the way into the editor and the trash. */
export function PagesList() {
  const t = useT();
  const api = useApi();
  const queries = useQueryClient();
  const handle = useParams()['handle'] ?? '';
  const pagesQuery = usePages(handle);
  const trashQuery = usePages(handle, 'TRASHED');
  const weblog = useWeblog(handle).data;
  const siteUrl = useMeta().data?.siteUrl;
  const base = weblogBase(handle, weblog, siteUrl);

  const pages = pagesQuery.data ?? [];
  const [order, setOrder] = useState<string[] | null>(null);
  const [dragging, setDragging] = useState<number | null>(null);
  const [confirming, setConfirming] = useState<PageView | null>(null);
  const [messages, setMessages] = useState<string[]>([]);
  const [errors, setErrors] = useState<string[]>([]);
  const [progress, setProgress] = useState<Progress | null>(null);

  const loadedIds = pages.map((p) => p.id ?? '');
  const ids = order ?? loadedIds;
  const byId = new Map(pages.map((p) => [p.id ?? '', p]));
  const rows = ids.flatMap((id) => byId.get(id) ?? []);
  const dirty = order !== null && order.join(',') !== loadedIds.join(',');
  const trashed = trashQuery.data?.length ?? 0;

  const move = (from: number, to: number) => {
    if (to < 0 || to >= ids.length || from === to) {
      return;
    }
    const next = [...ids];
    next.splice(to, 0, next.splice(from, 1)[0] ?? '');
    setOrder(next);
  };

  const saveOrder = async () => {
    setMessages([]);
    setErrors([]);
    // Only pages whose position changed need a call: the order is the pages' own navOrder, 0..n-1.
    const changed = rows.flatMap((page, index) => (page.navOrder === index ? [] : [{ page, index }]));
    setProgress({ done: 0, total: changed.length });
    const failed = await runBulk(changed, async ({ page, index }) => {
      await call(api.PATCH('/v1/weblogs/{handle}/pages/{id}', {
        params: { path: { handle, id: page.id ?? '' } }, body: { navOrder: index }
      }));
    }, (done) => { setProgress({ done, total: changed.length }); });
    setProgress(null);
    await queries.invalidateQueries({ queryKey: ['pages', handle] });
    if (failed.length === 0) {
      setOrder(null);
      setMessages([t('weblogPagesForm.orderSaved')]);
    } else {
      setErrors([
        t('weblogPagesForm.orderFailed', failed.length),
        ...failed.map(({ item, error }) => `/${item.page.slug ?? ''}: ${error instanceof Error ? error.message : String(error)}`)
      ]);
    }
  };

  const trash = useMutation({
    mutationFn: async (page: PageView) => {
      await call(api.DELETE('/v1/weblogs/{handle}/pages/{id}', { params: { path: { handle, id: page.id ?? '' } } }));
      return page;
    },
    onSuccess: async (page) => {
      setConfirming(null);
      setOrder(null);
      setMessages([t('pages.trash.moved', page.slug ?? '')]);
      setErrors([]);
      await queries.invalidateQueries({ queryKey: ['pages', handle] });
    },
    onError: () => { setConfirming(null); }
  });

  const addTo = 'new';
  const subtitle = isSiteSpace(handle) ? t('weblogPagesForm.subtitle.site') : t('weblogPagesForm.subtitle', markupArg(handle));
  const frontPage = weblog?.frontPage;

  return (
    <>
      <PageTitle>{t('weblogPagesForm.title')}</PageTitle>
      <p className="subtitle"><Markup text={subtitle} /></p>
      <ProblemBanner messages={messages} errors={errors}
                     error={pagesQuery.error ?? trash.error} />
      <div className="list-actions">
        {pages.length > 0 ? (
          <>
            <Link to={addTo} className="btn btn-primary btn-sm">{t('weblogPagesForm.add')}</Link>
            <button type="button" id="pagesOrderSave" className="btn btn-primary btn-sm" hidden={!dirty || progress !== null}
                    onClick={() => { void saveOrder(); }}>
              {t('weblogPagesForm.saveOrder')}
            </button>
          </>
        ) : null}
        {trashed > 0 ? (
          <Link to="trash" className="btn btn-outline-secondary btn-sm pages-trash-link">
            {t('pages.trash.link', trashed)}
          </Link>
        ) : null}
      </div>
      {progress === null ? null : (
        <p className="pagetip" role="status" aria-live="polite">
          {t('weblogPagesForm.orderSaving', progress.done, progress.total)}
        </p>
      )}

      {pages.length === 0 ? (
        pagesQuery.isSuccess ? (
          <EmptyState title={t('empty.pages.title')} body={t('empty.pages.body')}>
            <Link to={addTo} className="btn btn-primary">{t('weblogPagesForm.add')}</Link>
          </EmptyState>
        ) : null
      ) : (
        <div className="table-responsive">
          <table className="pressroomtable table table-striped" width="100%">
            <thead>
              <tr>
                <th scope="col" className="pressroomtable">{t('weblogPagesForm.slug')}</th>
                <th scope="col" className="pressroomtable">{t('weblogEdit.title')}</th>
                <th scope="col" className="pressroomtable" style={{ width: '10%' }}>{t('weblogEdit.status')}</th>
                <th scope="col" className="pressroomtable" style={{ width: '10%' }}>{t('weblogPagesForm.showInNav')}</th>
                <th scope="col" className="pressroomtable" style={{ width: '10%' }}>{t('weblogPagesForm.navOrder')}</th>
                <th scope="col" className="pressroomtable row-actions">
                  <span className="visually-hidden">{t('mainPage.actions')}</span>
                </th>
              </tr>
            </thead>
            <tbody id="pagesList">
              {rows.map((p, index) => {
                const title = p.title ?? '';
                const home = frontPage !== undefined && frontPage === p.id;
                const href = p.status === 'PUBLISHED' ? pageUrl(base, p.slug ?? '', home) : undefined;
                const edit = p.id ?? '';
                return (
                  <tr key={p.id} className="page-row" draggable data-page-id={p.id} data-page-slug={p.slug}
                      onDragStart={() => { setDragging(index); }}
                      onDragOver={(event) => { event.preventDefault(); }}
                      onDrop={(event) => {
                        event.preventDefault();
                        if (dragging !== null) {
                          move(dragging, index);
                        }
                        setDragging(null);
                      }}>
                    <td className="data">
                      {href === undefined ? `/${p.slug ?? ''}` : (
                        <a href={href} target="_blank" rel="noopener">{`/${p.slug ?? ''}`}</a>
                      )}
                    </td>
                    <td>
                      <Link to={edit}>{title}</Link>
                      {home ? <> <span className="status-pill page-home-marker">{t('weblogPagesForm.homePage')}</span></> : null}
                    </td>
                    <td><StatusPill status={p.status ?? 'DRAFT'} /></td>
                    <td>{p.showInNav === true ? '✓' : ''}</td>
                    <td className="data page-nav-order">{order === null ? p.navOrder : index}</td>
                    <td className="row-actions">
                      <span className="bi bi-grip-vertical page-order-handle" aria-hidden="true" />
                      <button type="button" className="row-action page-order-up"
                              aria-label={`${t('weblogPagesForm.moveUp')}: ${title}`}
                              onClick={() => { move(index, index - 1); }}>
                        <span className="bi bi-arrow-up" aria-hidden="true" title={t('weblogPagesForm.moveUp')} />
                      </button>
                      <button type="button" className="row-action page-order-down"
                              aria-label={`${t('weblogPagesForm.moveDown')}: ${title}`}
                              onClick={() => { move(index, index + 1); }}>
                        <span className="bi bi-arrow-down" aria-hidden="true" title={t('weblogPagesForm.moveDown')} />
                      </button>
                      <Link to={edit} className="row-action" aria-label={`${t('generic.edit')}: ${title}`}>
                        <span className="bi bi-pencil-square" aria-hidden="true" title={t('generic.edit')} />
                      </Link>
                      <button type="button" className="row-action row-action-danger page-delete-btn"
                              aria-label={`${t('pages.trash.move')}: ${title}`}
                              onClick={() => { setConfirming(p); }}>
                        <span className="bi bi-trash" aria-hidden="true" title={t('pages.trash.move')} />
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {confirming === null ? null : (
        <ConfirmDialog
          title={trashQuestion(t, confirming, frontPage === confirming.id)}
          confirmLabel={t('pages.trash.move')}
          onConfirm={() => { trash.mutate(confirming); }}
          onCancel={() => { setConfirming(null); }} />
      )}
    </>
  );
}

/** "Move this page to the trash: 'X'?" plus what it does to a strand's posts and the blog's root. */
export function trashQuestion(t: ReturnType<typeof useT>, page: PageView, home: boolean): string {
  const parts = page.parts?.length ?? 0;
  return [
    `${t('weblogPagesForm.removeConfirm')}: '${page.title ?? ''}'?`,
    parts > 0 ? t('pages.deleteStrandConfirm', parts) : '',
    home ? t('pages.deleteHomeConfirm') : ''
  ].filter((s) => s !== '').join(' ');
}
