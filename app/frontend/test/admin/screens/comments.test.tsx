import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Comments } from '../../../src/admin/screens/Comments';
import { BLOG, meHandler, problem, renderScreen, server, useMockApi, whereIs } from './harness';

const COMMENTS = [
  { id: 'c1', entryId: 'e1', entryTitle: 'First post', status: 'PENDING', body: 'Nice <b>post</b>\nsecond line',
    created: '2026-03-01T09:30:00Z', authorUserName: 'reader1', authorDisplayName: 'Rita Reader', authorIsOwner: false },
  { id: 'c2', entryId: 'e2', entryTitle: 'Other', status: 'PUBLISHED', body: 'Published one',
    created: '2026-03-02T09:30:00Z', authorUserName: 'owner', authorDisplayName: 'The Owner', authorIsOwner: true },
  { id: 'c3', entryId: 'e2', entryTitle: 'Other', status: 'PUBLISHED', body: 'With a reply', reply: 'Thanks!',
    created: '2026-03-03T09:30:00Z', authorUserName: 'reader3', authorDisplayName: 'Third', authorIsOwner: false },
  { id: 'c4', entryId: 'e2', entryTitle: 'Other', status: 'HIDDEN', body: '',
    created: '2026-03-04T09:30:00Z', authorUserName: 'reader4', authorDisplayName: 'Fourth', authorIsOwner: false }
];

const requests: URL[] = [];

function list(items = COMMENTS, hasMore = false) {
  return http.get(`${BLOG}/comments`, ({ request }) => {
    requests.push(new URL(request.url));
    return HttpResponse.json({ items, offset: 0, limit: 30, hasMore });
  });
}

const show = (at = '/weblogs/blog/comments') => renderScreen('/weblogs/:handle/comments', <Comments />, at);
const rowOf = async (name: string) => (await screen.findByText(name)).closest('tr') as HTMLElement;

describe('Comments', () => {
  useMockApi();
  beforeEach(() => { requests.length = 0; server.use(meHandler('POST'), list()); });

  it('opens on the Awaiting approval tab and tells the three tabs apart', async () => {
    show();
    const nav = await screen.findByRole('navigation', { name: 'Comments' });
    expect(requests[0]?.searchParams.get('status')).toBe('PENDING');
    expect(within(nav).getByRole('link', { name: 'Awaiting approval' })).toHaveAttribute('aria-current', 'page');
    expect(within(nav).getByRole('link', { name: 'Published' })).toHaveAttribute('href', '/weblogs/blog/comments?status=PUBLISHED');
    expect(screen.getByText(/Hide sends this reader's next comments back for approval/)).toBeInTheDocument();
  });

  it('shows the Hidden tab without the hide-versus-delete note', async () => {
    const user = userEvent.setup();
    show();
    await user.click(await screen.findByRole('link', { name: 'Hidden' }));
    await waitFor(() => { expect(requests.at(-1)?.searchParams.get('status')).toBe('HIDDEN'); });
    expect(screen.queryByText(/Hide sends this reader's/)).toBeNull();
    expect(await whereIs()).toBe('/weblogs/blog/comments?status=HIDDEN');
  });

  it('renders a row: writer, owner badge, plain-text body, reply, tombstone, entry link and date', async () => {
    show();
    const row = await rowOf('Rita Reader');
    expect(within(row).getByText('reader1')).toHaveClass('data');
    expect(within(row).getByText(/Nice <b>post<\/b>/)).toHaveStyle({ whiteSpace: 'pre-wrap' });
    expect(within(row).getByRole('link', { name: 'First post' })).toHaveAttribute('href', '/weblogs/blog/entries/e1');
    expect(within(row).getByText('2026-03-01 09:30 UTC')).toBeInTheDocument();
    const owner = await rowOf('The Owner');
    expect(within(owner).getByText('Author', { selector: '.status-pill' })).toBeInTheDocument();
    const replied = await rowOf('Third');
    expect(within(replied).getByText('Thanks!')).toBeInTheDocument();
    expect(within(replied).queryByText('Reply', { selector: 'summary' })).toBeNull();
    const tomb = await rowOf('Fourth');
    expect(within(tomb).getByText('Deleted by its author', { selector: 'em' })).toBeInTheDocument();
  });

  it('offers approve only where it can apply, hide only where it can, delete always', async () => {
    show();
    const pending = await rowOf('Rita Reader');
    expect(within(pending.querySelector('td:last-child') as HTMLElement).getAllByRole('button').map((b) => b.textContent)).toEqual(['Approve', 'Hide', 'Delete']);
    expect(within((await rowOf('The Owner')).querySelector('td:last-child') as HTMLElement).getAllByRole('button').map((b) => b.textContent)).toEqual(['Hide', 'Delete']);
    expect(within((await rowOf('Fourth')).querySelector('td:last-child') as HTMLElement).getAllByRole('button').map((b) => b.textContent)).toEqual(['Delete']);
  });

  it('approves and hides one comment', async () => {
    const user = userEvent.setup();
    const bodies: unknown[] = [];
    server.use(http.patch(`${BLOG}/comments/c1`, async ({ request }) => {
      bodies.push(await request.json());
      return HttpResponse.json({ id: 'c1' });
    }));
    show();
    await user.click(within(await rowOf('Rita Reader')).getByRole('button', { name: 'Approve' }));
    expect(await screen.findByText('1 approved.')).toBeInTheDocument();
    await user.click(within(await rowOf('Rita Reader')).getByRole('button', { name: 'Hide' }));
    expect(await screen.findByText('1 hidden.')).toBeInTheDocument();
    expect(bodies).toEqual([{ status: 'PUBLISHED' }, { status: 'HIDDEN' }]);
  });

  it('says why a moderation was refused, in place', async () => {
    const user = userEvent.setup();
    server.use(http.patch(`${BLOG}/comments/c1`, () => problem(409, 'comment.deletedByAuthor', 'Its author deleted it.')));
    show();
    await user.click(within(await rowOf('Rita Reader')).getByRole('button', { name: 'Approve' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Its author deleted it.');
  });

  it('deletes one comment after a confirmation', async () => {
    const user = userEvent.setup();
    let gone = '';
    server.use(http.delete(`${BLOG}/comments/c1`, () => { gone = 'c1'; return new HttpResponse(null, { status: 204 }); }));
    show();
    await user.click(within(await rowOf('Rita Reader')).getByRole('button', { name: 'Delete' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Delete this comment? An author reply to it goes too.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(gone).toBe('');
    await user.click(within(await rowOf('Rita Reader')).getByRole('button', { name: 'Delete' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('1 deleted.')).toBeInTheDocument();
    expect(gone).toBe('c1');
  });

  it('shows a refused delete in place', async () => {
    const user = userEvent.setup();
    server.use(http.delete(`${BLOG}/comments/c1`, () => problem(404, 'comment.notFound', 'Gone already.')));
    show();
    await user.click(within(await rowOf('Rita Reader')).getByRole('button', { name: 'Delete' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Gone already.');
  });

  it('approves the selection per comment and reports the ones that failed', async () => {
    const user = userEvent.setup();
    server.use(http.patch(`${BLOG}/comments/:id`, ({ params }) => params['id'] === 'c3'
      ? problem(409, 'comment.deletedByAuthor', 'Its author deleted it.') : HttpResponse.json({ id: params['id'] })));
    show();
    await user.click(await screen.findByTitle('Select all'));
    expect(document.querySelector('.selection-count')).toHaveTextContent('4 selected');
    await user.click(document.querySelector('.selection-bar button.btn-primary') as HTMLElement);
    expect(await screen.findByText('3 approved.')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('reader3: Its author deleted it.');
  });

  it('hides the selection', async () => {
    const user = userEvent.setup();
    const hidden: string[] = [];
    server.use(http.patch(`${BLOG}/comments/:id`, async ({ params, request }) => {
      hidden.push(`${String(params['id'])}:${JSON.stringify(await request.json())}`);
      return HttpResponse.json({ id: params['id'] });
    }));
    show();
    await user.click(await screen.findByLabelText('reader1'));
    await user.click(screen.getByLabelText('owner'));
    await user.click(document.querySelector('.selection-bar button.btn-secondary') as HTMLElement);
    expect(await screen.findByText('2 hidden.')).toBeInTheDocument();
    expect(hidden.sort()).toEqual(['c1:{"status":"HIDDEN"}', 'c2:{"status":"HIDDEN"}']);
  });

  it('deletes the selection after a confirmation that counts it', async () => {
    const user = userEvent.setup();
    const gone: string[] = [];
    server.use(http.delete(`${BLOG}/comments/:id`, ({ params }) => { gone.push(String(params['id'])); return new HttpResponse(null, { status: 204 }); }));
    show();
    await user.click(await screen.findByLabelText('reader1'));
    await user.click(screen.getByLabelText('reader4'));
    await user.click(document.querySelector('#commentsDeleteSelected') as HTMLElement);
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Delete the selected comments?')).toBeInTheDocument();
    expect(within(dialog).getByText('2 selected')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('2 deleted.')).toBeInTheDocument();
    expect(gone.sort()).toEqual(['c1', 'c4']);
  });

  it('posts a reply on a published comment without one', async () => {
    const user = userEvent.setup();
    let sent: unknown;
    server.use(http.post(`${BLOG}/comments/c2/replies`, async ({ request }) => {
      sent = await request.json();
      return HttpResponse.json({ id: 'r1' }, { status: 201 });
    }));
    show();
    const row = await rowOf('The Owner');
    await user.click(within(row).getByText('Reply', { selector: 'summary' }));
    await user.type(within(row).getByLabelText('Reply'), 'Glad you liked it');
    await user.click(within(row).getByRole('button', { name: 'Post reply' }));
    expect(await screen.findByText('Reply posted.')).toBeInTheDocument();
    expect(sent).toEqual({ body: 'Glad you liked it' });
  });

  it('puts a refused reply on its field and in words', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${BLOG}/comments/c2/replies`, () => problem(400, 'field.tooLong', 'Too long.', [{ field: 'body', message: 'At most 4000 characters.' }])));
    show();
    const row = await rowOf('The Owner');
    await user.click(within(row).getByText('Reply', { selector: 'summary' }));
    await user.type(within(row).getByLabelText('Reply'), 'x');
    await user.click(within(row).getByRole('button', { name: 'Post reply' }));
    expect(await within(row).findByRole('alert')).toHaveTextContent('Too long.');
    expect(within(row).getByLabelText('Reply')).toHaveAttribute('aria-invalid', 'true');
    expect(within(row).getByLabelText('Reply')).toHaveClass('is-invalid');
  });

  it('filters to one entry and pages with Newer and Older', async () => {
    const user = userEvent.setup();
    server.use(list(COMMENTS, true));
    show('/weblogs/blog/comments?status=PUBLISHED&entry=e2');
    await screen.findByText('The Owner');
    expect(requests[0]?.searchParams.get('entryId')).toBe('e2');
    await user.click((await screen.findAllByRole('link', { name: /Older/ }))[0] as HTMLElement);
    expect(await whereIs()).toBe('/weblogs/blog/comments?status=PUBLISHED&entry=e2&offset=30');
  });

  it('shows the per-tab empty state', async () => {
    server.use(list([]));
    const { unmount } = show();
    expect(await screen.findByText('No comments here.')).toBeInTheDocument();
    expect(screen.getByText('New comments wait here until you approve them.')).toBeInTheDocument();
    unmount();
    show('/weblogs/blog/comments?status=HIDDEN');
    expect(await screen.findByText('Comments you hide appear here. Readers do not see them.')).toBeInTheDocument();
  });

  it('shows a failed list in place', async () => {
    server.use(http.get(`${BLOG}/comments`, () => problem(403, 'auth.forbidden', 'No.')));
    show();
    expect(await screen.findByRole('alert')).toHaveTextContent('No.');
  });
});
