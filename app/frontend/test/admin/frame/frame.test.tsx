import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { Footer } from '../../../src/admin/frame/Footer';
import { PageTitle } from '../../../src/admin/frame/PageTitle';
import { appBasename, appRoutes } from '../../../src/admin/router';
import { RAIL_ENTRIES } from '../../../src/admin/routes/railTable';
import { APP_DRAFT_PREFIX } from '../../../src/admin/session/drafts';
import { adminBoot, editorBoot, realMessages, renderInApp, renderRoutes } from '../support';

const API = 'http://pressroom.test/pressroom/api/v1';
let me: object;
let meta: object;
const weblogs: Record<string, object> = {
  alpha: { handle: 'alpha', name: 'Alpha Blog', active: true, timeZone: 'Europe/Berlin', theme: 'journal',
    absoluteUrl: 'https://blog.example.com/alpha' },
  beta: { handle: 'beta', name: 'Beta Blog', active: false, customDomain: 'beta.example.com' }
};

const served: string[] = [];
const server = setupServer(
  http.get(`${API}/me`, () => { served.push('me'); return HttpResponse.json(me); }),
  http.get(`${API}/meta`, () => { served.push('meta'); return HttpResponse.json(meta); }),
  http.get(`${API}/me/weblogs`, () => HttpResponse.json({ weblogs: [] })),
  http.get(`${API}/weblogs/:handle`, ({ params }) => {
    const found = weblogs[String(params['handle'])];
    return found === undefined ? HttpResponse.json({ code: 'weblog.notFound', detail: 'No such weblog.' }, { status: 404 }) : HttpResponse.json(found);
  })
);
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => {
  served.length = 0;
  me = { userName: 'jake', screenName: 'Jake', kind: 'PERSON', globalAdmin: false, tokenScope: {},
    weblogs: [{ handle: 'alpha', name: 'Alpha Blog', permission: 'POST' }] };
  meta = { version: '1.2.3', siteUrl: 'https://blog.example.com', features: { customThemes: true, groupBlogging: true }, limits: {} };
  window.localStorage.clear();
});

const rail = () => document.getElementById('adminRail') as HTMLElement;
const groups = () => [...rail().querySelectorAll('.rail-group')].map((g) => ({
  label: g.querySelector('.rail-group-label')?.textContent,
  links: [...g.querySelectorAll('a')].map((a) => a.textContent)
}));

describe('Frame', () => {
  it('shows the one-shot message a screen handed over in router state {message}, above the screen', async () => {
    const router = createMemoryRouter(appRoutes, { initialEntries: [{ pathname: '/', state: { message: 'Saved <it>.' } }] });
    renderInApp(<RouterProvider router={router} />, { boot: editorBoot });
    const banner = await screen.findByRole('status');
    expect(banner).toHaveTextContent('Saved <it>.');
    expect(banner.id).toBe('messages');
  });

  it('shows the main menu page (no weblog) a rail with just the signed-in user, as the JSP does', async () => {
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    expect(await screen.findByRole('heading', { level: 1, name: 'Your weblogs' })).toHaveClass('pressroom-page-title');
    expect(rail().querySelectorAll('.rail-group')).toHaveLength(0);
    const context = rail().querySelector('.rail-context');
    expect(context?.querySelector('.visually-hidden')).toHaveTextContent('Signed in as:');
    expect(within(context as HTMLElement).getByRole('link', { name: 'jake' }))
      .toHaveAttribute('href', 'http://pressroom.test/pressroom/pressroom-ui/profile.rol');
    expect(document.title).toBe('Pressroom: Your weblogs');
    expect(document.querySelector('.card-body')).not.toHaveClass('pressroom-content-body');
  });

  it('shows an editor with POST on a weblog the JSP Create-and-edit tab only, linked to .rol', async () => {
    renderRoutes(appRoutes, '/weblogs/alpha', { boot: editorBoot });
    await screen.findByText('Create & edit');
    expect(groups()).toEqual([{
      label: 'Create & edit',
      links: ['New entry', 'Entries', 'Trash', 'Inquiries', 'Comments', 'Categories', 'Pages', 'Media files']
    }]);
    expect(screen.getByRole('link', { name: 'Entries' }))
      .toHaveAttribute('href', 'http://pressroom.test/pressroom/pressroom-ui/authoring/entries.rol?weblog=alpha');
    expect(rail().querySelector('.rail-active')).toBeNull();
  });

  it('shows a weblog admin every tab, and drops items whose feature is off', async () => {
    me = { ...me, weblogs: [{ handle: 'alpha', name: 'Alpha Blog', permission: 'ADMIN' }] };
    meta = { ...meta, features: { customThemes: false, groupBlogging: true } };
    renderRoutes(appRoutes, '/weblogs/alpha', { boot: editorBoot });
    await screen.findByText('Design');
    expect(groups().map((g) => g.label)).toEqual(['Create & edit', 'Design', 'Preferences']);
    expect(groups()[0]?.links).toContain('Traffic');
    expect(groups()[1]?.links).toEqual(['Theme']);
    expect(groups()[2]?.links).toEqual(['Settings', 'Members']);
  });

  it('shows a site admin the Server admin group on the admin route, and the weblog menu without membership', async () => {
    me = { ...me, globalAdmin: true, weblogs: [] };
    renderRoutes(appRoutes, '/admin', { boot: adminBoot });
    await screen.findByRole('link', { name: 'Maintenance' });
    expect(groups()).toEqual([{
      label: 'Server admin',
      links: ['Configuration', 'Users', 'Businesses', 'API tokens', 'Traffic', 'Maintenance']
    }]);
    expect(screen.getByRole('link', { name: 'Users' }))
      .toHaveAttribute('href', 'http://pressroom.test/pressroom/pressroom-ui/admin/userAdmin.rol');
  });

  it('shows a site admin who is no member the full weblog menu', async () => {
    me = { ...me, globalAdmin: true, weblogs: [] };
    renderRoutes(appRoutes, '/weblogs/beta', { boot: adminBoot });
    await screen.findByText('Preferences');
    expect(groups().map((g) => g.label)).toEqual(['Create & edit', 'Design', 'Preferences']);
  });

  it('shows an editor nothing on the admin route', async () => {
    renderRoutes(appRoutes, '/admin', { boot: editorBoot });
    await screen.findByRole('heading', { name: 'Server admin' });
    await waitFor(() => { expect([...served].sort()).toEqual(['me', 'meta']); });
    await act(async () => undefined);
    expect(screen.queryByText('Configuration')).not.toBeInTheDocument();
    expect(rail().querySelectorAll('.rail-group')).toHaveLength(0);
  });

  it('puts the weblog in the rail context: name linked to the site, handle and status dot, tz for dates', async () => {
    renderRoutes(appRoutes, '/weblogs/alpha', { boot: editorBoot });
    const name = await screen.findByRole('link', { name: 'Alpha Blog' });
    expect(name).toHaveAttribute('href', 'https://blog.example.com/alpha');
    const handle = rail().querySelector('.rail-context-handle') as HTMLElement;
    expect(handle).toHaveTextContent('alpha');
    expect(handle.querySelector('.rail-status-dot')).not.toBeNull();
    expect(rail().querySelector('.visually-hidden')).toHaveTextContent('Editing weblog:');
    expect(document.title).toBe('Pressroom: Alpha Blog — alpha');
  });

  it('shows an inactive weblog without the dot and links its custom domain', async () => {
    me = { ...me, globalAdmin: true, weblogs: [] };
    renderRoutes(appRoutes, '/weblogs/beta', { boot: adminBoot });
    expect(await screen.findByRole('link', { name: 'Beta Blog' })).toHaveAttribute('href', 'https://beta.example.com/');
    expect(rail().querySelector('.rail-status-dot')).toBeNull();
  });

  it('answers an unknown weblog with the problem detail and falls back to the handle', async () => {
    me = { ...me, globalAdmin: true, weblogs: [] };
    renderRoutes(appRoutes, '/weblogs/ghost', { boot: adminBoot });
    expect(await screen.findByText('No such weblog.')).toBeInTheDocument();
    expect(screen.getAllByText('ghost').length).toBeGreaterThan(0);
  });

  it('marks the entry whose React route is active, and links migrated screens client-side', async () => {
    const traffic = RAIL_ENTRIES.find((e) => e.id === 'traffic') as { path?: string };
    traffic.path = '/weblogs/:handle/traffic';
    try {
      me = { ...me, weblogs: [{ handle: 'alpha', name: 'Alpha Blog', permission: 'ADMIN' }] };
      const routes = [{ ...appRoutes[0], children: [...(appRoutes[0]?.children ?? []),
        { path: 'weblogs/:handle/traffic', element: <PageTitle>Traffic</PageTitle>, handle: { scope: 'weblog' } }] }];
      renderRoutes(routes as never, '/weblogs/alpha/traffic', { boot: editorBoot });
      const link = await screen.findByRole('link', { name: 'Traffic' });
      expect(link).toHaveClass('rail-link', 'rail-active');
      expect(link).toHaveAttribute('aria-current', 'page');
      expect(link).toHaveAttribute('href', '/weblogs/alpha/traffic');
      expect(rail().querySelectorAll('.rail-active')).toHaveLength(1);
    } finally {
      delete traffic.path;
    }
  });

  it('shows a 404 inside the frame', async () => {
    renderRoutes(appRoutes, '/nowhere', { boot: editorBoot });
    expect(await screen.findByText('That page does not exist.')).toBeInTheDocument();
  });

  it('shows a failed meta call as an error banner, but leaves a 401 to the session-expired bar', async () => {
    server.use(http.get(`${API}/meta`, () => HttpResponse.json({ code: 'server.internalError', detail: 'Meta is down.' }, { status: 500 })));
    const first = renderRoutes(appRoutes, '/', { boot: editorBoot });
    expect(await screen.findByText('Meta is down.', undefined, { timeout: 4000 })).toBeInTheDocument();
    first.unmount();

    server.use(http.get(`${API}/me`, () => HttpResponse.json({ code: 'auth.notAuthenticated', detail: 'Sign in.' }, { status: 401 })));
    server.use(http.get(`${API}/meta`, () => HttpResponse.json(meta)));
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    expect(await screen.findByRole('alert')).toHaveTextContent('You have been signed out.');
    expect(screen.queryByText('Sign in.')).not.toBeInTheDocument();
  });

  it('folds the rail behind its toggle below md', async () => {
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    const toggle = await screen.findByRole('button', { name: /Menu/ });
    const column = document.getElementById('adminRailColumn') as HTMLElement;
    expect(column).toHaveClass('collapse', 'd-md-block');
    expect(column).not.toHaveClass('show');
    fireEvent.click(toggle);
    expect(column).toHaveClass('show');
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
  });

  it('renders the footer with the version and the colophon facts the shell carries', async () => {
    const boot = { ...editorBoot, colophon: { copyrightHolder: 'Acme Press', copyrightYear: '2026', privacyUrl: 'https://x.test/privacy',
      shortRevision: 'abc1234', releaseDate: '2026-11-20' } };
    renderRoutes(appRoutes, '/', { boot });
    expect(await screen.findByText('Pressroom 1.2.3 (abc1234)')).toHaveClass('colophon-version');
    expect(document.querySelector('.colophon-copyright')).toHaveTextContent('© 2026 Acme Press. All rights reserved.');
    expect(screen.getByRole('link', { name: 'Privacy policy' })).toHaveAttribute('href', 'https://x.test/privacy');
    expect(document.querySelector('.colophon-released')).toHaveTextContent('released 2026-11-20');
  });

  it('brands the banner and the tab with the site names from the shell', async () => {
    const boot = { ...editorBoot, site: { name: 'Acme Press', shortName: 'Acme', siteSpaceHandle: 'site-space' } };
    renderRoutes(appRoutes, '/weblogs/alpha', { boot });
    await screen.findByRole('link', { name: 'Alpha Blog' });
    expect(document.querySelector('.navbar-brand')).toHaveTextContent('Acme Press');
    expect(document.title).toBe('Acme: Alpha Blog — alpha');
  });

  it('knows the site space from the shell: asks the API for nothing, names it, and offers its three screens', async () => {
    me = { ...me, globalAdmin: true, weblogs: [] };
    meta = { ...meta, features: { customThemes: true, groupBlogging: true } };
    const asked: string[] = [];
    server.use(http.get(`${API}/weblogs/site-space`, () => { asked.push('weblog'); return HttpResponse.json({}, { status: 404 }); }));
    renderRoutes(appRoutes, '/weblogs/site-space', { boot: adminBoot });
    await screen.findByRole('link', { name: 'Pages' });
    expect(groups()).toEqual([
      { label: 'Create & edit', links: ['Pages', 'Media files'] },
      { label: 'Design', links: ['Theme'] }
    ]);
    expect(rail().querySelector('.rail-context-name')).toHaveTextContent('Site');
    expect(rail().querySelector('.rail-context-name a')).toBeNull();
    expect(rail().querySelector('.rail-context-handle')).toHaveTextContent('site-space');
    expect(asked).toEqual([]);
    expect(screen.queryByText('Could not find that weblog.')).not.toBeInTheDocument();
  });
});

describe('Banner', () => {
  it('shows brand, main menu and sign out; sign-out clears autosaved drafts', async () => {
    window.localStorage.setItem(`${APP_DRAFT_PREFIX}jake:e`, '{}');
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    const logout = await screen.findByRole('link', { name: 'Sign out' });
    expect(logout).toHaveAttribute('href', 'http://pressroom.test/pressroom/pressroom-ui/logout.rol');
    expect(screen.getByRole('link', { name: 'Main menu' })).toHaveAttribute('href', '/');
    expect(document.querySelector('.navbar-brand')).toHaveAttribute('href', 'http://pressroom.test/pressroom/');
    logout.addEventListener('click', (event) => { event.preventDefault(); });
    fireEvent.click(logout);
    expect(window.localStorage.length).toBe(0);
  });

  it('toggles the collapsed nav', async () => {
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    const toggler = (await screen.findAllByRole('button', { name: 'Toggle' }))[0] as HTMLElement;
    expect(toggler).toHaveClass('collapsed');
    fireEvent.click(toggler);
    expect(toggler).not.toHaveClass('collapsed');
    expect(document.getElementById('navbar')).toHaveClass('show');
  });

  it('offers no switcher with one weblog', async () => {
    renderRoutes(appRoutes, '/weblogs/alpha', { boot: editorBoot });
    await screen.findByText('Create & edit');
    expect(document.querySelector('.weblog-switcher')).toBeNull();
  });

  describe('weblog switcher (two or more weblogs)', () => {
    beforeEach(() => {
      me = { ...me, weblogs: [{ handle: 'alpha', name: 'Alpha Blog', permission: 'POST' }, { handle: 'beta', name: '', permission: 'ADMIN' }] };
    });

    it('lists them, marks the current one and sends the reader to entries on the target', async () => {
      renderRoutes(appRoutes, '/weblogs/alpha', { boot: editorBoot });
      const button = await screen.findByRole('button', { name: 'Switch weblog' });
      await waitFor(() => { expect(button).toHaveTextContent('Alpha Blog'); });
      const menu = document.querySelector('.dropdown-menu') as HTMLElement;
      expect(menu).not.toHaveClass('show');
      await userEvent.click(button);
      expect(menu).toHaveClass('show');
      expect(button).toHaveAttribute('aria-expanded', 'true');
      const items = within(menu).getAllByRole('link');
      expect(items.map((i) => i.textContent)).toEqual(['Alpha Blog alpha', 'beta beta']);
      expect(items[0]).toHaveClass('active');
      expect(items[1]).not.toHaveClass('active');
      expect(items[1]).toHaveAttribute('href', 'http://pressroom.test/pressroom/pressroom-ui/authoring/entries.rol?weblog=beta');
    });

    it('labels itself with the prompt where no weblog is in play, and closes on Escape and outside click', async () => {
      renderRoutes(appRoutes, '/', { boot: editorBoot });
      const button = await screen.findByRole('button', { name: 'Switch weblog' });
      expect(button).toHaveTextContent('Switch weblog');
      const menu = document.querySelector('.dropdown-menu') as HTMLElement;
      await userEvent.click(button);
      expect(menu).toHaveClass('show');
      fireEvent.keyDown(document, { key: 'a' });
      expect(menu).toHaveClass('show');
      fireEvent.keyDown(document, { key: 'Escape' });
      expect(menu).not.toHaveClass('show');
      await userEvent.click(button);
      fireEvent.mouseDown(menu);
      expect(menu).toHaveClass('show');
      fireEvent.mouseDown(document.body);
      expect(menu).not.toHaveClass('show');
    });

    it('keeps the screen the reader is on when it is a rail screen', async () => {
      const traffic = RAIL_ENTRIES.find((e) => e.id === 'traffic') as { path?: string };
      traffic.path = '/weblogs/:handle/traffic';
      try {
        me = { ...me, weblogs: [{ handle: 'alpha', name: 'Alpha Blog', permission: 'ADMIN' }, { handle: 'beta', name: 'Beta', permission: 'ADMIN' }] };
        const routes = [{ ...appRoutes[0], children: [
          { path: 'weblogs/:handle/traffic', element: <PageTitle>Traffic</PageTitle>, handle: { scope: 'weblog' } }] }];
        renderRoutes(routes as never, '/weblogs/alpha/traffic', { boot: editorBoot });
        await userEvent.click(await screen.findByRole('button', { name: 'Switch weblog' }));
        const beta = await screen.findByRole('link', { name: /Beta/ });
        expect(beta).toHaveAttribute('href', '/weblogs/beta/traffic');
      } finally {
        delete traffic.path;
      }
    });
  });
});

describe('router', () => {
  it('lives under the context path and the app prefix', () => {
    expect(appBasename('/pressroom')).toBe('/pressroom/pressroom-ui/app');
    expect(appBasename('')).toBe('/pressroom-ui/app');
  });

  it('works under that basename', async () => {
    const router = createMemoryRouter(appRoutes, { initialEntries: ['/pressroom/pressroom-ui/app/weblogs/alpha'], basename: appBasename('/pressroom') });
    renderInApp(<RouterProvider router={router} />, { boot: editorBoot });
    expect(await screen.findByRole('heading', { name: 'Alpha Blog' })).toBeInTheDocument();
  });
});

describe('Footer', () => {
  const colophon = (value: object) => renderInApp(<Footer colophon={value} />);

  it('states every Colophon fact it is given, in footer.jsp order', () => {
    const { container } = colophon({ copyrightYear: '2026', copyrightHolder: 'Acme, Inc.', privacyUrl: '/privacy',
      version: '1.2.3', shortRevision: 'abc1234', releaseDate: '2026-11-25' });
    expect(container.querySelector('.colophon-copyright')).toHaveTextContent('© 2026 Acme, Inc.. All rights reserved.');
    expect(screen.getByRole('link', { name: 'Privacy policy' })).toHaveAttribute('href', '/privacy');
    expect(container.querySelector('.colophon-version')).toHaveTextContent('Pressroom 1.2.3 (abc1234)');
    expect(container.querySelector('.colophon-released')).toHaveTextContent('released 2026-11-25');
  });

  it('prefers the copyright text, and leaves out clauses with no fact', () => {
    const { container } = colophon({ copyrightText: 'All mine', copyrightHolder: 'Ignored' });
    expect(container.querySelector('.colophon-copyright')).toHaveTextContent('All mine');
    expect(container.querySelector('.colophon-version')).toBeNull();
    expect(container.querySelector('.colophon-privacy')).toBeNull();
  });

  it('omits an empty copyright and a holderless default', () => {
    expect(colophon({ copyrightText: '' }).container.querySelector('.colophon-copyright')).toBeNull();
    expect(colophon({}).container.querySelector('.colophon-copyright')).toBeNull();
  });

  it('uses the year it is given or none', () => {
    const { container } = colophon({ copyrightHolder: 'Acme' });
    expect(container.querySelector('.colophon-copyright')).toHaveTextContent('© Acme');
  });
});

describe('message coverage', () => {
  it('has every key the frame and its dynamic status pill can use', () => {
    const keys = ['adminRail.toggle', 'switcher.label', 'generic.toggle', 'generic.close', 'generic.cancel', 'mainPage.mainMenu',
      'mainPage.currentWebsite', 'mainPage.loggedInAs', 'navigationBar.logout', 'navigationBar.login', 'session.expired',
      'yourWebsites.title', 'app.notFound', 'selection.count', 'footer.version', 'footer.privacy', 'footer.released',
      'footer.copyright.default', 'footer.version.revision', 'passwordField.show', 'passwordField.hide',
      'passwordField.match', 'passwordField.mismatch', 'weblogEdit.draftRecovery.message', 'weblogEdit.draftRecovery.restore',
      'weblogEdit.draftRecovery.discard', 'weblogEdit.draft', 'weblogEdit.pending', 'weblogEdit.published', 'weblogEdit.scheduled',
      'weblogEdit.trashed', 'weblogEdit.unsaved',
      ...RAIL_ENTRIES.map((e) => e.messageKey)];
    expect(keys.filter((key) => realMessages[key] === undefined)).toEqual([]);
  });
});
