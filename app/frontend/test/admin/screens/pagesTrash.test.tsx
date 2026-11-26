import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { appRoutes } from '../../../src/admin/router';
import { editorBoot, renderRoutes } from '../support';
import { API, makeServer, meta, page, problem, writes, writing } from './pagesSupport';

const { server, start } = makeServer();
start();

const trashed = [
  page({ id: 't1', slug: 'old', title: 'Old page', status: 'DRAFT', trashedAt: '2026-11-24T08:30:00Z' }),
  page({ id: 't2', slug: 'series', title: "Maiia's series", strand: true, trashedAt: '2026-11-23T08:30:00Z' })
];
const withTrash = () => server.use(http.get(`${API}/weblogs/:handle/pages`, ({ request }) =>
  HttpResponse.json(new URL(request.url).searchParams.get('status') === 'TRASHED' ? trashed : [])));

const open = (at = '/weblogs/alpha/pages/trash') => {
  const view = renderRoutes(appRoutes, at, { boot: editorBoot });
  return { ...view, user: userEvent.setup() };
};

const withRetention = (days: number) => server.use(
  http.get(`${API}/meta`, () => HttpResponse.json({ ...meta, pageTrashRetentionDays: days, trashRetentionDays: 99 })));

describe('PagesTrash', () => {
  it('says how long a trashed page lasts, in the three cases of the page retention setting', async () => {
    withTrash();
    withRetention(14);
    const first = open();
    expect(await screen.findByText(/deleted permanently after 14 days/)).toHaveClass('pagetip');
    first.unmount();
    withRetention(-1);
    const second = open();
    expect(await screen.findByText(/kept until you delete them here/)).toBeInTheDocument();
    second.unmount();
    withRetention(0);
    open();
    expect(await screen.findByText(/deleted permanently on the next sweep/)).toBeInTheDocument();
  });

  it('shows when each page is deleted permanently: the trashed time plus the retention days', async () => {
    withTrash();
    withRetention(30);
    open();
    const old = (await screen.findByText('Old page')).closest('tr') as HTMLElement;
    expect(screen.getByRole('columnheader', { name: 'Deleted permanently' })).toBeInTheDocument();
    const times = old.querySelectorAll('time');
    expect(times[1]).toHaveAttribute('datetime', '2026-12-24T08:30:00.000Z');
  });

  it('says a page is kept until deleted by hand when nothing expires', async () => {
    withTrash();
    withRetention(-1);
    open();
    const old = (await screen.findByText('Old page')).closest('tr') as HTMLElement;
    expect(old.querySelectorAll('time')).toHaveLength(1);
    expect(within(old).getByText('Kept until deleted by hand')).toBeInTheDocument();
  });

  it('lists trashed pages with the trashed time, never as links', async () => {
    withTrash();
    open();
    expect(await screen.findByRole('heading', { level: 1, name: 'Page trash' })).toBeInTheDocument();
    expect(document.querySelector('p.subtitle')).toHaveTextContent('Deleted pages from alpha');
    const old = await screen.findByText('Old page');
    const tr = old.closest('tr') as HTMLElement;
    expect(tr).toHaveClass('trashed-page-row');
    expect(tr.querySelector('a')).toBeNull();
    expect(within(tr).getByText('/old')).toBeInTheDocument();
    expect(tr.querySelector('time')).toHaveAttribute('datetime', '2026-11-24T08:30:00.000Z');
    expect(document.getElementById('pages-trash-list-marker')).toContainElement(tr);
    expect(screen.getByRole('link', { name: 'Back to pages' })).toHaveAttribute('href', '/weblogs/alpha/pages');
  });

  it('says it is empty with the empty-state idiom, inside the marker', async () => {
    open('/weblogs/site-space/pages/trash');
    expect(await screen.findByText('No pages in the trash.')).toHaveClass('empty-state-title');
    expect(document.querySelector('p.subtitle')).toHaveTextContent('Deleted site pages');
    expect(document.getElementById('pages-trash-list-marker')?.querySelector('.empty-state')).not.toBeNull();
  });

  it('restores a page as a draft and says so', async () => {
    withTrash();
    server.use(writing('post', '/weblogs/alpha/pages/t1/restore', () => HttpResponse.json(page({ id: 't1' }))));
    const { user } = open();
    await screen.findByText('Old page');
    await user.click(within(screen.getByText('Old page').closest('tr') as HTMLElement).getByRole('button', { name: 'Restore' }));
    expect(await screen.findByText(/Page "old" restored as a draft/)).toBeInTheDocument();
    expect(writes).toEqual([{ method: 'POST', url: '/weblogs/alpha/pages/t1/restore', body: null }]);
  });

  it('shows a refused restore in place (an address someone else took)', async () => {
    withTrash();
    server.use(writing('post', '/weblogs/alpha/pages/t1/restore', () => problem(409, 'page.slugTaken', 'Another page already has that address.')));
    const { user } = open();
    await screen.findByText('Old page');
    await user.click(within(screen.getByText('Old page').closest('tr') as HTMLElement).getByRole('button', { name: 'Restore' }));
    expect(await screen.findByText('Another page already has that address.')).toBeInTheDocument();
  });

  it('deletes forever only after asking, and a strand adds what happens to its posts', async () => {
    withTrash();
    server.use(writing('post', '/weblogs/alpha/pages/t2/delete-forever', () => new HttpResponse(null, { status: 204 })));
    const { user } = open();
    await screen.findByText("Maiia's series");
    await user.click(within(screen.getByText("Maiia's series").closest('tr') as HTMLElement).getByRole('button', { name: 'Delete forever' }));
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent("Delete this page permanently? This cannot be undone: 'Maiia's series'?");
    expect(dialog).toHaveTextContent('Its posts stay published, outside any strand.');
    expect(writes).toEqual([]);
    await user.click(within(dialog).getByRole('button', { name: 'Delete forever' }));
    expect(await screen.findByText('Page "series" deleted permanently.')).toBeInTheDocument();
    expect(writes).toEqual([{ method: 'POST', url: '/weblogs/alpha/pages/t2/delete-forever', body: null }]);
  });

  it('keeps the plain question for a page that is not a strand, cancels cleanly, and shows a refusal', async () => {
    withTrash();
    server.use(writing('post', '/weblogs/alpha/pages/t1/delete-forever', () => problem(409, 'page.deleteForeverNeedsTrash', 'Page is not trashed; trash it first.')));
    const { user } = open();
    await screen.findByText('Old page');
    const del = () => within(screen.getByText('Old page').closest('tr') as HTMLElement).getByRole('button', { name: 'Delete forever' });
    await user.click(del());
    expect(screen.getByRole('dialog')).not.toHaveTextContent('Its posts stay published');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(writes).toEqual([]);
    await user.click(del());
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete forever' }));
    expect(await screen.findByText('Page is not trashed; trash it first.')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
