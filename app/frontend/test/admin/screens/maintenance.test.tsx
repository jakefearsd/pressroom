import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { siteAdminRoutes } from '../../../src/admin/routes/siteRoutes';
import { adminBoot, editorBoot, renderRoutes } from '../support';

const API = 'http://pressroom.test/pressroom/api/v1';
const weblogs = [
  { id: 'w1', handle: 'alpha', name: 'Alpha <Blog>' },
  { id: 'w2', handle: 'beta', name: 'Beta' }
];
const report = (over: object = {}) => ({ at: '2026-11-25T10:00:00Z', dryRun: false, byCount: 1, byAge: 1, byCeiling: 0,
  ceilingUnmeetable: false, weblogsFailed: 0, ceilingFailed: false, total: 2, incomplete: false, ...over });
let stats: object;
let discovery: object;
let analytics: object;
let searchEnabled = true;
let posts: { url: string; body: unknown; csrf: string | null }[];

const server = setupServer(
  http.get(`${API}/weblogs`, () => HttpResponse.json({ items: weblogs, offset: 0, limit: 200, hasMore: false })),
  http.get(`${API}/admin/revisions`, () => HttpResponse.json(stats)),
  http.get(`${API}/admin/discovery`, () => HttpResponse.json(discovery)),
  http.get(`${API}/admin/analytics`, () => HttpResponse.json(analytics)),
  http.get(`${API}/meta`, () => HttpResponse.json({
    version: '1', features: { customThemes: false, groupBlogging: false, userWeblogCreation: false, uploads: true,
      readerSignIn: false, analytics: true, searchEnabled, mailConfigured: true },
    limits: { maxBodyBytes: 1, maxListLimit: 200, entryTextMaxChars: 1 }, trashRetentionDays: 30 }))
);
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => {
  posts = [];
  searchEnabled = true;
  stats = { entryRevisions: 120, pageRevisions: 8, kept: 3, approximateBytes: 2_500_000, oldest: '2026-01-02T03:04:00Z',
    largest: [{ kind: 'ENTRY', id: 'e1', title: 'Big <post>', revisions: 40 }, { kind: 'PAGE', id: 'p1', title: 'About', revisions: 9 }],
    lastSweep: report({ total: 5 }), nextSweep: report({ total: 7, dryRun: true }), total: 131 };
  discovery = { state: 'OK', lastAttempt: '2026-11-25T10:00:00Z', lastSuccess: '2026-11-25T09:00:00Z', scoredEntries: 120,
    candidates: 30 };
  analytics = { configured: true, reachable: true, registered: 3, total: 4, lastRead: '2026-11-25T08:30:00Z',
    failures: [{ handle: 'beta', error: 'timeout <x>', at: '2026-11-25T08:00:00Z' }] };
});

const capture = (path: string, status: number, body: object) =>
  http.post(`${API}${path}`, async ({ request }) => {
    const text = await request.text();
    posts.push({ url: new URL(request.url).pathname.replace('/pressroom/api/v1', ''), body: text === '' ? null : JSON.parse(text) as unknown,
      csrf: request.headers.get('X-CSRF-TOKEN') });
    return HttpResponse.json(body, { status });
  });

const openScreen = async () => {
  const view = renderRoutes(siteAdminRoutes, '/admin/maintenance', { boot: adminBoot });
  await screen.findByText('Revisions of posts');
  await screen.findByRole('option', { name: 'Alpha <Blog> (alpha)' });
  return view;
};
const choose = async (user: ReturnType<typeof userEvent.setup>, label: string) => {
  await user.selectOptions(screen.getByLabelText('Weblog'), label);
};

describe('Maintenance', () => {
  it('shows the title, the picker with a disabled placeholder, and every weblog action disabled until one is chosen', async () => {
    await openScreen();
    expect(screen.getByRole('heading', { level: 1, name: 'Maintenance' })).toBeInTheDocument();
    expect(screen.getByText(/Rebuild a weblog's search index/)).toHaveClass('subtitle');
    const select = screen.getByLabelText('Weblog');
    expect(select).toHaveAttribute('id', 'maintenanceWeblog');
    expect(within(select).getByRole('option', { name: 'Choose a weblog' })).toBeDisabled();
    expect(within(select).getByRole('option', { name: 'Alpha <Blog> (alpha)' })).toBeInTheDocument();
    expect(select).toHaveValue('');
    for (const name of ['Flush cache', 'Rebuild search index', 'Regenerate image renditions']) {
      expect(screen.getByRole('button', { name })).toBeDisabled();
    }
    expect(document.title).toBe('Pressroom: Maintenance');
  });

  it('flushes the chosen weblog cache with no confirmation and says so', async () => {
    server.use(capture('/admin/weblogs/alpha/actions/flush-cache', 202, { action: 'flush-cache', weblog: 'alpha' }));
    const user = userEvent.setup();
    await openScreen();
    await choose(user, 'alpha');
    await user.click(screen.getByRole('button', { name: 'Flush cache' }));
    expect(await screen.findByText('Page cache flushed.')).toBeInTheDocument();
    expect(posts).toEqual([{ url: '/admin/weblogs/alpha/actions/flush-cache', body: null, csrf: 'tok' }]);
  });

  it('confirms a rebuild naming the weblog, then reports it scheduled; cancel does nothing', async () => {
    server.use(capture('/admin/weblogs/beta/actions/rebuild-index', 202, { action: 'rebuild-index', weblog: 'beta' }));
    const user = userEvent.setup();
    await openScreen();
    await choose(user, 'beta');
    await user.click(screen.getByRole('button', { name: 'Rebuild search index' }));
    let dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Rebuild the search index for beta? This can take a while on a large weblog.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(posts).toEqual([]);
    await user.click(screen.getByRole('button', { name: 'Rebuild search index' }));
    dialog = screen.getByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Rebuild search index' }));
    expect(await screen.findByText('Search index rebuild scheduled.')).toBeInTheDocument();
    expect(posts.map((p) => p.url)).toEqual(['/admin/weblogs/beta/actions/rebuild-index']);
  });

  it('offers no Rebuild search index when the site runs without search (the JSP hid it on search.enabled)', async () => {
    searchEnabled = false;
    await openScreen();
    await screen.findByRole('button', { name: 'Flush cache' });
    expect(screen.queryByRole('button', { name: 'Rebuild search index' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Regenerate image renditions' })).toBeInTheDocument();
  });

  it('confirms and starts the rendition regeneration', async () => {
    server.use(capture('/admin/weblogs/alpha/actions/regenerate-renditions', 202, { action: 'regenerate-renditions', weblog: 'alpha', count: 3 }));
    const user = userEvent.setup();
    await openScreen();
    await choose(user, 'alpha');
    await user.click(screen.getByRole('button', { name: 'Regenerate image renditions' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Regenerate every image rendition for alpha? This re-encodes every uploaded photo.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Regenerate image renditions' }));
    expect(await screen.findByText('Renditions regenerated. Images processed: 3.')).toBeInTheDocument();
    expect(posts.map((p) => p.url)).toEqual(['/admin/weblogs/alpha/actions/regenerate-renditions']);
  });

  it('shows a refused action as the problem, in place', async () => {
    server.use(http.post(`${API}/admin/weblogs/alpha/actions/flush-cache`, () =>
      HttpResponse.json({ code: 'weblog.notFound', detail: 'No such weblog.' }, { status: 404 })));
    const user = userEvent.setup();
    await openScreen();
    await choose(user, 'alpha');
    await user.click(screen.getByRole('button', { name: 'Flush cache' }));
    expect(await screen.findByText('No such weblog.')).toBeInTheDocument();
    expect(screen.queryByText('Page cache flushed.')).not.toBeInTheDocument();
  });

  it('describes discovery and refreshes it, reporting each outcome as the JSP did', async () => {
    const user = userEvent.setup();
    await openScreen();
    const section = document.querySelector('.maintenance-discovery') as HTMLElement;
    expect(within(section).getByRole('heading', { level: 2, name: 'Front page discovery' })).toHaveClass('section-head');
    expect(within(section).getByText('Popularity is current.')).toBeInTheDocument();
    expect(within(section).getByText('Last attempt')).toBeInTheDocument();
    expect(within(section).getByText('2026-11-25 10:00 UTC')).toBeInTheDocument();
    expect(within(section).getByText('120 posts with traffic out of 30 eligible')).toBeInTheDocument();

    server.use(capture('/admin/discovery/refresh', 200, { state: 'OK', scoredEntries: 5, candidates: 6 }));
    await user.click(within(section).getByRole('button', { name: 'Refresh now' }));
    expect(await screen.findByText('Popularity refreshed.')).toBeInTheDocument();

    discovery = { state: 'FAILED', scoredEntries: 0, candidates: 0, lastError: 'umami <down>' };
    server.use(capture('/admin/discovery/refresh', 200, discovery));
    await user.click(within(section).getByRole('button', { name: 'Refresh now' }));
    expect(await screen.findByText('Refresh failed: umami <down>')).toBeInTheDocument();
    await waitFor(() => { expect(within(section).getByText('The last refresh could not read Umami; the previous popularity is still in use.')).toBeInTheDocument(); });
    expect(within(section).getByText('Last error')).toBeInTheDocument();

    discovery = { state: 'DISABLED', scoredEntries: 0, candidates: 0 };
    server.use(capture('/admin/discovery/refresh', 200, discovery));
    await user.click(within(section).getByRole('button', { name: 'Refresh now' }));
    expect(await screen.findAllByText('Umami is not connected; the front page uses random picks only.')).not.toHaveLength(0);
    expect(within(section).queryByText('Popularity')).not.toBeInTheDocument();
  });

  it('names a discovery that never ran', async () => {
    discovery = { state: 'NEVER', scoredEntries: 0, candidates: 0 };
    await openScreen();
    expect(screen.getByText('Not refreshed yet.')).toBeInTheDocument();
  });

  it('shows what the revision store holds and the next sweep', async () => {
    await openScreen();
    const section = document.querySelector('.maintenance-revisions') as HTMLElement;
    expect(within(section).getByRole('heading', { level: 2, name: 'Revisions' })).toBeInTheDocument();
    const dd = (label: string) => (within(section).getByText(label).nextElementSibling as HTMLElement).textContent;
    expect(dd('Revisions of posts')).toBe('120');
    expect(dd('Revisions of pages')).toBe('8');
    expect(dd('Kept revisions')).toBe('3');
    expect(dd('Approximate size')).toBe('2,441 KB');
    expect(dd('Oldest revision')).toBe('2026-01-02 03:04 UTC');
    expect(dd('Last sweep')).toBe('2026-11-25 10:00 UTC, removed 5');
    expect(dd('The next sweep would remove')).toBe('7');
    expect(section.querySelector('.maintenance-revisions-next')).toHaveTextContent('7');
    expect(within(section).getByText('Items with the most revisions')).toBeInTheDocument();
    expect(within(section).getByText('40: Big <post> (post)')).toBeInTheDocument();
    expect(within(section).getByText('9: About (page)')).toBeInTheDocument();
    expect(within(section).queryByText(/Part of this figure/)).not.toBeInTheDocument();
  });

  it('says so when nothing was swept yet, and flags an incomplete figure and an unmeetable ceiling', async () => {
    stats = { ...stats, oldest: undefined, lastSweep: undefined, largest: [],
      nextSweep: report({ total: 1, incomplete: true, ceilingUnmeetable: true }) };
    await openScreen();
    expect(screen.getByText('None since the application started.')).toBeInTheDocument();
    expect(screen.getByText(/Part of this figure could not be worked out/)).toHaveClass('pagetip');
    expect(screen.getByText(/The site-wide limit cannot be met/)).toBeInTheDocument();
    expect(screen.queryByText('Oldest revision')).not.toBeInTheDocument();
    expect(screen.queryByText('Items with the most revisions')).not.toBeInTheDocument();
  });

  it('confirms the sweep with the figure, runs it for real, and reports the removal', async () => {
    const user = userEvent.setup();
    await openScreen();
    server.use(capture('/admin/revisions/sweep', 200, report({ total: 7 })));
    await user.click(screen.getByRole('button', { name: 'Sweep now' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Remove revisions under the current limits now? This cannot be undone. Revisions to be removed: 7')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Sweep now' }));
    expect(await screen.findByText('Sweep finished: 7 revisions removed.')).toBeInTheDocument();
    expect(posts).toEqual([{ url: '/admin/revisions/sweep', body: { dryRun: false }, csrf: 'tok' }]);
  });

  it('never reports a partly failed sweep as a success', async () => {
    const user = userEvent.setup();
    await openScreen();
    server.use(capture('/admin/revisions/sweep', 200, report({ total: 3, weblogsFailed: 1 })));
    await user.click(screen.getByRole('button', { name: 'Sweep now' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Sweep now' }));
    expect(await screen.findByText('The sweep did not finish cleanly: 3 revisions removed, and part of it failed and removed nothing. See the log.')).toBeInTheDocument();
    expect(screen.queryByText(/Sweep finished/)).not.toBeInTheDocument();
  });

  it('treats a failed ceiling the same way, and shows a refused sweep as the problem', async () => {
    const user = userEvent.setup();
    await openScreen();
    server.use(capture('/admin/revisions/sweep', 200, report({ total: 0, ceilingFailed: true })));
    await user.click(screen.getByRole('button', { name: 'Sweep now' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Sweep now' }));
    expect(await screen.findByText(/did not finish cleanly: 0 revisions removed/)).toBeInTheDocument();
    server.use(http.post(`${API}/admin/revisions/sweep`, () => HttpResponse.json({ code: 'x', detail: 'Sweep refused.' }, { status: 403 })));
    await user.click(screen.getByRole('button', { name: 'Sweep now' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Sweep now' }));
    expect(await screen.findByText('Sweep refused.')).toBeInTheDocument();
  });

  it('does not sweep when the confirmation is cancelled', async () => {
    const user = userEvent.setup();
    await openScreen();
    await user.click(screen.getByRole('button', { name: 'Sweep now' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));
    expect(posts).toEqual([]);
  });

  it('says the revision figures could not be read, and the rest of the screen still works', async () => {
    server.use(http.get(`${API}/admin/revisions`, () => HttpResponse.json({ code: 'x', detail: 'boom' }, { status: 400 })));
    renderRoutes(siteAdminRoutes, '/admin/maintenance', { boot: adminBoot });
    expect(await screen.findByText('The revision figures could not be read. See the log.')).toBeInTheDocument();
    expect(screen.getByLabelText('Weblog')).toBeInTheDocument();
    expect(screen.queryByText('Revisions of posts')).not.toBeInTheDocument();
  });

  it('shows analytics registration, reachability, last read and failures; sync is requested in the background', async () => {
    const user = userEvent.setup();
    await openScreen();
    const section = document.querySelector('.maintenance-analytics') as HTMLElement;
    expect(within(section).getByRole('heading', { level: 2, name: 'Analytics' })).toBeInTheDocument();
    expect(within(section).getByText('3 of 4 blogs are registered with Umami.')).toBeInTheDocument();
    expect((within(section).getByText('Umami').nextElementSibling as HTMLElement).textContent).toBe('Reachable');
    expect((within(section).getByText('Last traffic read').nextElementSibling as HTMLElement).textContent).toBe('2026-11-25 08:30 UTC');
    expect(within(section).getByText('Blogs not registered')).toBeInTheDocument();
    expect(within(section).getByRole('listitem')).toHaveTextContent('beta: timeout <x> (2026-11-25 08:00 UTC)');
    server.use(capture('/admin/analytics/sync', 202, analytics));
    await user.click(within(section).getByRole('button', { name: 'Sync now' }));
    expect(await screen.findByText('Sync requested. It runs in the background; reload this page in a moment.')).toBeInTheDocument();
    expect(posts.map((p) => p.url)).toEqual(['/admin/analytics/sync']);
  });

  it('shows the other analytics states: off, unreachable, never read, a read error', async () => {
    analytics = { configured: true, reachable: false, registered: 0, total: 1, lastReadError: 'HTTP 502', failures: [] };
    await openScreen();
    expect(screen.getByText('Not reachable')).toBeInTheDocument();
    expect(screen.getByText('Not read yet')).toBeInTheDocument();
    expect(screen.getByText('Last read error')).toBeInTheDocument();
    expect(screen.getByText('HTTP 502')).toBeInTheDocument();
    expect(screen.queryByText('Blogs not registered')).not.toBeInTheDocument();
  });

  it('shows analytics off and an unchecked Umami', async () => {
    analytics = { configured: false, registered: 0, total: 0, failures: [] };
    const { unmount } = await openScreen();
    expect(screen.getByText('Analytics is off: no Umami address is configured.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sync now' })).not.toBeInTheDocument();
    unmount();
    analytics = { configured: true, registered: 1, total: 1, failures: [] };
    await openScreen();
    expect(screen.getByText('Not checked yet')).toBeInTheDocument();
  });

  it('is not offered to an editor', async () => {
    renderRoutes(siteAdminRoutes, '/admin/maintenance', { boot: editorBoot });
    expect(await screen.findByText('That page does not exist.')).toBeInTheDocument();
  });
});
