import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { API, handlers, heading, problem, recording, renderPeople, seen, server, fakeServer } from './harness';

const { http, HttpResponse } = handlers;

const ada = { userName: 'ada', screenName: 'Ada L', fullName: 'Ada Lovelace', emailAddress: 'ada@example.com', enabled: true,
  globalRoles: ['editor'], sso: false, kind: 'PERSON', locale: 'en_US', timeZone: 'Europe/London', writer: true };
const rae = { ...ada, userName: 'rae', screenName: 'Rae', emailAddress: 'rae@example.com', sso: true, writer: false, globalRoles: ['reader'] };
const bot = { userName: 'bot', screenName: 'Bot', emailAddress: 'bot@agents.invalid', enabled: true, globalRoles: ['editor'], sso: false, kind: 'AGENT' };
const options = { themes: [], locales: [{ id: 'en_US', name: 'English (United States)' }, { id: 'de', name: 'German' }],
  timeZones: ['Europe/London', 'UTC'] };
let features = { groupBlogging: true };
const memberships = [{ handle: 'alpha', name: 'Alpha', permission: 'ADMIN' }, { handle: 'beta', name: 'Beta', permission: 'POST' }];
let held = memberships;

fakeServer();
beforeEach(() => {
  features = { groupBlogging: true };
  held = memberships;
  server.use(
    http.get(`${API}/meta/weblog-options`, () => HttpResponse.json(options)),
    http.get(`${API}/meta`, () => HttpResponse.json({ features, limits: {} })),
    http.get(`${API}/admin/users`, ({ request }) => {
      const q = new URL(request.url).searchParams.get('q');
      return HttpResponse.json({ items: [ada, rae, bot].filter((u) => u.userName === q), offset: 0, limit: 200, hasMore: false });
    }),
    http.get(`${API}/admin/users/:userName/weblogs`, () => HttpResponse.json({ items: held })),
    http.get(`${API}/weblogs`, () => HttpResponse.json({
      items: [{ handle: 'alpha', name: 'Alpha' }, { handle: 'beta', name: 'Beta' }, { handle: 'gamma', name: 'Gamma' }],
      offset: 0, limit: 200, hasMore: false }))
  );
});

const label = (name: string) => screen.findByLabelText(name);

describe('New person', () => {
  it('creates a person with an emailed link by default, sending no password', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/admin/users`, recording(() => HttpResponse.json(ada, { status: 201 }))));
    const { router } = renderPeople('/admin/users/new');
    expect(await heading()).toHaveTextContent('New user');
    expect(await screen.findByText('All fields are required.')).toHaveClass('pagetip');
    await user.type(await label('Username'), 'newbie');
    await user.type(screen.getByLabelText('Screen name'), 'Newbie');
    await user.type(screen.getByLabelText('Full name'), 'New Bie');
    await user.type(screen.getByLabelText('Email'), 'n@example.com');
    expect(screen.getByRole('radio', { name: 'Email a set-password link' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Author' })).toBeChecked();
    expect(screen.queryByLabelText('Enabled')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => { expect(router.state.location.pathname).toBe('/admin/users'); });
    expect(seen.find((s) => s.method === 'POST')?.body).toEqual({
      userName: 'newbie', screenName: 'Newbie', fullName: 'New Bie', emailAddress: 'n@example.com',
      locale: 'en_US', timeZone: expect.any(String) as string, administrator: false });
    expect(router.state.location.state).toEqual({ flash: 'User created. A set-password link has been emailed.' });
  });

  it('sets a password now and makes a site admin when asked', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/admin/users`, recording(() => HttpResponse.json(ada, { status: 201 }))));
    const { router } = renderPeople('/admin/users/new');
    await user.type(await label('Username'), 'newbie');
    await user.click(screen.getByRole('radio', { name: 'Set a password now' }));
    await user.click(screen.getByRole('radio', { name: 'Site admin' }));
    await user.type(screen.getByLabelText('Password'), 'longenough1');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => { expect(router.state.location.pathname).toBe('/admin/users'); });
    expect(seen.find((s) => s.method === 'POST')?.body).toMatchObject({ password: 'longenough1', administrator: true });
    expect(router.state.location.state).toEqual({ flash: 'User "newbie" created' });
  });

  it('puts a refused field on its control and the problem in place', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/admin/users`, () => problem(409, 'user.nameInUse', 'Username already in use.',
      [{ field: 'userName', message: 'Username already in use.' }])));
    const { router } = renderPeople('/admin/users/new');
    await user.type(await label('Username'), 'ada');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    const field = await screen.findByLabelText('Username');
    await waitFor(() => { expect(field).toHaveClass('is-invalid'); });
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(field).toHaveFocus();
    expect(screen.getByRole('alert')).toHaveTextContent('Username already in use.');
    expect(router.state.location.pathname).toBe('/admin/users/new');
  });

  it('shows the JSP wording when the mail cannot be sent', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/admin/users`, () => problem(400, 'user.mailNotConfigured', 'Mail is off.')));
    renderPeople('/admin/users/new');
    await user.type(await label('Username'), 'newbie');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('No outgoing mail is configured; set a password directly instead.');
  });
});

describe('Edit person', () => {
  it('loads the account, keeps the user name read-only and saves the changes', async () => {
    const user = userEvent.setup();
    server.use(http.patch(`${API}/admin/users/ada`, recording(() => HttpResponse.json(ada))));
    const { router } = renderPeople('/admin/users/ada');
    expect(await heading()).toHaveTextContent('Edit user');
    expect(await screen.findByText('Editing user ada')).toHaveClass('subtitle');
    const name = await label('Username');
    expect(name).toHaveValue('ada');
    expect(name).toHaveAttribute('readonly');
    expect(await screen.findByLabelText('Screen name')).toHaveValue('Ada L');
    expect(screen.getByLabelText('Locale')).toHaveValue('en_US');
    expect(screen.getByLabelText('Time zone')).toHaveValue('Europe/London');
    expect(screen.getByLabelText('Enabled')).toBeChecked();
    expect(screen.getByLabelText('Administrator')).not.toBeChecked();
    expect(screen.queryByLabelText('Can write (editor)')).toBeNull();
    await user.clear(screen.getByLabelText('Screen name'));
    await user.type(screen.getByLabelText('Screen name'), 'Countess');
    await user.click(screen.getByLabelText('Administrator'));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => { expect(router.state.location.pathname).toBe('/admin/users'); });
    expect(seen.find((s) => s.method === 'PATCH')?.body).toEqual({
      screenName: 'Countess', fullName: 'Ada Lovelace', emailAddress: 'ada@example.com', locale: 'en_US',
      timeZone: 'Europe/London', enabled: true, administrator: true });
    expect(router.state.location.state).toEqual({ flash: 'User saved.' });
  });

  it('sends a typed password only when one is entered', async () => {
    const user = userEvent.setup();
    server.use(http.patch(`${API}/admin/users/ada`, recording(() => HttpResponse.json(ada))));
    renderPeople('/admin/users/ada');
    await user.type(await label('Password'), 'brandnewpass');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => { expect(seen.some((s) => s.method === 'PATCH')).toBe(true); });
    expect(seen.find((s) => s.method === 'PATCH')?.body).toMatchObject({ password: 'brandnewpass' });
  });

  it('refuses an admin disabling or demoting themselves, in the JSP wording, and stays on the page', async () => {
    const user = userEvent.setup();
    server.use(http.patch(`${API}/admin/users/ada`, () => problem(409, 'user.cantChangeOwnRole', 'You cannot remove your own administrator role.')));
    const { router } = renderPeople('/admin/users/ada');
    await user.click(await label('Enabled'));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('You cannot change your own role.');
    expect(router.state.location.pathname).toBe('/admin/users/ada');
    server.use(http.patch(`${API}/admin/users/ada`, () => problem(409, 'user.cantDisableSelf', 'x')));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('You cannot disable your own account.')).toBeInTheDocument();
  });

  it('shows a refused email on the email field', async () => {
    const user = userEvent.setup();
    server.use(http.patch(`${API}/admin/users/ada`, () => problem(400, 'user.emailInvalid', 'Enter a valid email address.',
      [{ field: 'emailAddress', message: 'Enter a valid email address.' }])));
    renderPeople('/admin/users/ada');
    await user.click(await screen.findByRole('button', { name: 'Save' }));
    const email = await screen.findByLabelText('Email');
    await waitFor(() => { expect(email).toHaveAttribute('aria-invalid', 'true'); });
    expect(email).toHaveFocus();
  });

  it('shows a single-sign-on account without password or administrator, with the writer box', async () => {
    const user = userEvent.setup();
    server.use(http.patch(`${API}/admin/users/rae`, recording(() => HttpResponse.json(rae))));
    renderPeople('/admin/users/rae');
    expect(await screen.findByText('Signs in with Google (single sign-on). This account has no password.')).toHaveAttribute('id', 'ssoBoundMarker');
    expect(screen.queryByLabelText('Password')).toBeNull();
    expect(screen.queryByLabelText('Administrator')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Send set-password link' })).toBeNull();
    await user.click(screen.getByLabelText('Can write (editor)'));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => { expect(seen.some((s) => s.method === 'PATCH')).toBe(true); });
    const body = seen.find((s) => s.method === 'PATCH')?.body as Record<string, unknown>;
    expect(body['writer']).toBe(true);
    expect(body).not.toHaveProperty('administrator');
    expect(body).not.toHaveProperty('password');
  });

  it('sends the password link only after confirming, and says where it went', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/admin/users/ada/password-link`, recording(() => new HttpResponse(null, { status: 204 }))));
    renderPeople('/admin/users/ada');
    await user.click(await screen.findByRole('button', { name: 'Send set-password link' }));
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('Email a set-password link to ada at ada@example.com?');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(seen.some((s) => s.path.includes('password-link'))).toBe(false);
    await user.click(screen.getByRole('button', { name: 'Send set-password link' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Send set-password link' }));
    expect(await screen.findByRole('status')).toHaveTextContent('A set-password link has been emailed to ada@example.com.');
    expect(seen.filter((s) => s.path.includes('password-link'))).toHaveLength(1);
  });

  it('shows a refused password link in the JSP wording', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/admin/users/ada/password-link`, () => problem(400, 'user.mailNotConfigured', 'Mail is off.')));
    renderPeople('/admin/users/ada');
    await user.click(await screen.findByRole('button', { name: 'Send set-password link' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Send set-password link' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('No outgoing mail is configured; set a password directly instead.');
  });

  it('says so when there is no such account', async () => {
    renderPeople('/admin/users/ghost');
    expect(await screen.findByText('No such user.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save' })).toBeNull();
  });

  it('sends an agent to its own page, as the JSP did', async () => {
    const { router } = renderPeople('/admin/users/bot');
    await waitFor(() => { expect(router.state.location.pathname).toBe('/admin/agents/bot'); });
  });
});

describe('The account\'s weblogs', () => {
  it('lists the memberships with the Members page levels', async () => {
    renderPeople('/admin/users/ada');
    const section = (await screen.findByRole('heading', { name: 'Weblogs' })).closest('section') as HTMLElement;
    expect(section.id).toBe('userWeblogsSection');
    const table = await within(section).findByRole('table');
    expect(table.id).toBe('userWeblogsTable');
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    expect(within(rows[0] as HTMLElement).getByText('alpha')).toHaveClass('data');
    expect(within(rows[0] as HTMLElement).getByLabelText('Level')).toHaveValue('ADMIN');
    expect(within(rows[1] as HTMLElement).getByLabelText('Level')).toHaveValue('POST');
  });

  it('changes a level', async () => {
    const user = userEvent.setup();
    server.use(http.patch(`${API}/admin/users/ada/weblogs/beta`, recording(() => HttpResponse.json({ handle: 'beta', name: 'Beta', permission: 'EDIT_DRAFT' }))));
    renderPeople('/admin/users/ada');
    const table = await screen.findByRole('table', { name: '' });
    const row = within(table).getAllByRole('row')[2] as HTMLElement;
    await user.selectOptions(within(row).getByLabelText('Level'), 'EDIT_DRAFT');
    await user.click(within(row).getByRole('button', { name: 'Change' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Changed the level on beta.');
    expect(seen.find((s) => s.method === 'PATCH')?.body).toEqual({ permission: 'EDIT_DRAFT' });
  });

  it('removes only after confirming, then refreshes', async () => {
    const user = userEvent.setup();
    server.use(http.delete(`${API}/admin/users/ada/weblogs/beta`, recording(() => {
      held = [memberships[0] as (typeof memberships)[number]];
      return new HttpResponse(null, { status: 204 });
    })));
    renderPeople('/admin/users/ada');
    const table = await screen.findByRole('table');
    await user.click(within(within(table).getAllByRole('row')[2] as HTMLElement).getByRole('button', { name: 'Remove' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('Remove this account from beta?');
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));
    expect(seen.some((s) => s.method === 'DELETE')).toBe(false);
    await user.click(within(within(table).getAllByRole('row')[2] as HTMLElement).getByRole('button', { name: 'Remove' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Removed from beta.');
    await waitFor(() => { expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(2); });
  });

  it('adds the account to a weblog it is not in, Author by default', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/admin/users/ada/weblogs`, recording(() => HttpResponse.json({ handle: 'gamma', name: 'Gamma', permission: 'POST' }, { status: 201 }))));
    renderPeople('/admin/users/ada');
    const handle = await screen.findByLabelText('Weblog');
    expect([...within(handle).getAllByRole('option')].map((o) => o.textContent)).toEqual(['Gamma (gamma)']);
    expect(screen.getByLabelText('Level', { selector: '#userWeblogsAddLevel' })).toHaveValue('POST');
    await user.click(screen.getByRole('button', { name: 'Add to weblog' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Added to gamma.');
    expect(seen.find((s) => s.method === 'POST')?.body).toEqual({ handle: 'gamma', permission: 'POST' });
  });

  it('shows a refused grant in place', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/admin/users/ada/weblogs`, () => problem(409, 'member.groupBloggingOff', 'Group blogging is off.')));
    renderPeople('/admin/users/ada');
    await user.click(await screen.findByRole('button', { name: 'Add to weblog' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Group blogging is off.');
  });

  it('explains the missing add form: group blogging off, or already everywhere', async () => {
    features = { groupBlogging: false };
    const { unmount } = renderPeople('/admin/users/ada');
    expect(await screen.findByText('Group blogging is turned off, so an account cannot be added to a weblog here.')).toHaveAttribute('id', 'userWeblogsGroupBloggingOff');
    expect(screen.queryByRole('button', { name: 'Add to weblog' })).toBeNull();
    unmount();
    features = { groupBlogging: true };
    held = [...memberships, { handle: 'gamma', name: 'Gamma', permission: 'POST' }];
    renderPeople('/admin/users/ada');
    expect(await screen.findByText('This account is already a member of every weblog.')).toBeInTheDocument();
  });

  it('shows the empty state with a pointer to the form', async () => {
    held = [];
    renderPeople('/admin/users/ada');
    expect(await screen.findByText('No weblogs yet.')).toHaveClass('empty-state-title');
    expect(screen.getByText('Add one with the form below.')).toHaveClass('empty-state-body');
  });

  it('keeps a non-admin out of the account page', async () => {
    const { editorBoot } = await import('../support');
    renderPeople('/admin/users/ada', editorBoot);
    expect(await screen.findByText('That page does not exist.')).toBeInTheDocument();
    expect(seen).toEqual([]);
  });
});
