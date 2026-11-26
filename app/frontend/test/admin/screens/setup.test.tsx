import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { siteAdminRoutes } from '../../../src/admin/routes/siteRoutes';
import { adminBoot, editorBoot, renderRoutes } from '../support';

const API = 'http://pressroom.test/pressroom/api/v1';
let users: object[];
let blogs: object[];
let config: Record<string, string>;
let patched: { body: unknown; csrf: string | null }[];

/** The API's paging envelope: 200 at most per page, `hasMore` while rows remain. */
const page = (rows: object[], request: Request) => {
  const offset = Number(new URL(request.url).searchParams.get('offset') ?? '0');
  return HttpResponse.json({ items: rows.slice(offset, offset + 200), offset, limit: 200, hasMore: rows.length > offset + 200 });
};

const server = setupServer(
  http.get(`${API}/admin/users`, ({ request }) => page(users, request)),
  http.get(`${API}/weblogs`, ({ request }) => page(blogs, request)),
  http.get(`${API}/admin/config`, () => HttpResponse.json(Object.entries(config).map(([name, value]) => ({ name, value, type: 'string' }))))
);
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => {
  users = [{ userName: 'jake' }];
  blogs = [{ handle: 'alpha', name: 'Alpha <Blog>' }, { handle: 'beta', name: 'Beta' }];
  config = { 'site.frontpage.weblog.handle': '', 'site.frontpage.weblog.aggregated': 'false' };
  patched = [];
});

const openScreen = async () => {
  const view = renderRoutes(siteAdminRoutes, '/admin/setup', { boot: adminBoot });
  await screen.findByText('Create a weblog', { exact: false });
  return view;
};
const card = (title: string) => screen.getByRole('heading', { level: 2, name: new RegExp(`^${title}`) }).closest('.card') as HTMLElement;

describe('Setup', () => {
  it('walks the three steps with what the site already has', async () => {
    await openScreen();
    expect(screen.getByRole('heading', { level: 1, name: 'Welcome to Pressroom!' })).toBeInTheDocument();
    expect(screen.getByText('Follow these steps to finalize your Pressroom installation:')).toHaveClass('pagetip');
    await screen.findByText(/Done: this site has 2 weblogs\./);
    expect(screen.getByRole('heading', { level: 2, name: 'Create a user - Done: this site has 1 user.' })).toHaveClass('setup-section-title');
    expect(screen.getByRole('heading', { level: 2, name: 'Create a weblog - Done: this site has 2 weblogs.' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Choose a front page weblog' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'new user page' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'new weblog page' })).not.toBeInTheDocument();
    expect(document.title).toBe('Pressroom: Welcome to Pressroom!');
  });

  it('counts every account and weblog, not the first page of each', async () => {
    users = Array.from({ length: 250 }, (_v, i) => ({ userName: `u${String(i)}` }));
    blogs = Array.from({ length: 205 }, (_v, i) => ({ handle: `b${String(i)}`, name: `Blog ${String(i)}` }));
    await openScreen();
    expect(await screen.findByRole('heading', { level: 2, name: 'Create a user - Done: this site has 250 users.' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Create a weblog - Done: this site has 205 weblogs.' })).toBeInTheDocument();
  });

  it('formats the help the way the bundle wrote it', async () => {
    await openScreen();
    const help = await screen.findByText(/Choose a weblog to serve as the site's front page/);
    expect(help.querySelector('b')).toHaveTextContent('Server admin → Configuration');
    const user = within(card('Create a user')).getByText(/Before you can start to use Pressroom/);
    expect(user.querySelector('i')).toHaveTextContent('Global Admin');
  });

  it('invites creating the first user, then the first weblog, as links to those pages', async () => {
    users = [];
    blogs = [];
    await openScreen();
    const user = await screen.findByRole('link', { name: 'new user page' });
    expect(user).toHaveAttribute('id', 'a_createUser');
    expect(user).toHaveAttribute('href', '/admin/users/new');
    expect(screen.queryByRole('link', { name: 'new weblog page' })).not.toBeInTheDocument();
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Create a user' })).toBeInTheDocument();
  });

  it('offers creating the first weblog once there is a user', async () => {
    blogs = [];
    await openScreen();
    const link = await screen.findByRole('link', { name: 'new weblog page' });
    expect(link).toHaveAttribute('id', 'a_createBlog');
    expect(link).toHaveAttribute('href', '/weblogs/new');
    expect(screen.queryByLabelText('Weblog to serve as the site front page')).not.toBeInTheDocument();
  });

  it('shows the front page form: a disabled placeholder while nothing is chosen, the blogs by name, the checkbox', async () => {
    await openScreen();
    const select = await screen.findByLabelText('Weblog to serve as the site front page');
    expect(select).toHaveAttribute('id', 'frontpageBlog');
    expect(within(select).getByRole('option', { name: 'Choose a weblog' })).toBeDisabled();
    expect(select).toHaveValue('');
    expect(within(select).getByRole('option', { name: 'Alpha <Blog>' })).toHaveValue('alpha');
    expect(screen.getByLabelText('Show entries from every weblog on the front page')).not.toBeChecked();
  });

  it('shows what is stored: the chosen blog and aggregation', async () => {
    config = { 'site.frontpage.weblog.handle': 'beta', 'site.frontpage.weblog.aggregated': 'true' };
    await openScreen();
    await waitFor(() => { expect(screen.getByLabelText('Weblog to serve as the site front page')).toHaveValue('beta'); });
    expect(screen.getByLabelText('Show entries from every weblog on the front page')).toBeChecked();
  });

  it('saves the two front page settings and says so', async () => {
    server.use(http.patch(`${API}/admin/config`, async ({ request }) => {
      patched.push({ body: await request.json(), csrf: request.headers.get('X-CSRF-TOKEN') });
      return HttpResponse.json([]);
    }));
    const user = userEvent.setup();
    await openScreen();
    await user.selectOptions(await screen.findByLabelText('Weblog to serve as the site front page'), 'alpha');
    await user.click(screen.getByLabelText('Show entries from every weblog on the front page'));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Front page settings saved.')).toBeInTheDocument();
    expect(patched).toEqual([{ csrf: 'tok', body: { 'site.frontpage.weblog.handle': 'alpha', 'site.frontpage.weblog.aggregated': 'true' } }]);
  });

  it('sends aggregated false when unticked', async () => {
    config = { 'site.frontpage.weblog.handle': 'beta', 'site.frontpage.weblog.aggregated': 'true' };
    server.use(http.patch(`${API}/admin/config`, async ({ request }) => {
      patched.push({ body: await request.json(), csrf: null });
      return HttpResponse.json([]);
    }));
    const user = userEvent.setup();
    await openScreen();
    await waitFor(() => { expect(screen.getByLabelText('Show entries from every weblog on the front page')).toBeChecked(); });
    await user.click(screen.getByLabelText('Show entries from every weblog on the front page'));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('Front page settings saved.');
    expect(patched[0]?.body).toEqual({ 'site.frontpage.weblog.handle': 'beta', 'site.frontpage.weblog.aggregated': 'false' });
  });

  it('does not save without a chosen blog', async () => {
    const user = userEvent.setup();
    await openScreen();
    await user.click(await screen.findByRole('button', { name: 'Save' }));
    expect(patched).toEqual([]);
    expect(screen.queryByText('Front page settings saved.')).not.toBeInTheDocument();
  });

  it('shows a refused save as the problem, in place', async () => {
    server.use(http.patch(`${API}/admin/config`, () => HttpResponse.json({
      code: 'validation.failed', detail: 'The front page can be a site page or a blog, not both.',
      errors: [{ field: 'site.frontpage.weblog.handle', message: 'The front page can be a site page or a blog, not both.' }]
    }, { status: 400 })));
    const user = userEvent.setup();
    await openScreen();
    const select = await screen.findByLabelText('Weblog to serve as the site front page');
    await user.selectOptions(select, 'alpha');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect((await screen.findAllByText('The front page can be a site page or a blog, not both.')).length).toBeGreaterThan(0);
    await waitFor(() => { expect(select).toHaveAttribute('aria-invalid', 'true'); });
    expect(screen.queryByText('Front page settings saved.')).not.toBeInTheDocument();
  });

  it('says so when the weblogs cannot be listed', async () => {
    server.use(http.get(`${API}/weblogs`, () => HttpResponse.json({ code: 'x', detail: 'down' }, { status: 400 })));
    renderRoutes(siteAdminRoutes, '/admin/setup', { boot: adminBoot });
    expect(await screen.findByText('The list of weblogs could not be loaded.')).toBeInTheDocument();
  });

  it('is not offered to an editor', async () => {
    renderRoutes(siteAdminRoutes, '/admin/setup', { boot: editorBoot });
    expect(await screen.findByText('That page does not exist.')).toBeInTheDocument();
  });
});
