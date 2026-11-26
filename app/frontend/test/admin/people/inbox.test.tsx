import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
import { API, open, problem, recordCalls, resetWorld, server, world, writes } from './harness';

interface Sub { id: string; name?: string; email?: string; subject?: string; message?: string; pageSlug?: string; entryAnchor?: string; created?: string }
let all: Sub[];

const make = (n: number): Sub[] => Array.from({ length: n }, (_v, i) => ({
  id: `s${String(i)}`, name: `Sender ${String(i)}`, email: `s${String(i)}@example.com`, subject: `Subject ${String(i)}`,
  message: `Hello ${String(i)}`, created: '2026-03-01T12:30:00Z'
}));

beforeAll(() => { recordCalls(); server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => {
  resetWorld();
  all = make(3);
  server.use(http.get(`${API}/weblogs/alpha/submissions`, ({ request }) => {
    const q = new URL(request.url).searchParams;
    const offset = Number(q.get('offset'));
    const limit = Number(q.get('limit'));
    return HttpResponse.json({ items: all.slice(offset, offset + limit), offset, limit, hasMore: offset + limit < all.length, total: all.length });
  }));
});

describe('Inbox screen', () => {
  it('lists inquiries with sender, mailto link, local time and where the form was', async () => {
    all = [
      { ...make(1)[0] as Sub, entryAnchor: 'my-entry' },
      { ...make(2)[1] as Sub, pageSlug: 'contact' },
      { id: 'x', subject: 'Bare', message: 'm' }
    ];
    open('/weblogs/alpha/inbox');
    expect(await screen.findByRole('heading', { level: 1, name: 'Contact inquiries' })).toBeInTheDocument();
    expect(await screen.findByText('Sender 0')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 's0@example.com' })).toHaveAttribute('href', 'mailto:s0@example.com');
    expect(screen.getAllByText('2026-03-01 13:30')).toHaveLength(2);
    expect(screen.getByText('my-entry')).toBeInTheDocument();
    expect(screen.getByText('/contact')).toBeInTheDocument();
    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('prints a stranger\'s markup as text and folds a long message into a details element', async () => {
    const long = `<img src=x onerror=alert(1)> ${'word '.repeat(60)}`;
    all = [{ id: 'a', name: '<b>Eve</b>', subject: 'Hi', message: long }];
    open('/weblogs/alpha/inbox');
    expect(await screen.findByText('<b>Eve</b>')).toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
    expect(screen.getByText('Show full message')).toBeInTheDocument();
    expect(screen.getByText(long.trim(), { selector: 'details p' })).toBeInTheDocument();
  });

  it('pages 30 at a time with Newer and Older links', async () => {
    all = make(70);
    const first = open('/weblogs/alpha/inbox');
    await screen.findByText('Sender 0');
    expect(screen.queryByText('Newer')).toBeNull();
    expect(screen.getByRole('link', { name: /Older/ })).toHaveAttribute('href', expect.stringContaining('/weblogs/alpha/inbox?page=1'));
    first.unmount();
    open('/weblogs/alpha/inbox?page=2');
    expect(await screen.findByText('Sender 60')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Older/ })).toBeNull();
    expect(screen.getByRole('link', { name: /Newer/ })).toHaveAttribute('href', expect.stringContaining('?page=1'));
  });

  it('shows the empty state', async () => {
    all = [];
    open('/weblogs/alpha/inbox');
    expect(await screen.findByText('No inquiries yet.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('shows the selection bar only with a selection, and select-all follows the rows', async () => {
    const user = userEvent.setup();
    open('/weblogs/alpha/inbox');
    await screen.findByText('Sender 0');
    const bar = document.querySelector('.selection-bar') as HTMLElement;
    expect(bar).toHaveAttribute('hidden');
    await user.click(screen.getByRole('checkbox', { name: 'Subject 1' }));
    expect(bar).not.toHaveAttribute('hidden');
    expect(within(bar).getByText('1 selected')).toBeInTheDocument();
    const all3 = screen.getByRole('checkbox', { name: 'Select all' });
    expect(all3).not.toBeChecked();
    await user.click(all3);
    expect(within(bar).getByText('3 selected')).toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: 'Subject 1' }));
    expect(all3).not.toBeChecked();
    await user.click(all3);
    await user.click(all3);
    expect(bar).toHaveAttribute('hidden');
  });

  it('confirms with the count, deletes four at a time with progress, and reports the total', async () => {
    const user = userEvent.setup();
    all = make(10);
    let inFlight = 0;
    let peak = 0;
    server.use(http.delete(`${API}/weblogs/alpha/submissions/:id`, async ({ params }) => {
      inFlight++;
      peak = Math.max(peak, inFlight);
      await delay(20);
      inFlight--;
      all = all.filter((s) => s.id !== params['id']);
      return new HttpResponse(null, { status: 204 });
    }));
    open('/weblogs/alpha/inbox');
    await screen.findByText('Sender 0');
    await user.click(screen.getByRole('checkbox', { name: 'Select all' }));
    await user.click(screen.getByRole('button', { name: 'Delete selected' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Delete the selected inquiries?')).toBeInTheDocument();
    expect(within(dialog).getByText('They are deleted permanently. Inquiries do not go to the Trash.')).toBeInTheDocument();
    expect(within(dialog).getByText('10 selected')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(writes()).toEqual([]);
    await user.click(screen.getByRole('button', { name: 'Delete selected' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText(/Deleting: \d+ of 10 done\./)).toBeInTheDocument();
    expect(await screen.findByText('Inquiries deleted: 10.')).toBeInTheDocument();
    expect(peak).toBe(4);
    expect(writes()).toHaveLength(10);
    expect(writes().every((c) => c.method === 'DELETE' && c.csrf === 'tok')).toBe(true);
    expect(await screen.findByText('No inquiries yet.')).toBeInTheDocument();
  });

  it('reports the inquiries it could not delete, by subject, and keeps going', async () => {
    const user = userEvent.setup();
    server.use(http.delete(`${API}/weblogs/alpha/submissions/:id`, ({ params }) => {
      if (params['id'] === 's1') {
        return problem(404, 'submission.notFound', 'No such message.');
      }
      all = all.filter((s) => s.id !== params['id']);
      return new HttpResponse(null, { status: 204 });
    }));
    open('/weblogs/alpha/inbox');
    await screen.findByText('Sender 0');
    await user.click(screen.getByRole('checkbox', { name: 'Select all' }));
    await user.click(screen.getByRole('button', { name: 'Delete selected' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('Subject 1: No such message.')).toBeInTheDocument();
    expect(screen.getByText('Inquiries deleted: 2.')).toBeInTheDocument();
    await waitFor(() => { expect(writes()).toHaveLength(3); });
  });

  it('shows a failed list in place', async () => {
    server.use(http.get(`${API}/weblogs/alpha/submissions`, () => problem(409, 'server.error', 'Something broke.')));
    open('/weblogs/alpha/inbox');
    expect(await screen.findByText('Something broke.')).toBeInTheDocument();
  }, 10000);

  it('is open to an author, closed to a limited member', async () => {
    world.permission = 'POST';
    const first = open('/weblogs/alpha/inbox');
    expect(await screen.findByText('Sender 0')).toBeInTheDocument();
    first.unmount();
    world.permission = 'EDIT_DRAFT';
    open('/weblogs/alpha/inbox');
    expect(await screen.findByText('That page does not exist.')).toBeInTheDocument();
  });
});
