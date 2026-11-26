import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { editorBoot } from '../support';
import { API, cacheText, fakeServer, handlers, problem, recording, renderPeople, renderPeopleWithCache, seen, server } from './harness';

const { http, HttpResponse } = handlers;

const mine = [
  { id: 't1', label: 'CI script', scopeRole: 'POST', created: '2026-11-01T10:00:00Z', lastUsedAt: '2026-11-20T09:30:00Z' },
  { id: 't2', label: 'Reader', scopeWeblog: 'alpha', scopeRole: 'READ', created: '2026-10-01T10:00:00Z', expiresAt: '2027-01-01T00:00:00Z' }
];
const SECRET = 'prtk_SUPER-SECRET-VALUE-123';
let live = mine;

fakeServer();
beforeEach(() => {
  live = mine;
  server.use(
    http.get(`${API}/tokens`, () => HttpResponse.json(live)),
    http.get(`${API}/me`, () => HttpResponse.json({ userName: 'jake', kind: 'PERSON', globalAdmin: false, tokenScope: {},
      weblogs: [{ handle: 'alpha', name: 'Alpha', permission: 'ADMIN' }] })),
    http.get(`${API}/weblogs`, () => HttpResponse.json({
      items: [{ handle: 'alpha', name: 'Alpha' }, { handle: 'zed', name: 'Zed' }], offset: 0, limit: 200, hasMore: false }))
  );
});

describe('My tokens', () => {
  it('lists the live tokens as the JSP table does', async () => {
    renderPeople('/profile/tokens', editorBoot);
    expect(await screen.findByText(/A token lets a script or an agent/)).toHaveClass('subtitle');
    expect(screen.getByRole('heading', { name: 'Live tokens' })).toHaveClass('section-head');
    const table = await screen.findByRole('table');
    expect(table.id).toBe('tokenListTable');
    const rows = within(table).getAllByRole('row').slice(1);
    expect(within(rows[0] as HTMLElement).getByText('CI script')).toBeInTheDocument();
    expect(within(rows[0] as HTMLElement).getByText('Write')).toBeInTheDocument();
    expect(within(rows[0] as HTMLElement).getByText('All its weblogs')).toBeInTheDocument();
    expect(within(rows[0] as HTMLElement).getByText('Never')).toBeInTheDocument();
    expect(within(rows[1] as HTMLElement).getByText('alpha')).toHaveClass('data');
    expect(within(rows[1] as HTMLElement).getByText('Read')).toBeInTheDocument();
    expect(within(rows[1] as HTMLElement).getByText('2027-01-01 00:00 UTC')).toBeInTheDocument();
  });

  it('shows the empty state when there are none', async () => {
    live = [];
    renderPeople('/profile/tokens', editorBoot);
    const empty = await screen.findByText('No live tokens.');
    expect(empty.closest('.empty-state')).toHaveAttribute('id', 'tokenListEmpty');
    expect(screen.getByText('Create one below. Its secret is shown once.')).toBeInTheDocument();
  });

  it('offers the weblogs the account belongs to as pins, and every weblog to a site admin', async () => {
    const { unmount } = renderPeople('/profile/tokens', editorBoot);
    const pin = await screen.findByLabelText('Weblog');
    await waitFor(() => { expect([...within(pin).getAllByRole('option')].map((o) => o.textContent)).toEqual(['All its weblogs', 'Alpha (alpha)']); });
    unmount();
    server.use(http.get(`${API}/me`, () => HttpResponse.json({ userName: 'jake', kind: 'PERSON', globalAdmin: true, tokenScope: {}, weblogs: [] })));
    renderPeople('/profile/tokens');
    const adminPin = await screen.findByLabelText('Weblog');
    await waitFor(() => { expect([...within(adminPin).getAllByRole('option')].map((o) => o.textContent)).toEqual(['All its weblogs', 'Alpha (alpha)', 'Zed (zed)']); });
  });

  it('mints with label, role, pin and a 30-day expiry converted to an instant, then reveals the secret once', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/tokens`, recording(() => HttpResponse.json(
      { token: SECRET, info: { id: 't3', label: 'Deploy', scopeRole: 'ADMIN' } }, { status: 201 }))));
    renderPeople('/profile/tokens', editorBoot);
    await user.type(await screen.findByLabelText('Label'), 'Deploy');
    expect(screen.getByLabelText('Role')).toHaveValue('POST');
    expect(screen.getByLabelText('Expires')).toHaveValue('90');
    await user.selectOptions(screen.getByLabelText('Role'), 'ADMIN');
    await waitFor(() => { expect(within(screen.getByLabelText('Weblog')).getAllByRole('option')).toHaveLength(2); });
    await user.selectOptions(screen.getByLabelText('Weblog'), 'alpha');
    await user.selectOptions(screen.getByLabelText('Expires'), '30');
    const before = Date.now();
    await user.click(screen.getByRole('button', { name: 'Create token' }));
    const secret = await screen.findByLabelText('Token');
    expect(secret).toHaveValue(SECRET);
    expect(secret).toHaveAttribute('readonly');
    expect(document.getElementById('tokenRevealPage')).not.toBeNull();
    expect(screen.getByText('Copy it now. This is the only time it is shown.')).toBeInTheDocument();
    expect(screen.getByLabelText('MCP connection')).toHaveValue(
      `claude mcp add --transport http pressroom http://pressroom.test/pressroom/api/mcp --header "Authorization: Bearer ${SECRET}"`);
    const body = seen.find((s) => s.method === 'POST')?.body as { expiresAt: string } & Record<string, unknown>;
    expect(body).toMatchObject({ label: 'Deploy', role: 'ADMIN', weblog: 'alpha' });
    const days = (Date.parse(body.expiresAt) - before) / 86_400_000;
    expect(days).toBeGreaterThan(29.99);
    expect(days).toBeLessThan(30.01);
  });

  it('omits the expiry for never and the pin for all weblogs', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/tokens`, recording(() => HttpResponse.json({ token: SECRET, info: { id: 't3' } }, { status: 201 }))));
    renderPeople('/profile/tokens', editorBoot);
    await user.type(await screen.findByLabelText('Label'), 'Forever');
    await user.selectOptions(screen.getByLabelText('Expires'), 'never');
    await user.click(screen.getByRole('button', { name: 'Create token' }));
    await screen.findByLabelText('Token');
    expect(seen.find((s) => s.method === 'POST')?.body).toEqual({ label: 'Forever', role: 'POST' });
  });

  it('shows the secret once: never in storage, never in the caches, gone after Done and after leaving', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/tokens`, () => HttpResponse.json({ token: SECRET, info: { id: 't3', label: 'Deploy' } }, { status: 201 })));
    const { router, unmount } = renderPeopleWithCache('/profile/tokens', editorBoot);
    await user.type(await screen.findByLabelText('Label'), 'Deploy');
    await user.click(screen.getByRole('button', { name: 'Create token' }));
    expect(await screen.findByLabelText('Token')).toHaveValue(SECRET);
    expect(JSON.stringify({ ...window.localStorage })).not.toContain(SECRET);
    expect(JSON.stringify({ ...window.sessionStorage })).not.toContain(SECRET);
    expect(cacheText()).not.toContain(SECRET);
    await user.click(screen.getByRole('button', { name: 'Done' }));
    expect(screen.queryByDisplayValue(SECRET)).toBeNull();
    expect(document.body.textContent).not.toContain(SECRET);
    expect(screen.getByLabelText('Label')).toHaveValue('');
    expect(cacheText()).not.toContain(SECRET);
    // Mint again, then navigate away and back: the secret does not come back.
    await user.type(screen.getByLabelText('Label'), 'Two');
    await user.click(screen.getByRole('button', { name: 'Create token' }));
    await screen.findByLabelText('Token');
    await act(async () => { await router.navigate('/elsewhere'); });
    await act(async () => { await router.navigate('/profile/tokens'); });
    await screen.findByLabelText('Label');
    expect(screen.queryByDisplayValue(SECRET)).toBeNull();
    expect(cacheText()).not.toContain(SECRET);
    unmount();
  });

  it('copies the secret to the clipboard', async () => {
    const user = userEvent.setup();
    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(window.navigator, 'clipboard', { value: { writeText }, configurable: true });
    server.use(http.post(`${API}/tokens`, () => HttpResponse.json({ token: SECRET, info: { id: 't3' } }, { status: 201 })));
    renderPeople('/profile/tokens', editorBoot);
    await user.type(await screen.findByLabelText('Label'), 'Deploy');
    await user.click(screen.getByRole('button', { name: 'Create token' }));
    await screen.findByLabelText('Token');
    await user.click(screen.getAllByRole('button', { name: 'Copy' })[0] as HTMLElement);
    expect(writeText).toHaveBeenCalledWith(SECRET);
    await waitFor(() => { expect(screen.getAllByRole('button', { name: 'Copy' })[0]).toHaveClass('copied'); });
  });

  it('puts a refused label on the label field and the problem in place, minting nothing', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/tokens`, () => problem(400, 'request.invalidFields', 'Give the token a label.',
      [{ field: 'label', message: 'Give the token a label.' }])));
    renderPeople('/profile/tokens', editorBoot);
    await user.click(await screen.findByRole('button', { name: 'Create token' }));
    const label = await screen.findByLabelText('Label');
    await waitFor(() => { expect(label).toHaveAttribute('aria-invalid', 'true'); });
    expect(label).toHaveClass('is-invalid');
    expect(label).toHaveFocus();
    expect(screen.getByRole('alert')).toHaveTextContent('Give the token a label.');
    expect(screen.queryByLabelText('Token')).toBeNull();
  });

  it('revokes only after confirming, then refreshes the list', async () => {
    const user = userEvent.setup();
    server.use(http.delete(`${API}/tokens/t1`, recording(() => { live = [mine[1] as (typeof mine)[number]]; return new HttpResponse(null, { status: 204 }); })));
    renderPeople('/profile/tokens', editorBoot);
    const table = await screen.findByRole('table');
    const revoke = () => within(within(table).getAllByRole('row')[1] as HTMLElement).getByRole('button', { name: 'Revoke' });
    await user.click(revoke());
    expect(screen.getByRole('dialog')).toHaveTextContent('Revoke this token? Anything using it stops working at once.');
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));
    expect(seen.some((s) => s.method === 'DELETE')).toBe(false);
    await user.click(revoke());
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Revoke' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Token revoked.');
    await waitFor(() => { expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(2); });
  });

  it('shows a refused revoke in place', async () => {
    const user = userEvent.setup();
    server.use(http.delete(`${API}/tokens/t1`, () => problem(404, 'token.notFound', 'No such token.')));
    renderPeople('/profile/tokens', editorBoot);
    const table = await screen.findByRole('table');
    await user.click(within(within(table).getAllByRole('row')[1] as HTMLElement).getByRole('button', { name: 'Revoke' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Revoke' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('No such token.');
  });
});

const siteRows = [
  { id: 'a1', owner: 'jake', ownerKind: 'PERSON', issuedBy: 'jake', label: 'Mine', scopeRole: 'ADMIN', created: '2026-11-01T10:00:00Z', live: true },
  { id: 'a2', owner: 'bot', ownerKind: 'AGENT', issuedBy: 'jake', label: 'Poster', scopeRole: 'POST', scopeWeblog: 'alpha',
    created: '2026-10-01T10:00:00Z', lastUsedAt: '2026-11-02T10:00:00Z', expiresAt: '2026-12-01T10:00:00Z', live: true },
  { id: 'a3', owner: 'bot', ownerKind: 'AGENT', issuedBy: 'bot', label: 'Old', scopeRole: 'READ', created: '2026-01-01T10:00:00Z',
    revokedAt: '2026-02-01T10:00:00Z', live: false },
  { id: 'a4', owner: 'jake', ownerKind: 'PERSON', label: 'Lapsed', scopeRole: 'READ', created: '2026-01-01T10:00:00Z', expiresAt: '2026-01-02T10:00:00Z', live: false }
];

describe('Site tokens', () => {
  beforeEach(() => {
    server.use(http.get(`${API}/admin/tokens`, recording(({ request }) => HttpResponse.json({
      items: new URL(request.url).searchParams.get('all') === 'true' ? siteRows : siteRows.filter((r) => r.live) }))));
  });

  it('lists every live token with owner, issuer, kind, scope and state', async () => {
    const { container } = renderPeople('/admin/tokens');
    expect(await screen.findByText('Every live token on this site. A revoked token stops working on its next request.')).toHaveClass('subtitle');
    expect(container.querySelector('#apiTokensPage')).not.toBeNull();
    const table = await screen.findByRole('table');
    expect(table.id).toBe('apiTokensTable');
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent))
      .toEqual(['Owner', 'Kind', 'Label', 'Role', 'Weblog', 'Created', 'Last used', 'Expires', 'State']);
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    const poster = within(rows[1] as HTMLElement);
    expect(poster.getByText(/Issued by/)).toBeInTheDocument();
    expect(poster.getByText('Agent')).toBeInTheDocument();
    expect(poster.getByText('alpha')).toHaveClass('data');
    expect(poster.getByRole('button', { name: 'Revoke' })).toBeInTheDocument();
    expect(within(rows[0] as HTMLElement).queryByText(/Issued by/)).toBeNull();
    expect(seen[0]?.path).toBe('/admin/tokens?all=false');
  });

  it('toggles to revoked and expired tokens, which have no revoke button', async () => {
    const user = userEvent.setup();
    const { router } = renderPeople('/admin/tokens');
    await user.click(await screen.findByRole('link', { name: 'Show revoked and expired' }));
    expect(router.state.location.search).toBe('?all=true');
    await waitFor(() => { expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(5); });
    const rows = within(screen.getByRole('table')).getAllByRole('row').slice(1);
    expect(within(rows[2] as HTMLElement).getByText(/Revoked/)).toBeInTheDocument();
    expect(within(rows[2] as HTMLElement).queryByRole('button')).toBeNull();
    expect(within(rows[3] as HTMLElement).getByText('Expired')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Show live tokens only' })).toBeInTheDocument();
  });

  it('revokes any token after confirming', async () => {
    const user = userEvent.setup();
    server.use(http.delete(`${API}/admin/tokens/a2`, recording(() => new HttpResponse(null, { status: 204 }))));
    renderPeople('/admin/tokens');
    const table = await screen.findByRole('table');
    await user.click(within(within(table).getAllByRole('row')[2] as HTMLElement).getByRole('button', { name: 'Revoke' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Revoke' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Token revoked.');
    expect(seen.some((s) => s.method === 'DELETE' && s.path === '/admin/tokens/a2')).toBe(true);
  });

  it('shows the empty state, a refused list, and keeps a non-admin out', async () => {
    server.use(http.get(`${API}/admin/tokens`, () => HttpResponse.json({ items: [] })));
    const { unmount } = renderPeople('/admin/tokens');
    expect(await screen.findByText('No tokens to show.')).toHaveClass('empty-state-title');
    unmount();
    server.use(http.get(`${API}/admin/tokens`, () => problem(403, 'forbidden', 'Not for you.')));
    const second = renderPeople('/admin/tokens');
    expect(await screen.findByRole('alert')).toHaveTextContent('Not for you.');
    second.unmount();
    seen.length = 0;
    renderPeople('/admin/tokens', editorBoot);
    expect(await screen.findByText('That page does not exist.')).toBeInTheDocument();
    expect(seen).toEqual([]);
  });
});
