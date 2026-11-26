import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Trash } from '../../../src/admin/screens/Trash';
import { BLOG, meHandler, problem, renderScreen, server, useMockApi } from './harness';

const TRASHED = [
  { id: 't1', title: 'Old news', category: 'News', updateTime: '2026-03-01T09:30:00Z' },
  { id: 't2', title: "Tom & Jerry's", category: 'Fun', updateTime: '2026-03-02T09:30:00Z' }
];

function list(items = TRASHED) {
  return http.get(`${BLOG}/entries`, ({ request }) => {
    expect(new URL(request.url).searchParams.get('status')).toBe('TRASHED');
    return HttpResponse.json({ items, offset: 0, limit: 200, hasMore: false });
  });
}

const show = () => renderScreen('/weblogs/:handle/trash', <Trash />, '/weblogs/blog/trash');

describe('Trash', () => {
  useMockApi();
  beforeEach(() => { server.use(meHandler('POST'), list()); });

  it('lists the trashed entries with their category', async () => {
    show();
    const row = (await screen.findByText('Old news')).closest('tr') as HTMLElement;
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Trash');
    expect(within(row).getByText('News')).toBeInTheDocument();
    expect(within(row).getByText('2026-03-01 09:30 UTC')).toBeInTheDocument();
    expect(screen.getByText("Tom & Jerry's")).toBeInTheDocument();
    expect(document.querySelector('#trash-list-marker')).not.toBeNull();
  });

  it('pages through a trash longer than one page', async () => {
    server.use(http.get(`${BLOG}/entries`, ({ request }) => {
      const offset = Number(new URL(request.url).searchParams.get('offset'));
      return offset === 0
        ? HttpResponse.json({ items: [TRASHED[0]], offset: 0, limit: 1, hasMore: true })
        : HttpResponse.json({ items: [TRASHED[1]], offset: 1, limit: 1, hasMore: false });
    }));
    show();
    expect(await screen.findByText('Old news')).toBeInTheDocument();
    expect(await screen.findByText("Tom & Jerry's")).toBeInTheDocument();
  });

  it('says so, with no action, when the trash is empty', async () => {
    server.use(list([]));
    show();
    expect(await screen.findByText('Nothing in the Trash.')).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
    expect(document.querySelector('#trash-list-marker')).not.toBeNull();
  });

  it('restores an entry', async () => {
    const user = userEvent.setup();
    let restored = '';
    server.use(http.post(`${BLOG}/entries/t1/restore`, () => { restored = 't1'; return HttpResponse.json({ id: 't1' }); }));
    show();
    const row = (await screen.findByText('Old news')).closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Restore' }));
    expect(await screen.findByText('Entry "Old news" restored')).toBeInTheDocument();
    expect(restored).toBe('t1');
  });

  it('shows a refused restore in place', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${BLOG}/entries/t1/restore`, () => problem(409, 'entry.notInTrash', 'Not in the trash.')));
    show();
    const row = (await screen.findByText('Old news')).closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Restore' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Not in the trash.');
  });

  it('deletes forever only after a confirmation', async () => {
    const user = userEvent.setup();
    let gone = '';
    server.use(http.post(`${BLOG}/entries/t2/delete-forever`, () => { gone = 't2'; return new HttpResponse(null, { status: 204 }); }));
    show();
    const row = (await screen.findByText("Tom & Jerry's")).closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Delete forever' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Delete this entry forever? This cannot be undone.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(gone).toBe('');
    await user.click(within(row).getByRole('button', { name: 'Delete forever' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete forever' }));
    expect(await screen.findByText('Entry "Tom & Jerry\'s" permanently deleted')).toBeInTheDocument();
    expect(gone).toBe('t2');
  });

  it('shows a refused delete-forever in place', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${BLOG}/entries/t2/delete-forever`, () => problem(403, 'entry.needsPublishRights', 'Publishers only.')));
    show();
    const row = (await screen.findByText("Tom & Jerry's")).closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Delete forever' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete forever' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Publishers only.');
  });

  it('empties the trash entry by entry after a confirmation that counts them, reporting failures', async () => {
    const user = userEvent.setup();
    const gone: string[] = [];
    server.use(http.post(`${BLOG}/entries/:id/delete-forever`, ({ params }) => {
      if (params['id'] === 't2') {
        return problem(409, 'entry.deleteForeverNeedsTrash', 'Already gone.');
      }
      gone.push(String(params['id']));
      return new HttpResponse(null, { status: 204 });
    }));
    show();
    await user.click(await screen.findByRole('button', { name: 'Empty trash' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Permanently delete 2 entries? This cannot be undone.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Empty trash' }));
    expect(await screen.findByText('Entries permanently deleted: 1.')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent("Tom & Jerry's: Already gone.");
    expect(gone).toEqual(['t1']);
  });

  it('shows the failed list in place', async () => {
    server.use(http.get(`${BLOG}/entries`, () => problem(403, 'auth.forbidden', 'No.')));
    show();
    expect(await screen.findByRole('alert')).toHaveTextContent('No.');
  });
});
