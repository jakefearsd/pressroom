import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { siteAdminRoutes } from '../../../src/admin/routes/siteRoutes';
import { adminBoot, editorBoot, renderRoutes } from '../support';

const API = 'http://pressroom.test/pressroom/api/v1';
const def = (name: string, key: string, type: string, extra: object = {}) => ({ name, key, type, defaultValue: '', rows: 5, cols: 25, ...extra });
const groups = [
  { name: 'siteSettings', key: 'configForm.siteSettings', properties: [
    def('site.name', 'configForm.siteName', 'string'),
    def('site.description', 'configForm.siteDescription', 'text', { rows: 4 }),
    def('site.frontpage.weblog.handle', 'configForm.frontpageWeblogHandle', 'string'),
    def('site.frontpage.page', 'configForm.frontpagePage', 'string'),
    def('site.absoluteurl', 'configForm.absoluteUrl', 'string')
  ] },
  { name: 'discoverySettings', key: 'configForm.discoverySettings', properties: [
    def('site.discovery.windowDays', 'configForm.discoveryWindowDays', 'integer'),
    def('site.discovery.refreshSeconds', 'configForm.discoveryRefreshMinutes', 'integer'),
    def('site.discovery.ratio', 'configForm.discoverySharePercent', 'float')
  ] },
  { name: 'userSettings', key: 'configForm.userSettings', properties: [
    def('groupblogging.enabled', 'configForm.groupBloggingEnabled', 'boolean'),
    def('site.unknown', 'configForm.hideUserNames', 'weird')
  ] },
  { name: 'revisionSettings', key: 'configForm.revisionSettings', properties: [
    def('entry.revisions.retention', 'configForm.entryRevisionsRetention', 'integer')
  ] }
];
let values: Record<string, string>;
let sitePages: object[] | 404;
let patched: { body: Record<string, string>; csrf: string | null }[];
let nextSweep: object;

const entries = () => groups.flatMap((g) => g.properties).map((p) => ({ name: p.name, type: p.type, value: values[p.name] ?? '' }));
const server = setupServer(
  http.get(`${API}/admin/config/definitions`, () => HttpResponse.json(groups)),
  http.get(`${API}/admin/config`, () => HttpResponse.json(entries())),
  http.get(`${API}/weblogs`, () => HttpResponse.json({ items: [{ handle: 'alpha', name: 'Alpha' }, { handle: 'site-space', name: 'Site' },
    { handle: 'beta', name: 'Beta' }], offset: 0, limit: 200, hasMore: false })),
  http.get(`${API}/weblogs/site-space/pages`, () => sitePages === 404
    ? HttpResponse.json({ code: 'weblog.notFound', detail: 'No such weblog.' }, { status: 404 }) : HttpResponse.json(sitePages)),
  http.get(`${API}/admin/revisions`, () => HttpResponse.json({ nextSweep }))
);
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => {
  values = { 'site.name': 'My Site', 'site.description': 'About <us>', 'site.frontpage.weblog.handle': '', 'site.frontpage.page': '',
    'site.absoluteurl': 'https://x.example', 'site.discovery.windowDays': '30', 'site.discovery.refreshSeconds': '600',
    'site.discovery.ratio': '0.5', 'groupblogging.enabled': 'true', 'site.unknown': 'zzz', 'entry.revisions.retention': '10' };
  sitePages = [{ slug: 'welcome', title: 'Welcome <home>', status: 'PUBLISHED' }, { slug: 'draft', title: 'Draft', status: 'DRAFT' }];
  patched = [];
  nextSweep = { total: 0, incomplete: false };
});

const accept = () => server.use(http.patch(`${API}/admin/config`, async ({ request }) => {
  const body = await request.json() as Record<string, string>;
  patched.push({ body, csrf: request.headers.get('X-CSRF-TOKEN') });
  values = { ...values, ...body };
  return HttpResponse.json(Object.entries(body).map(([name, value]) => ({ name, value, type: 'string' })));
}));
const openScreen = async () => {
  const view = renderRoutes(siteAdminRoutes, '/admin/config', { boot: adminBoot });
  await screen.findByLabelText('Site name (for the front page and feeds)');
  return view;
};
const save = (user: ReturnType<typeof userEvent.setup>) => user.click(screen.getByRole('button', { name: 'Save' }));

describe('Global config', () => {
  it('draws the groups of the definitions with a field per type, as the JSP did', async () => {
    await openScreen();
    expect(screen.getByRole('heading', { level: 1, name: 'Configuration' })).toBeInTheDocument();
    expect(screen.getByText('Settings for the whole site.')).toHaveClass('subtitle');
    expect(screen.getByText(/Changes apply to the whole site when you save them\./)).toBeInTheDocument();
    expect(document.querySelector('form.settings-grid.form-stacked')).not.toBeNull();
    const heads = [...document.querySelectorAll('h2.section-head')].map((h) => [h.id, h.textContent]);
    expect(heads).toEqual([
      ['cfg-configForm.siteSettings', 'Site settings'],
      ['cfg-configForm.discoverySettings', 'Front page discovery'],
      ['cfg-configForm.userSettings', 'User settings'],
      ['cfg-configForm.revisionSettings', 'Revisions and trash'],
      [expect.any(String), 'Sections']
    ]);
    const name = screen.getByLabelText('Site name (for the front page and feeds)');
    expect(name).toHaveValue('My Site');
    expect(name).toHaveAttribute('id', 'globalConfig_site_name');
    expect(name).toHaveAttribute('type', 'text');
    const area = screen.getByLabelText('Site description (for the front page and feeds)');
    expect(area.tagName).toBe('TEXTAREA');
    expect(area).toHaveValue('About <us>');
    expect(area).toHaveAttribute('rows', '4');
    const days = screen.getByLabelText('Popularity window (days of traffic counted)');
    expect(days).toHaveAttribute('type', 'number');
    expect(days).toHaveClass('form-control', 'integer');
    expect(screen.getByLabelText('Share of front-page slots filled at random (percent)')).toHaveClass('float');
    const box = screen.getByLabelText(/Allow group blogging/);
    expect(box).toBeChecked();
    expect(box).toHaveClass('form-check-input', 'boolean');
    expect(screen.getByLabelText(/Keep usernames private/)).toHaveValue('zzz');
    expect(screen.queryByLabelText(/Site page to serve as the front page/)).not.toBeInTheDocument();
    expect(document.title).toBe('Pressroom: Configuration');
  });

  it('shows the refresh interval in minutes and has a section index and a Save in the rail', async () => {
    await openScreen();
    const minutes = screen.getByLabelText('How often popularity refreshes and random picks reshuffle (minutes)');
    expect(minutes).toHaveValue(10);
    expect(minutes).toHaveAttribute('min', '1');
    expect(minutes).toHaveAttribute('max', '1440');
    expect(minutes).toHaveAttribute('id', 'globalConfig_site_discovery_refreshSeconds');
    const index = document.getElementById('globalConfigSectionIndex') as HTMLElement;
    expect(within(index).getAllByRole('link').map((a) => a.textContent))
      .toEqual(['Site settings', 'Front page discovery', 'User settings', 'Revisions and trash']);
    expect(within(index).getAllByRole('link')[0]).toHaveClass('is-current');
    expect(within(index).getAllByRole('link')[1]).toHaveAttribute('href', '#cfg-configForm.discoverySettings');
    expect(screen.getByRole('button', { name: 'Save' })).toHaveAttribute('id', 'saveButton');
    expect(screen.getByRole('button', { name: 'Save' })).toHaveClass('btn', 'btn-primary', 'w-100');
  });

  it('offers the discovery page, published site pages, and every blog but the site space in the front-door select', async () => {
    await openScreen();
    const select = screen.getByLabelText('What to show at the site front: the discovery page, a site page, or a weblog');
    expect(select).toHaveAttribute('id', 'globalConfig_site_frontpage_weblog_handle');
    const options = [...select.querySelectorAll('option')].map((o) => [o.value, o.textContent]);
    expect(options).toEqual([
      ['', 'Discovery page (popular and new posts from every blog)'],
      ['page:welcome', 'Welcome <home> (/welcome)'],
      ['alpha', 'alpha'],
      ['beta', 'beta']
    ]);
    expect(within(select).getByRole('group', { name: 'Site pages' })).toBeInTheDocument();
    expect(select).toHaveValue('');
    expect(screen.queryByText('No published site pages yet.')).not.toBeInTheDocument();
  });

  it('points to writing a site page when there are none, and when there is no site space', async () => {
    sitePages = 404;
    await openScreen();
    expect(screen.getByText(/No published site pages yet\./)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Write one' })).toHaveAttribute('href',
      'http://pressroom.test/pressroom/pressroom-ui/authoring/pages.rol?weblog=site-space');
  });

  it('selects the stored blog, or the stored page, and keeps an unpublished stored page as its own option', async () => {
    values['site.frontpage.weblog.handle'] = 'beta';
    const { unmount } = await openScreen();
    const label = 'What to show at the site front: the discovery page, a site page, or a weblog';
    expect(screen.getByLabelText(label)).toHaveValue('beta');
    unmount();
    values['site.frontpage.weblog.handle'] = '';
    values['site.frontpage.page'] = 'welcome';
    const second = await openScreen();
    expect(screen.getByLabelText(label)).toHaveValue('page:welcome');
    second.unmount();
    values['site.frontpage.page'] = 'gone';
    await openScreen();
    expect(screen.getByLabelText(label)).toHaveValue('page:gone');
    expect(screen.getByRole('option', { name: 'gone (unpublished: the discovery page shows until it is published again)' })).toBeInTheDocument();
  });

  it('saves only what changed: text, minutes as seconds, a checkbox, with the CSRF header, then confirms', async () => {
    accept();
    const user = userEvent.setup();
    await openScreen();
    await user.clear(screen.getByLabelText('Site name (for the front page and feeds)'));
    await user.type(screen.getByLabelText('Site name (for the front page and feeds)'), 'New name');
    const minutes = screen.getByLabelText('How often popularity refreshes and random picks reshuffle (minutes)');
    await user.clear(minutes);
    await user.type(minutes, '15');
    await user.click(screen.getByLabelText(/Allow group blogging/));
    await save(user);
    expect(await screen.findByText('Changes saved')).toBeInTheDocument();
    expect(patched).toEqual([{ csrf: 'tok', body: { 'site.name': 'New name', 'site.discovery.refreshSeconds': '900',
      'groupblogging.enabled': 'false' } }]);
  });

  it('encodes the front-door choices as the two settings, exactly one set', async () => {
    accept();
    const user = userEvent.setup();
    await openScreen();
    const select = screen.getByLabelText('What to show at the site front: the discovery page, a site page, or a weblog');
    await user.selectOptions(select, 'page:welcome');
    await save(user);
    await screen.findByText('Changes saved');
    await user.selectOptions(select, 'alpha');
    await save(user);
    await waitFor(() => { expect(patched).toHaveLength(2); });
    await user.selectOptions(select, '');
    await save(user);
    await waitFor(() => { expect(patched).toHaveLength(3); });
    expect(patched.map((p) => p.body)).toEqual([
      { 'site.frontpage.weblog.handle': '', 'site.frontpage.page': 'welcome' },
      { 'site.frontpage.weblog.handle': 'alpha', 'site.frontpage.page': '' },
      { 'site.frontpage.weblog.handle': '', 'site.frontpage.page': '' }
    ]);
  });

  it('says the changes are saved without a call when nothing changed', async () => {
    const user = userEvent.setup();
    await openScreen();
    await save(user);
    expect(await screen.findByText('Changes saved')).toBeInTheDocument();
    expect(patched).toEqual([]);
  });

  it('says what the next sweep removes after a revision limit changed, or that it cannot be worked out', async () => {
    accept();
    nextSweep = { total: 12, incomplete: false };
    const user = userEvent.setup();
    await openScreen();
    const retention = screen.getByLabelText('Entry revisions to keep (-1 for all, 0 for none)');
    await user.clear(retention);
    await user.type(retention, '2');
    await save(user);
    expect(await screen.findByText('The next sweep will remove 12 revisions under the limits as they now stand. Nothing has been removed yet.'))
      .toBeInTheDocument();
    nextSweep = { total: 0, incomplete: true };
    await user.clear(retention);
    await user.type(retention, '3');
    await save(user);
    expect(await screen.findByText(/could not be worked out; see the log and the maintenance screen/)).toBeInTheDocument();
  });

  it('is quiet about the sweep when nothing would be removed', async () => {
    accept();
    const user = userEvent.setup();
    await openScreen();
    const retention = screen.getByLabelText('Entry revisions to keep (-1 for all, 0 for none)');
    await user.clear(retention);
    await user.type(retention, '2');
    await save(user);
    await screen.findByText('Changes saved');
    expect(screen.queryByText(/The next sweep will remove/)).not.toBeInTheDocument();
  });

  it('puts a refused value on its field, focused, with the server message, and keeps what was typed', async () => {
    server.use(http.patch(`${API}/admin/config`, () => HttpResponse.json({
      code: 'validation.failed', detail: 'Some settings were refused.',
      errors: [{ field: 'site.discovery.windowDays', message: 'Popularity window must be between 1 and 90.' }]
    }, { status: 400 })));
    const user = userEvent.setup();
    await openScreen();
    const days = screen.getByLabelText('Popularity window (days of traffic counted)');
    await user.clear(days);
    await user.type(days, '500');
    await save(user);
    expect(await screen.findByText('Popularity window must be between 1 and 90.')).toBeInTheDocument();
    await waitFor(() => { expect(days).toHaveAttribute('aria-invalid', 'true'); });
    expect(days).toHaveClass('is-invalid');
    expect(days).toHaveFocus();
    expect(days).toHaveValue(500);
    expect(screen.queryByText('Changes saved')).not.toBeInTheDocument();
  });

  it('maps a refused front-door page onto the select, and a refused minutes onto the minutes field', async () => {
    server.use(http.patch(`${API}/admin/config`, () => HttpResponse.json({
      code: 'validation.failed', detail: 'Refused.',
      errors: [{ field: 'site.frontpage.page', message: 'The front page must be a published site page.' },
        { field: 'site.discovery.refreshSeconds', message: 'Minutes out of range.' }]
    }, { status: 400 })));
    const user = userEvent.setup();
    await openScreen();
    await user.selectOptions(screen.getByLabelText(/What to show at the site front/), 'page:welcome');
    await save(user);
    await screen.findByText('The front page must be a published site page.');
    expect(screen.getByLabelText(/What to show at the site front/)).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText(/How often popularity refreshes/)).toHaveClass('is-invalid');
  });

  it('shows the absolute URL as a problem in place when the API refuses it (session-only write not landed)', async () => {
    server.use(http.patch(`${API}/admin/config`, () => HttpResponse.json(
      { code: 'auth.sessionOnly', detail: 'site.absoluteurl is managed in the admin UI.' }, { status: 403 })));
    const user = userEvent.setup();
    await openScreen();
    const url = screen.getByLabelText('Absolute URL of the site (if required)');
    await user.clear(url);
    await user.type(url, 'https://new.example');
    await save(user);
    expect(await screen.findByText('site.absoluteurl is managed in the admin UI.')).toBeInTheDocument();
    expect(url).toHaveValue('https://new.example');
  });

  it('highlights a number that is not a number (or not whole) and keeps Save off until it is fixed', async () => {
    const user = userEvent.setup();
    await openScreen();
    const days = screen.getByLabelText('Popularity window (days of traffic counted)');
    await user.clear(days);
    expect(days).toHaveClass('field-invalid-highlight');
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    await user.type(days, '2.5');
    expect(days).toHaveClass('field-invalid-highlight');
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    await user.clear(days);
    await user.type(days, '7');
    expect(days).not.toHaveClass('field-invalid-highlight');
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
    const ratio = screen.getByLabelText('Share of front-page slots filled at random (percent)');
    await user.clear(ratio);
    await user.type(ratio, '0.25');
    expect(ratio).not.toHaveClass('field-invalid-highlight');
  });

  it('shows definitions that cannot load as the problem', async () => {
    server.use(http.get(`${API}/admin/config/definitions`, () => HttpResponse.json({ code: 'x', detail: 'No definitions.' }, { status: 400 })));
    renderRoutes(siteAdminRoutes, '/admin/config', { boot: adminBoot });
    expect(await screen.findByText('No definitions.')).toBeInTheDocument();
  });

  it('is not offered to an editor', async () => {
    renderRoutes(siteAdminRoutes, '/admin/config', { boot: editorBoot });
    expect(await screen.findByText('That page does not exist.')).toBeInTheDocument();
  });
});
