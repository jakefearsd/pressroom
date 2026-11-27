import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useQuery } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { useMyWeblogs } from '../../../src/admin/api/queries';
import { CommentQueue } from '../../../src/admin/screens/CommentQueue';
import { API, problem, renderScreen, server, useMockApi, whereIs } from './harness';

const ITEMS = [
  { id: 'c1', entryId: 'e1', entryTitle: 'Alpha post', status: 'PENDING', body: 'Nice <b>post</b>',
    created: '2026-03-02T09:30:00Z', authorUserName: 'reader1', authorDisplayName: 'Rita Reader', authorIsOwner: false,
    weblog: { handle: 'alpha', name: 'Alpha Blog' }, entryPermalink: 'https://a.example/entry/a' },
  { id: 'c2', entryId: 'e2', entryTitle: 'Beta post', status: 'PENDING', body: 'Second',
    created: '2026-03-01T09:30:00Z', authorUserName: 'reader2', authorDisplayName: 'Sam Reader', authorIsOwner: false,
    weblog: { handle: 'beta', name: 'Beta Blog' }, entryPermalink: 'https://b.example/entry/b' }
];

const requests: URL[] = [];

function queue(items = ITEMS, hasMore = false) {
  return http.get(`${API}/me/comments`, ({ request }) => {
    requests.push(new URL(request.url));
    return HttpResponse.json({ items, offset: 0, limit: 30, hasMore, total: items.length });
  });
}

const show = (at = '/comments') => renderScreen('/comments', <CommentQueue />, at);
const rowOf = async (name: string) => (await screen.findByText(name)).closest('tr') as HTMLElement;

describe('CommentQueue', () => {
  useMockApi();
  beforeEach(() => { requests.length = 0; server.use(queue()); });

  it('lists comments of two blogs together, each row naming its blog, inside its marker', async () => {
    show();
    const first = await rowOf('Rita Reader');
    expect(within(first).getByRole('link', { name: 'Alpha Blog' })).toHaveAttribute('href', '/weblogs/alpha/comments?status=PENDING');
    expect(within(first).getByRole('link', { name: 'Alpha post' })).toHaveAttribute('href', '/weblogs/alpha/entries/e1');
    expect(within(await rowOf('Sam Reader')).getByText('Beta Blog')).toBeInTheDocument();
    expect(document.querySelectorAll('#comment-queue-marker table')).toHaveLength(1);
    expect(requests[0]?.searchParams.get('status')).toBe('PENDING');
  });

  it('emits its marker and an empty state when nothing waits', async () => {
    server.use(queue([]));
    show();
    await screen.findByText('Nothing is waiting for approval on any of your blogs.');
    expect(document.querySelectorAll('#comment-queue-marker')).toHaveLength(1);
  });

  it('approves on the row\'s own blog, then refreshes the queue, that blog\'s list and the menu counts', async () => {
    const user = userEvent.setup();
    let reads = 0;
    let patched = '';
    let blogListReads = 0;
    function MenuCounts() {
      useMyWeblogs();
      // Stands in for the approved comment's own blog's list, which must go stale.
      useQuery({ queryKey: ['comments', 'beta', { status: 'PENDING' }], queryFn: () => { blogListReads += 1; return 0; } });
      return null;
    }
    server.use(
      http.get(`${API}/me/weblogs`, () => { reads += 1; return HttpResponse.json({ weblogs: [] }); }),
      http.patch(`${API}/weblogs/beta/comments/c2`, async ({ request }) => {
        patched = JSON.stringify(await request.json());
        return HttpResponse.json({ id: 'c2' });
      })
    );
    renderScreen('/comments', <><CommentQueue /><MenuCounts /></>, '/comments');
    await user.click(within(await rowOf('Sam Reader')).getByRole('button', { name: 'Approve' }));
    await screen.findByText('1 approved.');
    expect(patched).toBe('{"status":"PUBLISHED"}');
    await waitFor(() => { expect(reads).toBe(2); });
    await waitFor(() => { expect(requests.length).toBe(2); });
    await waitFor(() => { expect(blogListReads).toBe(2); });
  });

  it('offers Newer when an approval empties the last page, rather than saying nothing waits anywhere', async () => {
    server.use(queue([]));
    show('/comments?offset=30');
    await screen.findByText('Nothing is waiting for approval on any of your blogs.');
    expect(screen.getByRole('link', { name: /Newer/ })).toHaveAttribute('href', '/comments');
  });

  it('hides a comment on its own blog', async () => {
    const user = userEvent.setup();
    let patched = '';
    server.use(http.patch(`${API}/weblogs/alpha/comments/c1`, async ({ request }) => {
      patched = JSON.stringify(await request.json());
      return HttpResponse.json({ id: 'c1' });
    }));
    show();
    await user.click(within(await rowOf('Rita Reader')).getByRole('button', { name: 'Hide' }));
    await screen.findByText('1 hidden.');
    expect(patched).toBe('{"status":"HIDDEN"}');
  });

  it('deletes after a confirmation, and a cancelled one deletes nothing', async () => {
    const user = userEvent.setup();
    let gone = '';
    server.use(http.delete(`${API}/weblogs/alpha/comments/c1`, () => { gone = 'c1'; return new HttpResponse(null, { status: 204 }); }));
    show();
    await user.click(within(await rowOf('Rita Reader')).getByRole('button', { name: 'Delete' }));
    await user.click(await screen.findByRole('button', { name: 'Cancel' }));
    expect(gone).toBe('');
    await user.click(within(await rowOf('Rita Reader')).getByRole('button', { name: 'Delete' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }));
    await screen.findByText('1 deleted.');
    expect(gone).toBe('c1');
  });

  it('says why a moderation was refused, in place', async () => {
    const user = userEvent.setup();
    server.use(http.patch(`${API}/weblogs/alpha/comments/c1`, () => problem(409, 'comment.deletedByAuthor', 'Its author deleted it.')));
    show();
    await user.click(within(await rowOf('Rita Reader')).getByRole('button', { name: 'Approve' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Its author deleted it.');
  });

  it('pages: an offset in the address is asked for, and Older leads to the next page', async () => {
    server.use(queue(ITEMS, true));
    show('/comments?offset=30');
    await screen.findByText('Rita Reader');
    expect(requests[0]?.searchParams.get('offset')).toBe('30');
    expect(screen.getAllByRole('link', { name: /Next|Older/ })[0]).toHaveAttribute('href', '/comments?offset=60');
    expect(await whereIs()).toBe('/comments?offset=30');
  });
});
