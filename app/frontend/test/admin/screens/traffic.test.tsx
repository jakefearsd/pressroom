import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { appRoutes } from '../../../src/admin/router';
import { adminBoot, editorBoot, renderRoutes } from '../support';

const API = 'http://pressroom.test/pressroom/api/v1';
const LEGACY = 'http://pressroom.test/pressroom/pressroom-ui';

const requests: string[] = [];
let blog: object;
let site: object;

const snapshot = (over: object = {}) => ({
  state: 'OK', asOf: '2026-11-08T10:15:00Z', period: '30d', timeZone: 'Europe/Berlin',
  current: { visitors: 40, visits: 50, views: 120, bounces: 10, totalTimeSeconds: 3600 },
  previous: { visitors: 20, visits: 25, views: 60, bounces: 5, totalTimeSeconds: 1800 },
  series: [{ day: '2026-11-07', visitors: 7, views: 15 }, { day: '2026-11-08', visitors: 9, views: 30 }],
  posts: [
    { entryId: 'e1', title: 'Tom & Jerry', path: '/alpha/entry/tom', views: 90, visitors: 30, trend: 0.5 },
    { entryId: null, title: null, path: '/alpha/tags/x', views: 30, visitors: null, trend: null },
    { entryId: 'e2', title: 'Second', path: '/alpha/entry/second', views: 20, visitors: 40, trend: -0.2 }
  ],
  breakdowns: {
    sources: [{ key: 'channel:search', value: 12 }, { key: 'google.com', value: 7 }],
    devices: [{ key: 'os:Linux', value: 3 }, { key: 'device:desktop', value: 9 }],
    speed: [{ key: 'lcp', value: 2500 }, { key: 'weird', value: 4 }]
  },
  pathsCappedAt: 0,
  ...over
});

const server = setupServer(
  http.get(`${API}/me`, () => HttpResponse.json({ userName: 'jake', screenName: 'Jake', kind: 'PERSON', globalAdmin: false, tokenScope: {},
    weblogs: [{ handle: 'alpha', name: 'Alpha Blog', permission: 'ADMIN' }] })),
  http.get(`${API}/meta`, () => HttpResponse.json({ version: '1', features: { customThemes: true, groupBlogging: true }, limits: {} })),
  http.get(`${API}/weblogs/:handle`, ({ params }) => HttpResponse.json({ handle: params['handle'], name: 'Alpha Blog', active: true, timeZone: 'Europe/Berlin' })),
  http.get(`${API}/weblogs/:handle/traffic`, ({ request }) => {
    requests.push(new URL(request.url).search);
    return HttpResponse.json(blog);
  }),
  http.get(`${API}/admin/traffic/:handle`, ({ params, request }) => {
    requests.push(`admin:${String(params['handle'])}${new URL(request.url).search}`);
    return HttpResponse.json(blog);
  }),
  http.get(`${API}/admin/traffic`, ({ request }) => {
    requests.push(`site${new URL(request.url).search}`);
    return HttpResponse.json(site);
  })
);
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => { requests.length = 0; blog = snapshot(); });

const page = () => document.getElementById('trafficPage') as HTMLElement;
const tableRows = (table: HTMLElement) => [...table.querySelectorAll('tr')].map((tr) => [...tr.children].map((c) => c.textContent));

describe('weblog traffic', () => {
  it('shows the headline tiles, the chart, the tabs and the posts as the JSP page did', async () => {
    renderRoutes(appRoutes, '/weblogs/alpha/traffic', { boot: editorBoot });
    expect(await screen.findByRole('heading', { level: 1, name: 'Traffic' })).toHaveClass('pressroom-page-title');
    await waitFor(() => { expect(page().querySelector('.traffic-tiles')).not.toBeNull(); });
    expect(page().querySelector('.subtitle')).toHaveTextContent('How many people read Alpha Blog, and where they came from');
    const tiles = [...page().querySelectorAll('.traffic-tile')].map((t) => [...t.children].map((c) => c.textContent));
    expect(tiles[0]).toEqual(['Visitors', '40', '+100%']);
    expect(page().querySelector('.traffic-tile-change.up')).not.toBeNull();
    const chart = page().querySelector('svg.traffic-chart') as SVGElement;
    expect(chart).toHaveAttribute('viewBox', '0 0 600 140');
    expect(chart.querySelectorAll('rect.traffic-bar')).toHaveLength(2);
    expect(chart.querySelector('rect.traffic-bar title')).toHaveTextContent('Nov 7: 15');
    expect(chart.querySelector('text.traffic-axis')).toHaveTextContent('Nov 7');
    const tabs = [...page().querySelectorAll('.traffic-tab')].map((a) => a.textContent);
    expect(tabs).toEqual(['Posts', 'Sources', 'Places', 'Devices', 'Events', 'Campaigns', 'Speed']);
    expect(page().querySelector('.traffic-tab.on')).toHaveAttribute('aria-current', 'page');
    const table = page().querySelector('table.traffic-table') as HTMLElement;
    expect(tableRows(table)).toEqual([
      ['Post', 'Views', 'Visitors', 'Trend'],
      ['Tom & Jerry', '90', '30', '+50%'],
      ['/alpha/tags/x', '30', '—', '—'],
      ['Second', '20', '40', '-20%']
    ]);
    expect(within(table).getByRole('link', { name: 'Tom & Jerry' }))
      .toHaveAttribute('href', `${LEGACY}/authoring/entryEdit.rol?weblog=alpha&bean.id=e1`);
    expect(table.querySelector('span.data')).toHaveTextContent('/alpha/tags/x');
    expect(page().querySelector('th[aria-sort="descending"]')).toHaveTextContent('Views');
    expect(page().querySelector('.traffic-fresh')).toHaveTextContent('Numbers from Umami as of 11:15, refreshed every 15 minutes');
    expect(requests).toEqual(['?period=30d']);
  });

  it('switches the period without a page load, keeping the tab, and marks the chosen one', async () => {
    renderRoutes(appRoutes, '/weblogs/alpha/traffic?tab=sources', { boot: editorBoot });
    await screen.findByText('Sources', { selector: '.traffic-tab.on' });
    const nav = page().querySelector('nav.traffic-period') as HTMLElement;
    expect(within(nav).getByRole('link', { name: '30d' })).toHaveAttribute('aria-current', 'true');
    await userEvent.click(within(nav).getByRole('link', { name: '7d' }));
    await waitFor(() => { expect(requests).toEqual(['?period=30d', '?period=7d']); });
    await waitFor(() => { expect(within(nav).getByRole('link', { name: '7d' })).toHaveClass('traffic-period-link', 'on'); });
    expect(within(nav).getByRole('link', { name: '30d' })).not.toHaveAttribute('aria-current');
    expect(page().querySelector('.traffic-tab.on')).toHaveTextContent('Sources');
  });

  it('switches the tab: a grouped breakdown with its group headings, devices in order, speed measures', async () => {
    renderRoutes(appRoutes, '/weblogs/alpha/traffic', { boot: editorBoot });
    await userEvent.click(await screen.findByRole('link', { name: 'Sources' }));
    const table = await waitFor(() => {
      const found = page().querySelector('table.traffic-table') as HTMLElement;
      expect(tableRows(found)[0]).toEqual(['Source', 'Visitors']);
      return found;
    });
    expect(tableRows(table)).toEqual([['Source', 'Visitors'], ['Channels'], ['search', '12'], ['google.com', '7']]);
    expect(table.querySelector('tr.traffic-group th')).toHaveAttribute('scope', 'rowgroup');

    await userEvent.click(screen.getByRole('link', { name: 'Devices' }));
    await waitFor(() => { expect(tableRows(page().querySelector('table') as HTMLElement)[0]).toEqual(['Device', 'Visitors']); });
    expect(tableRows(page().querySelector('table') as HTMLElement).slice(1).map((r) => r[0])).toEqual(['Device type', 'desktop', 'Operating system', 'Linux']);

    await userEvent.click(screen.getByRole('link', { name: 'Speed' }));
    await waitFor(() => { expect(tableRows(page().querySelector('table') as HTMLElement)[0]).toEqual(['Measure', 'Typical for readers (75th percentile)']); });
    expect(tableRows(page().querySelector('table') as HTMLElement).slice(1)).toEqual([['Largest content shown', '2.5 s'], ['weird', '4 ms']]);
  });

  it('says so when a tab has nothing, and what speed needs', async () => {
    blog = snapshot({ breakdowns: {} });
    renderRoutes(appRoutes, '/weblogs/alpha/traffic?tab=events', { boot: editorBoot });
    expect(await screen.findByText('Nothing in this report for this period.')).toHaveClass('traffic-empty');
    await userEvent.click(screen.getByRole('link', { name: 'Speed' }));
    expect(await screen.findByText(/No speed data yet/)).toBeInTheDocument();
  });

  it('re-sorts the posts by the column chosen, and no posts is said in words', async () => {
    renderRoutes(appRoutes, '/weblogs/alpha/traffic', { boot: editorBoot });
    await screen.findByText('Tom & Jerry');
    await userEvent.click(screen.getByRole('link', { name: 'Visitors' }));
    await waitFor(() => { expect(page().querySelector('th[aria-sort="descending"]')).toHaveTextContent('Visitors'); });
    expect([...page().querySelectorAll('tr')].slice(1).map((tr) => tr.children[0]?.textContent)).toEqual(['Second', 'Tom & Jerry', '/alpha/tags/x']);
    await userEvent.click(screen.getByRole('link', { name: 'Trend' }));
    await waitFor(() => { expect(page().querySelector('th[aria-sort="descending"]')).toHaveTextContent('Trend'); });
    expect([...page().querySelectorAll('tr')].slice(1).map((tr) => tr.children[0]?.textContent)).toEqual(['Tom & Jerry', 'Second', '/alpha/tags/x']);
    blog = snapshot({ posts: [] });
    await userEvent.click(screen.getByRole('link', { name: '7d' }));
    expect(await screen.findByText('No post has been read in this period.')).toBeInTheDocument();
  });

  it('falls back to 30 days, posts and views for values it does not offer', async () => {
    renderRoutes(appRoutes, '/weblogs/alpha/traffic?period=1y&tab=zz&sort=q', { boot: editorBoot });
    await screen.findByText('Tom & Jerry');
    expect(requests).toEqual(['?period=30d']);
    expect(page().querySelector('.traffic-tab.on')).toHaveTextContent('Posts');
    expect(page().querySelector('th[aria-sort]')).toHaveTextContent('Views');
  });

  it('says how many paths are listed when the list was cut, and links Open in Umami in a new tab', async () => {
    blog = snapshot({ pathsCappedAt: 1000, umamiUrl: 'https://stats.example.com/websites/w1' });
    renderRoutes(appRoutes, '/weblogs/alpha/traffic', { boot: editorBoot });
    expect(await screen.findByText('Showing the top 1,000 paths; a post read less often than these is not listed.')).toBeInTheDocument();
    const link = screen.getByRole('link', { name: 'Open in Umami' });
    expect(link).toHaveAttribute('href', 'https://stats.example.com/websites/w1');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener');
  });

  it('tells each state in words and shows no numbers when there are none', async () => {
    blog = snapshot({ state: 'NOT_CONFIGURED', current: undefined, previous: undefined, asOf: undefined });
    const first = renderRoutes(appRoutes, '/weblogs/alpha/traffic', { boot: editorBoot });
    const notice = await screen.findByRole('status');
    expect(notice).toHaveClass('traffic-notice', 'traffic-notice-notConfigured');
    expect(notice).toHaveTextContent('Traffic is not set up on this server');
    expect(page().querySelector('.traffic-tiles')).toBeNull();
    expect(page().querySelector('.traffic-fresh')).toBeEmptyDOMElement();
    first.unmount();

    blog = snapshot({ state: 'NO_DATA', current: undefined, registrationError: 'HTTP 401' });
    renderRoutes(appRoutes, '/weblogs/alpha/traffic', { boot: editorBoot });
    const failed = await screen.findByText('Not registered with Umami yet: HTTP 401. Pressroom retries on its next sync.');
    expect(failed).toHaveClass('traffic-notice-notRegistered');
    expect(screen.getAllByRole('status')).toHaveLength(1);
  });

  it('says a stale snapshot is stale and how old, and an all-zero one that the zeros are real', async () => {
    const old = new Date(Date.now() - 125 * 60_000).toISOString();
    blog = snapshot({ state: 'STALE', asOf: old });
    const first = renderRoutes(appRoutes, '/weblogs/alpha/traffic', { boot: editorBoot });
    expect(await screen.findByText(/Last updated 125 minutes ago/)).toHaveClass('traffic-notice-stale');
    expect(page().querySelector('.traffic-tiles')).not.toBeNull();
    first.unmount();

    const zero = { visitors: 0, visits: 0, views: 0, bounces: 0, totalTimeSeconds: 0 };
    blog = snapshot({ current: zero, previous: zero, series: [] });
    renderRoutes(appRoutes, '/weblogs/alpha/traffic', { boot: editorBoot });
    expect(await screen.findByText('Nobody has read this blog in this period. These zeros are real counts.')).toHaveClass('traffic-notice-zero');
  });

  it('shows a refused read in place: the problem, no numbers', async () => {
    server.use(http.get(`${API}/weblogs/:handle/traffic`, () => HttpResponse.json({ code: 'weblog.notFound', detail: 'No such weblog.' }, { status: 404 })));
    renderRoutes(appRoutes, '/weblogs/alpha/traffic', { boot: editorBoot });
    expect((await screen.findAllByText('No such weblog.')).length).toBeGreaterThan(0);
    expect(page().querySelector('.traffic-tiles')).toBeNull();
    expect(page().querySelector('.traffic-fresh')).toBeNull();
  });

  it('shows a 403 as its problem too', async () => {
    server.use(http.get(`${API}/weblogs/:handle/traffic`, () => HttpResponse.json({ code: 'auth.forbidden', detail: 'You may not read this.' }, { status: 403 })));
    renderRoutes(appRoutes, '/weblogs/alpha/traffic', { boot: editorBoot });
    expect(await screen.findByText('You may not read this.')).toBeInTheDocument();
  });

  it('reads the site space through the site admin route, and names it without asking for a weblog', async () => {
    renderRoutes(appRoutes, '/weblogs/site-space/traffic?period=7d', { boot: adminBoot });
    await screen.findByText('Tom & Jerry');
    expect(requests).toEqual(['admin:site-space?period=7d']);
    expect(page().querySelector('.subtitle')).toHaveTextContent('How many people read Site, and where they came from');
  });
});

describe('site traffic', () => {
  const blogs = [
    { handle: 'alpha', name: 'Alpha', siteSpace: false, standing: 'READ', visitors: 40, views: 1200, visits: 50, change: 0.25, topPostTitle: 'Tom & Jerry' },
    { handle: 'site-space', name: 'Site', siteSpace: true, standing: 'READ', visitors: 1, views: 2, visits: 1, change: -0.5, topPostTitle: null },
    { handle: 'beta', name: 'Beta', siteSpace: false, standing: 'NO_DATA' },
    { handle: 'gamma', name: 'Gamma', siteSpace: false, standing: 'NOT_REGISTERED' }
  ];
  beforeEach(() => {
    site = { state: 'OK', asOf: '2026-11-08T10:15:00Z', period: '30d', timeZone: 'UTC', totalViews: 1202, totalVisits: 51, blogs,
      posts: [{ entryId: 'e1', title: 'Tom & Jerry', path: '/alpha/tom', views: 90, visitors: 30 }, { entryId: null, path: '/x', views: 3, visitors: null }],
      sources: [{ key: 'google.com', value: 12 }] };
  });
  const root = () => document.getElementById('siteTrafficPage') as HTMLElement;

  it('lists every blog with its numbers, dashes for those without, and the totals', async () => {
    renderRoutes(appRoutes, '/admin/traffic', { boot: adminBoot });
    expect(await screen.findByRole('heading', { level: 1, name: 'Traffic' })).toBeInTheDocument();
    await screen.findByText('Alpha');
    expect(root().querySelector('.subtitle')).toHaveTextContent('Every blog on this server');
    const [main] = root().querySelectorAll('table');
    expect(tableRows(main as HTMLElement)).toEqual([
      ['Blog', 'Visitors', 'Views', 'Visits', 'Views, change', 'Most read post'],
      ['Alpha', '40', '1,200', '50', '+25%', 'Tom & Jerry'],
      ['Site pages (front page)', '1', '2', '1', '-50%', ''],
      ['Beta', '—', '—', '—', '', 'No numbers yet'],
      ['Gamma', '—', '—', '—', '', 'Not registered with Umami yet; see Maintenance'],
      ['Total', '—', '1,202', '51', '', '']
    ]);
    expect(root().querySelector('tr.traffic-total')).not.toBeNull();
    expect(root().querySelector('td.n.up')).toHaveTextContent('+25%');
    expect(root().querySelector('td.n.dn')).toHaveTextContent('-50%');
    expect(root().querySelector('.traffic-fresh')).toHaveTextContent('as of 10:15');
    expect(screen.getByRole('heading', { level: 2, name: 'Most read posts' })).toHaveClass('traffic-heading');
    expect(screen.getByRole('heading', { level: 2, name: 'Where readers came from' })).toBeInTheDocument();
    const tables = root().querySelectorAll('table');
    expect(tableRows(tables[1] as HTMLElement)).toEqual([['Post', 'Views', 'Visitors'], ['Tom & Jerry', '90', '30'], ['/x', '3', '—']]);
    expect(tableRows(tables[2] as HTMLElement)).toEqual([['Source', 'Visitors'], ['google.com', '12']]);
  });

  it('links each blog to its own traffic page, carrying the period, client-side', async () => {
    renderRoutes(appRoutes, '/admin/traffic', { boot: adminBoot });
    const alpha = await screen.findByRole('link', { name: 'Alpha' });
    expect(alpha).toHaveAttribute('href', '/weblogs/alpha/traffic?period=30d');
    expect(screen.getByRole('link', { name: 'Site pages (front page)' })).toHaveAttribute('href', '/weblogs/site-space/traffic?period=30d');
  });

  it('switches the period', async () => {
    renderRoutes(appRoutes, '/admin/traffic', { boot: adminBoot });
    await screen.findByText('Alpha');
    await userEvent.click(screen.getByRole('link', { name: '90d' }));
    await waitFor(() => { expect(requests).toEqual(['site?period=30d', 'site?period=90d']); });
    await waitFor(() => { expect(screen.getByRole('link', { name: '90d' })).toHaveClass('on'); });
    expect(screen.getByRole('link', { name: 'Alpha' })).toHaveAttribute('href', '/weblogs/alpha/traffic?period=90d');
  });

  it('says there is no setup, and says no blog was read, without a body', async () => {
    site = { state: 'NOT_CONFIGURED', blogs: [], posts: [], sources: [], totalViews: 0, totalVisits: 0 };
    const first = renderRoutes(appRoutes, '/admin/traffic', { boot: adminBoot });
    expect(await screen.findByText(/Traffic is not set up/)).toHaveClass('traffic-notice-notConfigured');
    expect(root().querySelector('table')).toBeNull();
    first.unmount();

    site = { state: 'OK', totalViews: 0, totalVisits: 0, blogs: [{ handle: 'a', name: 'A', standing: 'READ', visitors: 0, views: 0, visits: 0 }], posts: [], sources: [] };
    renderRoutes(appRoutes, '/admin/traffic', { boot: adminBoot });
    expect(await screen.findByText('Nobody has read any blog in this period. These zeros are real counts.')).toHaveClass('traffic-notice-siteZero');
    expect(screen.getByText('No post has been read in this period.')).toBeInTheDocument();
    expect(screen.getByText('Nothing in this report for this period.')).toBeInTheDocument();
  });

  it('refuses an editor who is no site admin with the API problem, and shows no numbers', async () => {
    server.use(http.get(`${API}/admin/traffic`, () => HttpResponse.json({ code: 'auth.forbidden', detail: 'Site admins only.' }, { status: 403 })));
    renderRoutes(appRoutes, '/admin/traffic', { boot: editorBoot });
    expect(await screen.findByText('Site admins only.')).toBeInTheDocument();
    expect(root().querySelector('table')).toBeNull();
    expect(screen.queryByRole('link', { name: 'Maintenance' })).toBeNull();
  });
});
