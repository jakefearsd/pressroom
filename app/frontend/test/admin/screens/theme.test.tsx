import '@testing-library/jest-dom/vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { appRoutes } from '../../../src/admin/router';
import { editorBoot, renderRoutes } from '../support';
import { API, alpha, me, meta, problem, record, themeChoices, type Seen } from './weblogFixtures';

let seen: Seen[];
let who: object;
let themes: Record<string, unknown>;
let refuse: ReturnType<typeof problem> | null;
const server = setupServer(
  http.get(`${API}/me`, () => HttpResponse.json(who)),
  http.get(`${API}/meta`, () => HttpResponse.json(meta())),
  http.get(`${API}/weblogs/alpha`, () => HttpResponse.json(alpha)),
  http.get(`${API}/weblogs/alpha/themes`, () => HttpResponse.json(themes)),
  http.patch(`${API}/weblogs/alpha`, async ({ request }) => {
    await record(seen, request);
    if (refuse !== null) {
      return refuse;
    }
    const body = seen[seen.length - 1]?.body as { theme: string };
    themes = { ...themes, current: body.theme, custom: false };
    return HttpResponse.json({ ...alpha, theme: body.theme });
  }),
  http.post(`${API}/weblogs/alpha/themes/custom`, async ({ request }) => {
    await record(seen, request);
    if (refuse !== null) {
      return refuse;
    }
    themes = { ...themes, custom: true, current: undefined, firstCustomization: false };
    return HttpResponse.json(themes);
  })
);
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => {
  seen = [];
  who = me();
  refuse = null;
  themes = { current: 'journal', custom: false, customAllowed: true, sharedStylesheetCustomised: false,
    firstCustomization: true, themes: themeChoices };
});

const open = async () => {
  renderRoutes(appRoutes, '/weblogs/alpha/theme', { boot: editorBoot });
  await screen.findByRole('heading', { level: 1, name: 'Theme' });
  await screen.findByText('Choose a shared theme').catch(() => undefined);
};

describe('ThemeEdit', () => {
  it('shows the current shared theme, the cards with previews, and no action until something is proposed', async () => {
    await open();
    expect(await screen.findByText('Journal', { selector: 'b' })).toBeInTheDocument();
    expect(document.querySelector('.subtitle span')).toHaveTextContent('alpha');
    expect(screen.getByRole('radio', { name: /Journal/ })).toBeChecked();
    expect(document.querySelector('.theme-card-thumb')).toHaveAttribute('src', 'http://pressroom.test/pressroom/themes/journal/preview.png');
    expect(screen.getByRole('link', { name: 'View your weblog' })).toHaveAttribute('href', 'https://blog.alpha.example/');
    expect(screen.queryByRole('button', { name: 'Use this theme' })).toBeNull();
    expect(document.getElementById('sharedChooser')).toHaveClass('chooser-selected');
  });

  it('notes a customised copy of the shared stylesheet', async () => {
    themes = { ...themes, sharedStylesheetCustomised: true };
    await open();
    expect(await screen.findByText(/Its stylesheet is your own edited copy\./)).toBeInTheDocument();
  });

  it('switches to another shared theme: warning, preview, then PATCH, then says so and settles', async () => {
    const user = userEvent.setup();
    const open$ = vi.spyOn(window, 'open').mockImplementation(() => null);
    await open();
    await user.click(await screen.findByRole('radio', { name: /Gaia/ }));
    expect(screen.getByText('You are proposing to switch to a new shared theme.')).toBeInTheDocument();
    expect(document.getElementById('sharedChangeToShared')).not.toBeNull();
    await user.click(screen.getByRole('button', { name: 'Preview' }));
    expect(open$).toHaveBeenCalledWith('http://pressroom.test/pressroom/pressroom-ui/authoring/preview/alpha?theme=gaia');
    await user.click(screen.getByRole('button', { name: 'Use this theme' }));
    expect(await screen.findByText('Theme set to Gaia')).toBeInTheDocument();
    expect(seen[0]).toEqual({ method: 'PATCH', path: '/weblogs/alpha', csrf: 'tok', body: { theme: 'gaia' } });
    await waitFor(() => { expect(screen.queryByRole('button', { name: 'Use this theme' })).toBeNull(); });
    expect(screen.getByText('Gaia', { selector: 'b' })).toBeInTheDocument();
    open$.mockRestore();
  });

  it('cancel puts the proposal back', async () => {
    const user = userEvent.setup();
    await open();
    await user.click(await screen.findByRole('radio', { name: /Gaia/ }));
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByRole('radio', { name: /Journal/ })).toBeChecked();
    expect(screen.queryByRole('button', { name: 'Use this theme' })).toBeNull();
  });

  it('proposing the theme it already has is no change', async () => {
    const user = userEvent.setup();
    await open();
    await user.click(await screen.findByRole('radio', { name: /Gaia/ }));
    await user.click(screen.getByRole('radio', { name: /Journal/ }));
    expect(screen.queryByRole('button', { name: 'Use this theme' })).toBeNull();
  });

  it('shows a refusal in place and keeps the proposal', async () => {
    const user = userEvent.setup();
    refuse = problem('theme.notOffered', 'That theme is not offered here.', undefined, 400);
    await open();
    await user.click(await screen.findByRole('radio', { name: /Gaia/ }));
    await user.click(screen.getByRole('button', { name: 'Use this theme' }));
    expect(await screen.findByText('That theme is not offered here.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Use this theme' })).toBeInTheDocument();
    expect(screen.queryByText('Theme set to Gaia')).toBeNull();
  });

  it('converts to a custom theme on first customisation: import is required, then both messages', async () => {
    const user = userEvent.setup();
    await open();
    await user.click(await screen.findByRole('radio', { name: /Custom theme/ }));
    expect(screen.getByText(/You are proposing to switch from a shared theme to a custom them/)).toBeInTheDocument();
    expect(screen.getByText(/Since this is the first time using a custom theme/)).toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Preview' })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Use this theme' }));
    expect(await screen.findByText('Shared theme Journal imported into custom templates')).toBeInTheDocument();
    expect(screen.getByText('This weblog is now using a custom theme.')).toBeInTheDocument();
    expect(seen[0]).toMatchObject({ method: 'POST', path: '/weblogs/alpha/themes/custom', body: { importTheme: 'journal' } });
    expect(await screen.findByText('This weblog is using a custom theme.')).toBeInTheDocument();
  });

  it('with templates already there, importing is a choice (and not importing is the default)', async () => {
    const user = userEvent.setup();
    themes = { ...themes, firstCustomization: false };
    await open();
    await user.click(await screen.findByRole('radio', { name: /Custom theme/ }));
    expect(screen.getByText(/This weblog already has a custom theme, so you need to choose/, { selector: 'b' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Use this theme' }));
    expect(await screen.findByText('This weblog is now using a custom theme.')).toBeInTheDocument();
    expect(seen[0]?.body).toEqual({});
  });

  it('with templates already there, ticking import imports the checked theme', async () => {
    const user = userEvent.setup();
    themes = { ...themes, firstCustomization: false };
    await open();
    await user.click(await screen.findByRole('radio', { name: /Gaia/ }));
    await user.click(screen.getByRole('radio', { name: /Custom theme/ }));
    await user.click(screen.getByRole('checkbox'));
    await user.click(screen.getByRole('button', { name: 'Use this theme' }));
    expect(await screen.findByText('Shared theme Gaia imported into custom templates')).toBeInTheDocument();
    expect(seen[0]?.body).toEqual({ importTheme: 'gaia' });
  });

  it('shows a custom-theme refusal in place', async () => {
    const user = userEvent.setup();
    refuse = problem('theme.customNotAllowed', 'Custom themes are not enabled on this site.', undefined, 403);
    await open();
    await user.click(await screen.findByRole('radio', { name: /Custom theme/ }));
    await user.click(screen.getByRole('button', { name: 'Use this theme' }));
    expect(await screen.findByText('Custom themes are not enabled on this site.')).toBeInTheDocument();
  });

  it('on a custom theme: says so, and switching back to shared proposes the first card and PATCHes it', async () => {
    const user = userEvent.setup();
    themes = { ...themes, custom: true, current: undefined };
    await open();
    expect(await screen.findByText('This weblog is using a custom theme.')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /Custom theme/ })).toBeChecked();
    await user.click(screen.getByRole('radio', { name: /Shared theme/ }));
    expect(screen.getByText('You are proposing to switch from a custom theme to a new shared theme.')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /Journal/ })).toBeChecked();
    await user.click(screen.getByRole('button', { name: 'Use this theme' }));
    expect(await screen.findByText('Theme set to Journal')).toBeInTheDocument();
    expect(seen[0]?.body).toEqual({ theme: 'journal' });
  });

  it('offers no choice of type when custom themes are not allowed, only the shared cards', async () => {
    themes = { ...themes, customAllowed: false };
    await open();
    expect(await screen.findByRole('radio', { name: /Gaia/ })).toBeInTheDocument();
    expect(screen.queryByRole('radio', { name: /Custom theme/ })).toBeNull();
    expect(screen.queryByRole('radio', { name: /Shared theme/ })).toBeNull();
  });

  it('falls back to the first card when the current theme is no longer offered', async () => {
    themes = { ...themes, current: 'retired' };
    await open();
    expect(await screen.findByRole('radio', { name: /Journal/ })).toBeChecked();
    expect(screen.getByText('retired', { selector: 'b' })).toBeInTheDocument();
  });

  it('refuses a member who is not a weblog admin', async () => {
    who = me('POST');
    renderRoutes(appRoutes, '/weblogs/alpha/theme', { boot: editorBoot });
    expect(await screen.findByText('Access denied.')).toBeInTheDocument();
  });

  it('reports a theme list that cannot be read', async () => {
    server.use(http.get(`${API}/weblogs/alpha/themes`, () => problem('weblog.notFound', 'No such weblog.', undefined, 404)));
    renderRoutes(appRoutes, '/weblogs/alpha/theme', { boot: editorBoot });
    expect((await screen.findAllByText('No such weblog.')).length).toBeGreaterThan(0);
  });
});
