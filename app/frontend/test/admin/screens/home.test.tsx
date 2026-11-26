import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { appRoutes } from '../../../src/admin/router';
import { adminBoot, editorBoot, renderRoutes } from '../support';

const API = 'http://pressroom.test/pressroom/api/v1';
const LEGACY = 'http://pressroom.test/pressroom/pressroom-ui';

let home: object;
let features: object;
const base = { about: 'Notes', theme: 'journal', absoluteUrl: 'https://blog.example.com/x', canModerate: false, entryCount: 0, pendingComments: 0 };
const limited = { ...base, handle: 'lim', name: 'Limited Blog', permission: 'EDIT_DRAFT', actions: ['edit_draft'], entryCount: 2 };
const author = { ...base, handle: 'auth', name: 'Author Blog', permission: 'POST', actions: ['post'], canModerate: true, entryCount: 12, pendingComments: 3 };
const owner = { ...base, handle: 'own', name: 'Owner Blog', permission: 'ADMIN', actions: ['admin', 'post', 'edit_draft'], canModerate: true, entryCount: 5, about: undefined };

const server = setupServer(
  http.get(`${API}/me`, () => HttpResponse.json({ userName: 'jake', screenName: 'Jake', kind: 'PERSON', globalAdmin: false, tokenScope: {}, weblogs: [] })),
  http.get(`${API}/meta`, () => HttpResponse.json({ version: '1', features, limits: {} })),
  http.get(`${API}/me/weblogs`, () => HttpResponse.json(home))
);
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => {
  home = { weblogs: [limited, author, owner] };
  features = { customThemes: true, groupBlogging: true, userWeblogCreation: true, uploads: true, readerSignIn: false, analytics: true };
});

const row = (name: string) => screen.getByRole('heading', { level: 2, name: new RegExp(name) }).closest('.weblog-row') as HTMLElement;

describe('Home (the main menu)', () => {
  it('lists each weblog as a row: name linked to the site, handle, about, role sentence', async () => {
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    expect(await screen.findByText('Access your weblogs here or create a new one.')).toHaveClass('subtitle');
    const heading = screen.getByRole('heading', { level: 2, name: /Author Blog/ });
    expect(heading).toHaveClass('mm_weblog_name', 'section-head');
    expect(within(heading).getByRole('link', { name: 'Author Blog' })).toHaveAttribute('href', 'https://blog.example.com/x');
    expect(heading.querySelector('.data')).toHaveTextContent('auth');
    expect(heading.querySelector('.bi-folder2-open')).not.toBeNull();
    expect(within(row('Author Blog')).getByText('Notes')).toBeInTheDocument();
    expect(within(row('Limited Blog')).getByText('You have Limited permission on this weblog.')).toBeInTheDocument();
    expect(within(row('Author Blog')).getByText('You have Author permission on this weblog.')).toBeInTheDocument();
    expect(within(row('Owner Blog')).getByText('You have Admin, Author, Limited permission on this weblog.')).toBeInTheDocument();
    expect(within(row('Owner Blog')).getByRole('group', { name: 'Owner Blog' })).toHaveClass('weblog-actions');
  });

  it('gives a limited member only New entry, with no entries list, comments, theme or settings', async () => {
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    await screen.findByText('Limited Blog');
    const actions = row('Limited Blog');
    expect(within(actions).getByRole('link', { name: 'New entry' }))
      .toHaveAttribute('href', `${LEGACY}/authoring/entryAdd.rol?weblog=lim`);
    expect(within(actions).getAllByRole('link').map((a) => a.textContent)).toEqual(['Limited Blog', 'New entry']);
  });

  it('gives an author the entries list with its count and, when comments wait, the moderation pill', async () => {
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    await screen.findByText('Author Blog');
    const actions = row('Author Blog');
    const entries = within(actions).getByRole('link', { name: /^Entries/ });
    expect(entries).toHaveAttribute('href', `${LEGACY}/authoring/entries.rol?weblog=auth`);
    expect(entries.querySelector('.data')).toHaveTextContent('12');
    const pill = within(actions).getByRole('link', { name: '3 awaiting approval' });
    expect(pill).toHaveClass('status-pill', 'status-pending');
    expect(pill).toHaveAttribute('href', `${LEGACY}/authoring/comments.rol?weblog=auth`);
    expect(within(actions).queryByRole('link', { name: 'Settings' })).toBeNull();
    expect(within(actions).queryByRole('link', { name: 'Theme' })).toBeNull();
  });

  it('shows no pill for nothing waiting, nor for a member who may not moderate', async () => {
    home = { weblogs: [{ ...author, pendingComments: 0 }, { ...limited, pendingComments: 4 }] };
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    await screen.findByText('Author Blog');
    expect(document.querySelector('.status-pill')).toBeNull();
  });

  it('gives an owner Theme and Settings, and a custom theme goes to its templates only while custom themes are allowed', async () => {
    home = { weblogs: [owner, { ...owner, handle: 'cus', name: 'Custom Blog', theme: 'custom' }] };
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    await screen.findByText('Custom Blog');
    expect(within(row('Owner Blog')).getByRole('link', { name: 'Theme' })).toHaveAttribute('href', `${LEGACY}/authoring/themeEdit.rol?weblog=own`);
    expect(within(row('Owner Blog')).getByRole('link', { name: 'Settings' })).toHaveAttribute('href', `${LEGACY}/authoring/weblogConfig.rol?weblog=own`);
    expect(within(row('Custom Blog')).getByRole('link', { name: 'Theme' })).toHaveAttribute('href', `${LEGACY}/authoring/templates.rol?weblog=cus`);
  });

  it('leaves out the Theme link of a custom-theme weblog when custom themes are switched off', async () => {
    features = { ...features, customThemes: false };
    home = { weblogs: [{ ...owner, theme: 'custom' }] };
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    await screen.findByText('Owner Blog');
    await waitFor(() => { expect(within(row('Owner Blog')).queryByRole('link', { name: 'Theme' })).toBeNull(); });
    expect(within(row('Owner Blog')).getByRole('link', { name: 'Settings' })).toBeInTheDocument();
  });

  it('shows a weblog with no address as plain text', async () => {
    home = { weblogs: [{ ...author, absoluteUrl: undefined }] };
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    const heading = await screen.findByRole('heading', { level: 2, name: /Author Blog/ });
    expect(within(heading).queryByRole('link')).toBeNull();
  });

  it('invites a user with no weblog to create one, in place of the list', async () => {
    home = { weblogs: [] };
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    await screen.findByText('No weblog yet.');
    expect(document.querySelector('.empty-state-title')).toHaveTextContent('No weblog yet.');
    expect(document.querySelector('.empty-state-body')).toHaveTextContent('A weblog is where your writing lives.');
    expect(document.getElementById('createWeblogLink')).toHaveAttribute('href', `${LEGACY}/createWeblog.rol`);
    expect(document.getElementById('createWeblogLink')).toHaveClass('btn', 'btn-primary');
    expect(document.querySelector('.subtitle')).toBeNull();
  });

  it('shows a site admin the Site section with its three screens, on the site space', async () => {
    home = { weblogs: [owner], siteSpace: 'site-space' };
    renderRoutes(appRoutes, '/', { boot: adminBoot });
    const site = (await screen.findByRole('heading', { level: 2, name: 'Site' })).closest('.weblog-row') as HTMLElement;
    expect(site).toHaveClass('site-row');
    expect(within(site).getByRole('link', { name: 'Pages' })).toHaveAttribute('href', `${LEGACY}/authoring/pages.rol?weblog=site-space`);
    expect(within(site).getByRole('link', { name: 'Media' })).toHaveAttribute('href', `${LEGACY}/authoring/mediaFileView.rol?weblog=site-space`);
    expect(within(site).getByRole('link', { name: 'Theme' })).toHaveAttribute('href', `${LEGACY}/authoring/themeEdit.rol?weblog=site-space`);
  });

  it('shows no Site section without a site space', async () => {
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    await screen.findByText('Author Blog');
    expect(document.querySelector('.site-row')).toBeNull();
  });

  it('answers a failed read in place', async () => {
    server.use(http.get(`${API}/me/weblogs`, () => HttpResponse.json({ code: 'server.internalError', detail: 'Menu is down.' }, { status: 403 })));
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    expect(await screen.findByText('Menu is down.')).toBeInTheDocument();
    expect(document.querySelector('.empty-state')).toBeNull();
  });
});

describe('Home sidebar', () => {
  const sidebar = () => document.getElementById('adminSidebar') as HTMLElement;

  it('offers a plain editor their profile only, linked to the legacy page', async () => {
    features = { ...features, groupBlogging: false };
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    const profile = await screen.findByRole('link', { name: 'Your profile' });
    expect(profile).toHaveAttribute('href', `${LEGACY}/profile.rol`);
    await screen.findByText('Author Blog');
    expect(sidebar().querySelectorAll('.sidebar-group')).toHaveLength(1);
    expect(sidebar().querySelector('.bi-person')).not.toBeNull();
  });

  it('offers weblog creation when users may create them and group blogging is on, even with weblogs', async () => {
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    const create = await screen.findByRole('link', { name: 'Create a weblog' });
    expect(create).toHaveAttribute('href', `${LEGACY}/createWeblog.rol`);
  });

  it('offers creation to a user with no weblog without group blogging, but not to one with a weblog', async () => {
    features = { ...features, groupBlogging: false };
    home = { weblogs: [] };
    const first = renderRoutes(appRoutes, '/', { boot: editorBoot });
    await waitFor(() => { expect(within(sidebar()).getByRole('link', { name: 'Create a weblog' })).toBeInTheDocument(); });
    first.unmount();
    home = { weblogs: [owner] };
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    await screen.findByText('Owner Blog');
    expect(within(sidebar()).queryByRole('link', { name: 'Create a weblog' })).toBeNull();
  });

  it('does not offer creation when users may not create weblogs', async () => {
    features = { ...features, userWeblogCreation: false, groupBlogging: true };
    home = { weblogs: [] };
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    await screen.findByText('No weblog yet.');
    expect(within(sidebar()).queryByRole('link', { name: 'Create a weblog' })).toBeNull();
  });

  it('gives a site admin the server administration group, and nobody else', async () => {
    renderRoutes(appRoutes, '/', { boot: adminBoot });
    expect(await screen.findByRole('link', { name: 'Server administration' }))
      .toHaveAttribute('href', `${LEGACY}/admin/globalConfig.rol`);
    expect(sidebar().querySelector('.bi-wrench')).not.toBeNull();
  });

  it('gives an editor no server administration', async () => {
    renderRoutes(appRoutes, '/', { boot: editorBoot });
    await screen.findByText('Author Blog');
    expect(screen.queryByRole('link', { name: 'Server administration' })).toBeNull();
  });
});
