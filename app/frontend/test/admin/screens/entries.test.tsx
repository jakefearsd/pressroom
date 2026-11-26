import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Entries } from '../../../src/admin/screens/Entries';
import { BLOG, meHandler, metaHandler, problem, renderScreen, server, useMockApi, whereIs } from './harness';

const ITEMS = [
  { id: 'e1', anchor: 'first-post', title: 'First post', status: 'PUBLISHED', category: 'News', tags: ['a'],
    pubTime: '2026-03-01T09:30:00Z', updateTime: '2026-03-02T10:00:00Z', permalink: 'https://x.test/blog/entry/first-post', pinned: true },
  { id: 'e2', anchor: 'second-post', title: "Maiia's trip", status: 'DRAFT', category: 'Travel', tags: [],
    updateTime: '2026-03-03T10:00:00Z' },
  { id: 'e3', anchor: 'later', title: 'Later', status: 'SCHEDULED', category: 'News', tags: ['x', 'y'],
    pubTime: '2026-12-01T09:00:00Z', updateTime: '2026-03-04T10:00:00Z' }
];

const requests: URL[] = [];

function listHandler(items: object[] = ITEMS, hasMore = false) {
  return http.get(`${BLOG}/entries`, ({ request }) => {
    requests.push(new URL(request.url));
    return HttpResponse.json({ items, offset: 0, limit: 30, hasMore });
  });
}

function categories() {
  return http.get(`${BLOG}/categories`, () => HttpResponse.json([
    { id: 'c1', name: 'News', entryCount: 2 }, { id: 'c2', name: 'Travel', entryCount: 1 }
  ]));
}

function show(at = '/weblogs/blog/entries', boot?: Parameters<typeof renderScreen>[3]) {
  return renderScreen('/weblogs/:handle/entries', <Entries />, at, boot);
}

describe('Entries', () => {
  useMockApi();
  beforeEach(() => {
    requests.length = 0;
    server.use(meHandler('POST'), metaHandler(), categories(), listHandler());
  });

  it('lists the entries as the JSP table did: pill, category, editor link, view link and pinned marker', async () => {
    show();
    const row = (await screen.findByRole('link', { name: 'First post' })).closest('tr') as HTMLElement;
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Weblog entries');
    expect(screen.getByRole('link', { name: 'First post' })).toHaveAttribute('href', '/weblogs/blog/entries/e1');
    expect(within(row).getByText('Published', { selector: '.status-pill' })).toBeInTheDocument();
    expect(within(row).getByText('News')).toBeInTheDocument();
    expect(within(row).getByText('first-post')).toHaveClass('data');
    expect(within(row).getByRole('link', { name: 'View' })).toHaveAttribute('href', 'https://x.test/blog/entry/first-post');
    expect(within(row).getByText('Pinned')).toHaveClass('entry-pinned');
    expect(within(row).getByText('2026-03-01 09:30 UTC', { selector: 'time' })).toBeInTheDocument();
    const draft = screen.getByRole('link', { name: "Maiia's trip" }).closest('tr') as HTMLElement;
    expect(within(draft).queryByRole('link', { name: 'View' })).toBeNull();
    const scheduled = screen.getByRole('link', { name: 'Later' }).closest('tr') as HTMLElement;
    expect(within(scheduled).getByText('Scheduled', { selector: '.status-pill' })).toHaveTextContent('2026-12-01 09:00');
    expect(requests[0]?.searchParams.get('status')).toBeNull();
    expect(requests[0]?.searchParams.get('limit')).toBe('30');
  });

  it('status chips swap only the status, keep the rest of the filter and mark the active one', async () => {
    const user = userEvent.setup();
    show('/weblogs/blog/entries?text=beach');
    const nav = await screen.findByRole('navigation', { name: 'Status' });
    expect(within(nav).getByRole('link', { name: 'All entries' })).toHaveAttribute('aria-current', 'page');
    await user.click(within(nav).getByRole('link', { name: 'Drafts' }));
    await waitFor(() => { expect(requests.at(-1)?.searchParams.get('status')).toBe('DRAFT'); });
    expect(requests.at(-1)?.searchParams.get('text')).toBe('beach');
    expect(await whereIs()).toBe('/weblogs/blog/entries?text=beach&status=DRAFT');
    expect(within(screen.getByRole('navigation', { name: 'Status' })).getByRole('link', { name: 'Drafts' }))
      .toHaveAttribute('aria-current', 'page');
  });

  it('filters by category, tags, text and dates', async () => {
    const user = userEvent.setup();
    show();
    await screen.findByRole('link', { name: 'First post' });
    await user.selectOptions(await screen.findByLabelText('Category', { selector: 'select' }), 'Travel');
    await user.type(screen.getByLabelText('Tags'), 'a, b');
    await user.type(screen.getByLabelText('Search text'), 'beach');
    await user.type(screen.getByLabelText('Start date'), '2026-01-02');
    await user.type(screen.getByLabelText('End date'), '2026-02-03');
    await user.click(screen.getByRole('button', { name: 'Filter' }));
    await waitFor(() => { expect(requests.at(-1)?.searchParams.get('category')).toBe('Travel'); });
    const last = requests.at(-1) as URL;
    expect(last.searchParams.getAll('tags')).toEqual(['a', 'b']);
    expect(last.searchParams.get('text')).toBe('beach');
    expect(last.searchParams.get('startDate')).toBe('2026-01-02');
    expect(last.searchParams.get('endDate')).toBe('2026-02-03');
    expect(screen.getByLabelText('Tags')).toHaveValue('a, b');
  });

  it('sorts by update time by default and by publish time on request, keeping the filter', async () => {
    const user = userEvent.setup();
    show('/weblogs/blog/entries?text=x');
    const select = await screen.findByLabelText('Sort by');
    expect(select).toHaveValue('UPDATE_TIME');
    expect(requests[0]?.searchParams.get('sortBy')).toBe('UPDATE_TIME');
    await user.selectOptions(select, 'PUBLICATION_TIME');
    await waitFor(() => { expect(requests.at(-1)?.searchParams.get('sortBy')).toBe('PUBLICATION_TIME'); });
    expect(requests.at(-1)?.searchParams.get('text')).toBe('x');
    expect(await whereIs()).toBe('/weblogs/blog/entries?text=x&sortBy=PUBLICATION_TIME');
    await user.click(within(screen.getByRole('navigation', { name: 'Status' })).getByRole('link', { name: 'Drafts' }));
    await waitFor(() => { expect(requests.at(-1)?.searchParams.get('status')).toBe('DRAFT'); });
    expect(requests.at(-1)?.searchParams.get('sortBy')).toBe('PUBLICATION_TIME');
  });

  it('has no views column without analytics', async () => {
    show();
    await screen.findByRole('link', { name: 'First post' });
    expect(screen.queryByText('Views (30d)')).toBeNull();
    expect(requests[0]?.searchParams.getAll('fields')).not.toContain('views30d');
  });

  it('shows the 30-day views, a dash where they are not known, when analytics is on', async () => {
    server.use(metaHandler(true), listHandler([
      { ...ITEMS[0], views30d: 1234 }, { ...ITEMS[1], views30d: 0 }, ITEMS[2] as object
    ]));
    show();
    expect(await screen.findByText('Views (30d)')).toHaveClass('n');
    const cells = (title: string) => (screen.getByRole('link', { name: title }).closest('tr') as HTMLElement).querySelector('td.n');
    await waitFor(() => { expect(cells('First post')).toHaveTextContent('1,234'); });
    expect(cells("Maiia's trip")).toHaveTextContent('0');
    expect(cells('Later')).toHaveTextContent('—');
    expect(requests.at(-1)?.searchParams.getAll('fields')).toContain('views30d');
  });

  it('pages with Newer and Older links that carry the filter', async () => {
    const user = userEvent.setup();
    server.use(listHandler(ITEMS, true));
    show('/weblogs/blog/entries?text=x');
    await user.click((await screen.findAllByRole('link', { name: /Older/ }))[0] as HTMLElement);
    await waitFor(() => { expect(requests.at(-1)?.searchParams.get('offset')).toBe('30'); });
    expect(await whereIs()).toBe('/weblogs/blog/entries?text=x&offset=30');
    await user.click((await screen.findAllByRole('link', { name: /Newer/ }))[0] as HTMLElement);
    expect(await whereIs()).toBe('/weblogs/blog/entries?text=x');
  });

  it('shows the first-entry invitation for an empty weblog and a clear-filters one when a filter matched nothing', async () => {
    server.use(listHandler([]));
    const { unmount } = show();
    expect(await screen.findByText('No entries yet.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Write an entry' })).toBeInTheDocument();
    expect(document.querySelector('#entries-list-marker')).not.toBeNull();
    expect(document.querySelector('table')).toBeNull();
    unmount();
    show('/weblogs/blog/entries?text=zzz');
    expect(await screen.findByText('Nothing matched.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Clear filters' })).toHaveAttribute('href', '/weblogs/blog/entries');
  });

  it('duplicates an entry and goes to the copy in the editor', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${BLOG}/entries/e1/duplicate`, () => HttpResponse.json({ id: 'copy', title: 'Copy of First post' }, { status: 201 })));
    show();
    await user.click(await screen.findByRole('button', { name: 'Duplicate: First post' }));
    await waitFor(() => { expect(screen.getByText('the editor')).toBeInTheDocument(); });
    expect(await whereIs()).toBe('/weblogs/blog/entries/copy');
  });

  it('says why a duplicate was refused, in place', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${BLOG}/entries/e1/duplicate`, () => problem(409, 'entry.trashed', 'The entry is in the trash.')));
    show();
    await user.click(await screen.findByRole('button', { name: 'Duplicate: First post' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('The entry is in the trash.');
    expect(screen.getByRole('link', { name: 'First post' })).toBeInTheDocument();
  });

  it('moves one entry to the trash after a confirmation naming it', async () => {
    const user = userEvent.setup();
    let trashed = '';
    server.use(http.delete(`${BLOG}/entries/e2`, () => { trashed = 'e2'; return new HttpResponse(null, { status: 204 }); }));
    show();
    await user.click(await screen.findByRole('button', { name: "Delete: Maiia's trip" }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Move to the Trash?')).toBeInTheDocument();
    expect(within(dialog).getByText("Maiia's trip")).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(trashed).toBe('');
    await user.click(screen.getByRole('button', { name: "Delete: Maiia's trip" }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Move to Trash' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Entry "Maiia\'s trip" moved to Trash.');
    expect(trashed).toBe('e2');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('shows a refused trash in place', async () => {
    const user = userEvent.setup();
    server.use(http.delete(`${BLOG}/entries/e2`, () => problem(403, 'entry.notChangeable', 'Not yours to change.')));
    show();
    await user.click(await screen.findByRole('button', { name: "Delete: Maiia's trip" }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Move to Trash' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Not yours to change.');
  });

  it('hides the bar until something is selected, and select-all selects every row', async () => {
    const user = userEvent.setup();
    show();
    await screen.findByRole('link', { name: 'First post' });
    expect(document.querySelector('.selection-bar')).toHaveAttribute('hidden');
    await user.click(screen.getByTitle('Select all'));
    expect(document.querySelector('.selection-count')).toHaveTextContent('3 selected');
    await user.click(screen.getByLabelText('Later'));
    expect(screen.getByTitle('Select all')).not.toBeChecked();
    expect(document.querySelector('.selection-count')).toHaveTextContent('2 selected');
  });

  it('bulk-publishes the selection one entry at a time and reports the count', async () => {
    const user = userEvent.setup();
    const patched: string[] = [];
    server.use(http.patch(`${BLOG}/entries/:id`, async ({ params, request }) => {
      patched.push(`${String(params['id'])}:${JSON.stringify(await request.json())}`);
      return HttpResponse.json({ id: params['id'] });
    }));
    show();
    await user.click(await screen.findByLabelText("Maiia's trip"));
    await user.click(screen.getByLabelText('Later'));
    await user.click(screen.getByRole('button', { name: 'Publish selected' }));
    expect(await screen.findByText('Entries published: 2.')).toBeInTheDocument();
    expect(patched.sort()).toEqual(['e2:{"status":"PUBLISHED"}', 'e3:{"status":"PUBLISHED"}']);
  });

  it('reports each entry a bulk action could not change, by title and reason', async () => {
    const user = userEvent.setup();
    server.use(http.patch(`${BLOG}/entries/:id`, ({ params }) => params['id'] === 'e3'
      ? problem(404, 'entry.notFound', 'No such entry.') : HttpResponse.json({ id: params['id'] })));
    show();
    await user.click(await screen.findByLabelText("Maiia's trip"));
    await user.click(screen.getByLabelText('Later'));
    await user.click(screen.getByRole('button', { name: 'Publish selected' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('1 of the selected entries could not be changed.');
    expect(alert).toHaveTextContent('Later: No such entry.');
    expect(screen.getByText('Entries published: 1.')).toBeInTheDocument();
  });

  it('shows progress while a bulk action runs', async () => {
    const user = userEvent.setup();
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    server.use(http.patch(`${BLOG}/entries/:id`, async ({ params }) => { await gate; return HttpResponse.json({ id: params['id'] }); }));
    show();
    await user.click(await screen.findByLabelText('Later'));
    await user.click(screen.getByRole('button', { name: 'Publish selected' }));
    expect(await screen.findByText('Working: 0 of 1 done')).toBeInTheDocument();
    release();
    expect(await screen.findByText('Entries published: 1.')).toBeInTheDocument();
  });

  it('tags the selection, adding to the tags each entry already has', async () => {
    const user = userEvent.setup();
    const bodies: Record<string, unknown> = {};
    server.use(http.patch(`${BLOG}/entries/:id`, async ({ params, request }) => {
      bodies[String(params['id'])] = await request.json();
      return HttpResponse.json({ id: params['id'] });
    }));
    show();
    await user.click(await screen.findByLabelText('Later'));
    await user.click(screen.getByLabelText('First post'));
    await user.click(screen.getByRole('button', { name: 'Add tag' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Type a tag before adding one.');
    await user.type(screen.getByPlaceholderText('tag to add'), ' x ');
    await user.click(screen.getByRole('button', { name: 'Add tag' }));
    expect(await screen.findByText('Entries tagged: 2.')).toBeInTheDocument();
    expect(bodies).toEqual({ e1: { tags: ['a', 'x'] }, e3: { tags: ['x', 'y'] } });
  });

  it('moves the selection to the trash after a confirmation that counts it', async () => {
    const user = userEvent.setup();
    const deleted: string[] = [];
    server.use(http.delete(`${BLOG}/entries/:id`, ({ params }) => { deleted.push(String(params['id'])); return new HttpResponse(null, { status: 204 }); }));
    show();
    await user.click(await screen.findByTitle('Select all'));
    await user.click(screen.getByRole('button', { name: 'Delete selected' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Move the selected entries to the Trash?')).toBeInTheDocument();
    expect(within(dialog).getByText('3 selected')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Move to Trash' }));
    expect(await screen.findByText(/Entries moved to the Trash: 3\./)).toBeInTheDocument();
    expect(deleted.sort()).toEqual(['e1', 'e2', 'e3']);
  });

  describe('for a draft-only author', () => {
    it('offers submit for review instead of publish and no duplicate', async () => {
      const user = userEvent.setup();
      server.use(meHandler('EDIT_DRAFT'));
      show();
      await screen.findByRole('link', { name: 'First post' });
      await user.click(screen.getByLabelText('Later'));
      await waitFor(() => { expect(screen.getByRole('button', { name: 'Submit selected for review' })).toBeInTheDocument(); });
      expect(screen.queryByRole('button', { name: 'Publish selected' })).toBeNull();
      expect(screen.queryByRole('button', { name: /^Duplicate/ })).toBeNull();
      server.use(http.patch(`${BLOG}/entries/e3`, () => HttpResponse.json({ id: 'e3', status: 'PENDING' })));
      await user.click(screen.getByRole('button', { name: 'Submit selected for review' }));
      expect(await screen.findByText('Entries submitted for review: 1.')).toBeInTheDocument();
    });
  });

  it('shows a failed list in place', async () => {
    server.use(http.get(`${BLOG}/entries`, () => problem(403, 'auth.forbidden', 'Boom.')));
    show();
    expect(await screen.findByRole('alert')).toHaveTextContent('Boom.');
  });
});
