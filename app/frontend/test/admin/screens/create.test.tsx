import '@testing-library/jest-dom/vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { appRoutes } from '../../../src/admin/router';
import { editorBoot, renderRoutes } from '../support';
import { API, me, meta, options, problem, record, type Seen } from './weblogFixtures';

let seen: Seen[];
let features: Record<string, boolean>;
let who: object;
let refuse: ReturnType<typeof problem> | null;
let profile: Record<string, string>;
const server = setupServer(
  http.get(`${API}/me`, () => HttpResponse.json(who)),
  http.get(`${API}/meta`, () => HttpResponse.json(meta(features))),
  http.get(`${API}/meta/weblog-options`, () => HttpResponse.json(options)),
  http.get(`${API}/me/profile`, () => HttpResponse.json(profile)),
  http.get(`${API}/weblogs/:handle`, () => HttpResponse.json({ handle: 'x', name: 'X' })),
  http.post(`${API}/weblogs`, async ({ request }) => {
    await record(seen, request);
    return refuse ?? HttpResponse.json({ handle: 'newone', name: 'New' }, { status: 201 });
  })
);
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => {
  seen = [];
  features = {};
  who = { ...me(), weblogs: [] };
  refuse = null;
  profile = { userName: 'jake', kind: 'PERSON', emailAddress: 'jake@example.com', locale: 'en_US', timeZone: 'Europe/Berlin' };
});

const open = async () => {
  const view = renderRoutes(appRoutes, '/weblogs/new', { boot: editorBoot });
  await screen.findByLabelText('Name');
  await screen.findByRole('option', { name: 'Journal' });
  return view;
};

describe('CreateWeblog', () => {
  it('keeps the JSP form control names (the ITs and browser autofill select on them)', async () => {
    await open();
    for (const field of ['name', 'handle', 'emailAddress', 'locale', 'timeZone', 'theme']) {
      expect(document.getElementById(field)).toHaveAttribute('name', field);
    }
  });

  it('shows the JSP form: the handle preview, the theme with description and thumbnail, journal preselected', async () => {
    const user = userEvent.setup();
    await open();
    expect(screen.getByRole('heading', { level: 1, name: 'Create a weblog' })).toBeInTheDocument();
    expect(screen.getByLabelText('Theme')).toHaveValue('journal');
    expect(document.getElementById('themedescription')).toHaveTextContent('Quiet and neutral.');
    expect(document.getElementById('themeThumbnail')).toHaveAttribute('src', 'http://pressroom.test/pressroom/themes/journal/preview.png');
    expect(screen.getByLabelText('Locale')).toHaveValue('en_US');
    await user.selectOptions(screen.getByLabelText('Theme'), 'gaia');
    expect(document.getElementById('themedescription')).toHaveTextContent('Leafy.');
    expect(document.getElementById('themeThumbnail')).toBeNull();
    await user.type(screen.getByLabelText('Handle'), 'my-handle');
    expect(document.getElementById('handlePreview')).toHaveTextContent('my-handle');
    expect(screen.getByText(/URL: https:\/\/blog\.example\.com\//)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Cancel' })).toHaveAttribute('href', '/');
  });

  it('creates the weblog, sends only what was filled in, and lands on the app home with the message', async () => {
    const user = userEvent.setup();
    const { router } = await open();
    await user.type(screen.getByLabelText('Name'), ' New one ');
    await user.type(screen.getByLabelText('Handle'), 'newone');
    await user.clear(screen.getByLabelText('Email address'));
    await user.type(screen.getByLabelText('Email address'), 'me@example.com');
    await user.selectOptions(screen.getByLabelText('Locale'), 'de_DE');
    await user.selectOptions(screen.getByLabelText('Time zone'), 'UTC');
    await user.click(screen.getByRole('button', { name: 'Create a weblog' }));
    await waitFor(() => { expect(router.state.location.pathname).toBe('/'); });
    expect(seen[0]).toEqual({ method: 'POST', path: '/weblogs', csrf: 'tok', body: {
      handle: 'newone', name: 'New one', theme: 'journal', emailAddress: 'me@example.com', locale: 'de_DE', timeZone: 'UTC'
    } });
    expect(await screen.findByText('Weblog "newone" created.')).toBeInTheDocument();
  });

  it('prefills email, locale and time zone from the signed-in account, as the JSP did, and posts them', async () => {
    profile = { ...profile, emailAddress: 'jake@example.com', locale: 'de_DE', timeZone: 'UTC' };
    const user = userEvent.setup();
    const { router } = await open();
    await waitFor(() => { expect(screen.getByLabelText('Email address')).toHaveValue('jake@example.com'); });
    expect(screen.getByLabelText('Locale')).toHaveValue('de_DE');
    expect(screen.getByLabelText('Time zone')).toHaveValue('UTC');
    await user.type(screen.getByLabelText('Name'), 'N');
    await user.type(screen.getByLabelText('Handle'), 'h');
    await user.click(screen.getByRole('button', { name: 'Create a weblog' }));
    await waitFor(() => { expect(router.state.location.pathname).toBe('/'); });
    expect(seen[0]?.body).toEqual({ handle: 'h', name: 'N', theme: 'journal', emailAddress: 'jake@example.com',
      locale: 'de_DE', timeZone: 'UTC' });
  });

  it('matches a bare account locale (en) to the first offered locale, and ignores a zone the form does not offer', async () => {
    profile = { ...profile, locale: 'de', timeZone: 'Mars/Olympus' };
    await open();
    await waitFor(() => { expect(screen.getByLabelText('Locale')).toHaveValue('de_DE'); });
    expect(screen.getByLabelText('Time zone')).toHaveValue('');
  });

  it('leaves email and time zone to the API defaults when the account has none', async () => {
    profile = { userName: 'jake', kind: 'PERSON' };
    const user = userEvent.setup();
    const { router } = await open();
    await user.type(screen.getByLabelText('Name'), 'N');
    await user.type(screen.getByLabelText('Handle'), 'h');
    await user.click(screen.getByRole('button', { name: 'Create a weblog' }));
    await waitFor(() => { expect(router.state.location.pathname).toBe('/'); });
    expect(seen[0]?.body).toEqual({ handle: 'h', name: 'N', theme: 'journal' });
  });

  it('asks for the name and the handle before sending anything, on the fields', async () => {
    const user = userEvent.setup();
    await open();
    await user.click(screen.getByRole('button', { name: 'Create a weblog' }));
    expect(screen.getByLabelText('Name')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Name')).toHaveFocus();
    expect(screen.getByText('Name is a required field', { selector: '.invalid-feedback' })).toBeInTheDocument();
    expect(screen.getByText('Handle is a required field', { selector: '.invalid-feedback' })).toBeInTheDocument();
    expect(seen).toHaveLength(0);
  });

  it('shows the server refusal in place: a handle in use is marked on the handle field', async () => {
    const user = userEvent.setup();
    refuse = problem('handle.inUse', 'A weblog with that handle already exists.',
      [{ field: 'handle', message: 'A weblog with that handle already exists.' }], 409);
    const { router } = await open();
    await user.type(screen.getByLabelText('Name'), 'N');
    await user.type(screen.getByLabelText('Handle'), 'taken');
    await user.click(screen.getByRole('button', { name: 'Create a weblog' }));
    const handle = screen.getByLabelText('Handle');
    await waitFor(() => { expect(handle).toHaveAttribute('aria-invalid', 'true'); });
    expect(handle).toHaveFocus();
    expect(screen.getByText('A weblog with that handle already exists.', { selector: '.invalid-feedback' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/weblogs/new');
    expect(screen.getByLabelText('Name')).toHaveValue('N');
  });

  it('shows a refusal that names no field in the banner (creation switched off at the server)', async () => {
    const user = userEvent.setup();
    refuse = problem('weblog.creationDisabled', 'Weblog creation is turned off on this site.', undefined, 403);
    await open();
    await user.type(screen.getByLabelText('Name'), 'N');
    await user.type(screen.getByLabelText('Handle'), 'h');
    await user.click(screen.getByRole('button', { name: 'Create a weblog' }));
    expect(await screen.findByText('Weblog creation is turned off on this site.')).toBeInTheDocument();
  });

  it('refuses up front, as the JSP refusal page did, when creation is off', async () => {
    features = { userWeblogCreation: false };
    renderRoutes(appRoutes, '/weblogs/new', { boot: editorBoot });
    expect(await screen.findByText('Sorry, the site administrator has turned off weblog creation.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Name')).toBeNull();
  });

  it('refuses up front when the site allows one weblog per user and the user has one', async () => {
    features = { groupBlogging: false };
    who = me();
    renderRoutes(appRoutes, '/weblogs/new', { boot: editorBoot });
    expect(await screen.findByText('Sorry, only one weblog per user is allowed.')).toBeInTheDocument();
  });

  it('lets a user with no weblog create one when group blogging is off', async () => {
    features = { groupBlogging: false };
    await open();
    expect(screen.getByLabelText('Name')).toBeInTheDocument();
  });
});
