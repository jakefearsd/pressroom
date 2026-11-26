import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { editorBoot } from '../support';
import { API, cacheText, fakeServer, handlers, problem, recording, renderPeople, renderPeopleWithCache, seen, server } from './harness';

const { http, HttpResponse } = handlers;
const SECRET = 'prtk_AGENT-SECRET-456';

let agent = { userName: 'bot', name: 'Poster Bot', emailAddress: 'bot@agents.invalid', notes: 'Posts the news.', enabled: true, siteAdmin: false,
  tokens: [{ id: 't1', label: 'prod', scopeRole: 'POST', scopeWeblog: 'alpha', created: '2026-11-01T10:00:00Z' }] };

fakeServer();
beforeEach(() => {
  agent = { ...agent, enabled: true, siteAdmin: false, name: 'Poster Bot' };
  server.use(
    http.get(`${API}/admin/agents/bot`, () => HttpResponse.json(agent)),
    http.get(`${API}/admin/agents/newbot`, () => HttpResponse.json({ ...agent, userName: 'newbot' })),
    http.get(`${API}/admin/users/newbot/weblogs`, () => HttpResponse.json({ items: [] })),
    http.get(`${API}/admin/agents/ghost`, () => problem(404, 'agent.notFound', 'No such agent.')),
    http.get(`${API}/meta`, () => HttpResponse.json({ features: { groupBlogging: true }, limits: {} })),
    http.get(`${API}/admin/users/bot/weblogs`, () => HttpResponse.json({ items: [{ handle: 'alpha', name: 'Alpha', permission: 'POST' }] })),
    http.get(`${API}/weblogs`, () => HttpResponse.json({
      items: [{ handle: 'alpha', name: 'Alpha' }, { handle: 'zed', name: 'Zed' }], offset: 0, limit: 200, hasMore: false }))
  );
});

describe('New agent', () => {
  it('shows the JSP form and creates the agent, then opens its page with the message', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/admin/agents`, recording(() => HttpResponse.json(agent, { status: 201 }))));
    const { router } = renderPeople('/admin/agents/new');
    expect(await screen.findByText(/An agent is an account for software/)).toHaveClass('subtitle');
    expect(screen.getByText('Letters and digits. It cannot be changed later.')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Username'), 'newbot');
    await user.type(screen.getByLabelText('Name'), 'New Bot');
    await user.type(screen.getByLabelText('Notes'), 'For news');
    await user.click(screen.getByLabelText('Site administrator'));
    await user.click(screen.getByRole('button', { name: 'Create agent' }));
    await waitFor(() => { expect(router.state.location.pathname).toBe('/admin/agents/newbot'); });
    expect(seen.find((s) => s.method === 'POST')?.body).toEqual({ userName: 'newbot', name: 'New Bot', notes: 'For news', siteAdmin: true });
    expect(router.state.location.state).toEqual({ message: 'Agent newbot created.' });
  });

  it('sends the optional email when given, and Cancel returns to the list', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/admin/agents`, recording(() => HttpResponse.json(agent, { status: 201 }))));
    renderPeople('/admin/agents/new');
    await user.type(await screen.findByLabelText('Username'), 'newbot');
    await user.type(screen.getByLabelText('Name'), 'New Bot');
    await user.type(screen.getByLabelText('Email (optional)'), 'x@example.com');
    expect(screen.getByRole('link', { name: 'Cancel' })).toHaveAttribute('href', '/admin/users');
    await user.click(screen.getByRole('button', { name: 'Create agent' }));
    await waitFor(() => { expect(seen.some((s) => s.method === 'POST')).toBe(true); });
    expect(seen.find((s) => s.method === 'POST')?.body).toMatchObject({ emailAddress: 'x@example.com', siteAdmin: false });
  });

  it('puts a refused user name on its field with the problem in place', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/admin/agents`, () => problem(409, 'user.nameInUse', 'Username already in use.',
      [{ field: 'userName', message: 'Username already in use.' }])));
    const { router } = renderPeople('/admin/agents/new');
    await user.type(await screen.findByLabelText('Username'), 'bot');
    await user.click(screen.getByRole('button', { name: 'Create agent' }));
    const field = await screen.findByLabelText('Username');
    await waitFor(() => { expect(field).toHaveAttribute('aria-invalid', 'true'); });
    expect(field).toHaveClass('is-invalid');
    expect(field).toHaveFocus();
    expect(screen.getByRole('alert')).toHaveTextContent('Username already in use.');
    expect(router.state.location.pathname).toBe('/admin/agents/new');
  });

  it('keeps a non-admin out', async () => {
    renderPeople('/admin/agents/new', editorBoot);
    expect(await screen.findByText('That page does not exist.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Create agent' })).toBeNull();
  });
});

describe('Agent page', () => {
  it('shows the details, status, weblogs and tokens', async () => {
    const { container } = renderPeople('/admin/agents/bot');
    expect(await screen.findByText('Agent bot')).toHaveClass('subtitle');
    expect(container.querySelector('#agentPage')).not.toBeNull();
    expect(screen.getByLabelText('Username')).toHaveAttribute('readonly');
    expect(screen.getByLabelText('Username')).toHaveValue('bot');
    expect(screen.getByLabelText('Name')).toHaveValue('Poster Bot');
    expect(screen.getByLabelText('Notes')).toHaveValue('Posts the news.');
    expect(screen.getByLabelText('Site administrator')).not.toBeChecked();
    expect(screen.getByText('Enabled')).toHaveClass('status-pill', 'status-enabled');
    expect(screen.getByText('Its tokens work.')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Weblogs' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'API tokens' })).toBeInTheDocument();
    expect(await screen.findByText('prod')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'New token' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Send set-password link' })).toBeNull();
  });

  it('saves the details and stays on the page with the message', async () => {
    const user = userEvent.setup();
    server.use(http.patch(`${API}/admin/agents/bot`, recording(() => HttpResponse.json({ ...agent, name: 'Renamed' }))));
    const { router } = renderPeople('/admin/agents/bot');
    const name = await screen.findByLabelText('Name');
    await user.clear(name);
    await user.type(name, 'Renamed');
    await user.click(screen.getByLabelText('Site administrator'));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Agent saved.');
    expect(seen.find((s) => s.method === 'PATCH')?.body).toEqual({ name: 'Renamed', notes: 'Posts the news.', siteAdmin: true });
    expect(router.state.location.pathname).toBe('/admin/agents/bot');
  });

  it('shows a refused save on the field', async () => {
    const user = userEvent.setup();
    server.use(http.patch(`${API}/admin/agents/bot`, () => problem(400, 'agent.nameRequired', 'Enter a name.', [{ field: 'name', message: 'Enter a name.' }])));
    renderPeople('/admin/agents/bot');
    const name = await screen.findByLabelText('Name');
    await user.clear(name);
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => { expect(screen.getByLabelText('Name')).toHaveAttribute('aria-invalid', 'true'); });
    expect(screen.getByLabelText('Name')).toHaveFocus();
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a name.');
  });

  it('disables after confirming, with the JSP question, and enables again', async () => {
    const user = userEvent.setup();
    server.use(http.patch(`${API}/admin/agents/bot`, recording(async ({ request }) => {
      const body = await request.clone().json() as { enabled: boolean };
      agent = { ...agent, enabled: body.enabled };
      return HttpResponse.json(agent);
    })));
    renderPeople('/admin/agents/bot');
    await user.click(await screen.findByRole('button', { name: 'Disable' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('Disable bot? Every one of its tokens stops working on its next request.');
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));
    expect(seen.some((s) => s.method === 'PATCH')).toBe(false);
    await user.click(screen.getByRole('button', { name: 'Disable' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Disable' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Agent disabled.');
    expect(seen.find((s) => s.method === 'PATCH')?.body).toEqual({ enabled: false });
    expect(await screen.findByText('Disabled')).toHaveClass('status-disabled');
    expect(screen.getByText('None of its tokens work until it is enabled again.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Enable' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('Enable bot? Its live tokens start working again.');
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Enable' }));
    expect(await screen.findByText('Agent enabled.')).toBeInTheDocument();
  });

  it('mints a token pinned to a weblog the agent belongs to, reveals it once, and never stores it', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/admin/agents/bot/tokens`, recording(() => HttpResponse.json(
      { token: SECRET, info: { id: 't2', label: 'new' } }, { status: 201 }))));
    renderPeopleWithCache('/admin/agents/bot');
    const pin = await screen.findByLabelText('Weblog', { selector: '#tokenPin' });
    await waitFor(() => { expect([...within(pin).getAllByRole('option')].map((o) => o.textContent)).toEqual(['All its weblogs', 'Alpha (alpha)']); });
    await user.type(screen.getByLabelText('Label'), 'new');
    await user.selectOptions(pin, 'alpha');
    await user.selectOptions(screen.getByLabelText('Expires'), 'never');
    await user.click(screen.getByRole('button', { name: 'Create token' }));
    expect(await screen.findByLabelText('Token')).toHaveValue(SECRET);
    expect(seen.find((s) => s.method === 'POST')?.body).toEqual({ label: 'new', role: 'POST', weblog: 'alpha' });
    expect(JSON.stringify({ ...window.localStorage })).not.toContain(SECRET);
    expect(cacheText()).not.toContain(SECRET);
    await user.click(screen.getByRole('button', { name: 'Done' }));
    expect(document.body.textContent).not.toContain(SECRET);
    expect(await screen.findByLabelText('Label')).toHaveValue('');
    expect(cacheText()).not.toContain(SECRET);
  });

  it('offers every weblog as a pin to a site-admin agent', async () => {
    agent = { ...agent, siteAdmin: true };
    renderPeople('/admin/agents/bot');
    const pin = await screen.findByLabelText('Weblog', { selector: '#tokenPin' });
    await waitFor(() => { expect([...within(pin).getAllByRole('option')].map((o) => o.textContent)).toEqual(['All its weblogs', 'Alpha (alpha)', 'Zed (zed)']); });
  });

  it('shows a refused mint in place, and revokes an agent token after confirming', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(`${API}/admin/agents/bot/tokens`, () => problem(400, 'token.weblogNotMember', 'Not a member.', [{ field: 'weblog', message: 'Not a member.' }])),
      http.delete(`${API}/admin/agents/bot/tokens/t1`, recording(() => new HttpResponse(null, { status: 204 })))
    );
    renderPeople('/admin/agents/bot');
    await user.type(await screen.findByLabelText('Label'), 'x');
    await user.click(screen.getByRole('button', { name: 'Create token' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Not a member.');
    expect(screen.getByLabelText('Weblog', { selector: '#tokenPin' })).toHaveAttribute('aria-invalid', 'true');
    const table = (await screen.findByText('prod')).closest('table') as HTMLElement;
    await user.click(within(within(table).getAllByRole('row')[1] as HTMLElement).getByRole('button', { name: 'Revoke' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Revoke' }));
    expect(await screen.findByText('Token revoked.')).toBeInTheDocument();
    expect(seen.some((s) => s.method === 'DELETE' && s.path === '/admin/agents/bot/tokens/t1')).toBe(true);
  });

  it('says so for an unknown agent, and keeps a non-admin out', async () => {
    const { unmount } = renderPeople('/admin/agents/ghost');
    expect(await screen.findByText('No such user.')).toBeInTheDocument();
    unmount();
    seen.length = 0;
    renderPeople('/admin/agents/bot', editorBoot);
    expect(await screen.findByText('That page does not exist.')).toBeInTheDocument();
    expect(seen).toEqual([]);
  });
});
