import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { editorBoot } from '../support';
import { API, handlers, heading, problem, recording, renderPeople, seen, server, fakeServer } from './harness';

const { http, HttpResponse } = handlers;

const accounts = [
  { userName: 'ada', screenName: 'Ada L', fullName: 'Ada Lovelace', emailAddress: 'ada@example.com', enabled: true,
    globalRoles: ['admin', 'editor'], sso: false, kind: 'PERSON', locale: 'en', timeZone: 'UTC', writer: true,
    weblogCount: 2, lastActive: '2026-11-25T08:30:00Z' },
  { userName: 'bot', screenName: 'Bot', emailAddress: 'bot@agents.invalid', enabled: false,
    globalRoles: ['editor'], sso: false, kind: 'AGENT', weblogCount: 0 },
  { userName: 'rae', screenName: 'Rae', emailAddress: 'rae@example.com', enabled: true,
    globalRoles: ['reader'], sso: true, kind: 'PERSON', weblogCount: 0 }
];
const page = (items: object[]) => ({ items, offset: 0, limit: 200, hasMore: false });

fakeServer();
beforeEach(() => {
  server.use(
    http.get(`${API}/admin/users`, recording(({ request }) => {
      const q = new URL(request.url).searchParams.get('q')?.toLowerCase() ?? '';
      const kind = new URL(request.url).searchParams.get('kind');
      return HttpResponse.json(page(accounts.filter((a) =>
        (q === '' || a.userName.includes(q)) && (kind !== 'agents' || a.kind === 'AGENT') && (kind !== 'people' || a.kind === 'PERSON'))));
    })),
    http.get(`${API}/admin/users/:userName/weblogs`, ({ params }) =>
      HttpResponse.json({ items: params['userName'] === 'ada'
        ? [{ handle: 'alpha', name: 'Alpha', permission: 'ADMIN' }, { handle: 'beta', name: 'Beta', permission: 'POST' }] : [] }))
  );
});

describe('Users list', () => {
  it('lists every account as the JSP table does, with tabs, kind, role, status and weblog counts', async () => {
    const { container } = renderPeople('/admin/users');
    expect(await heading()).toBeInTheDocument();
    expect(container.querySelector('#userListPage')).not.toBeNull();
    const table = await screen.findByRole('table');
    expect(table).toHaveClass('pressroomtable', 'table');
    expect(table.id).toBe('userListTable');
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent))
      .toEqual(['Account', 'Kind', 'Role', 'Status', 'Weblogs', 'Last active']);
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(3);
    const ada = within(rows[0] as HTMLElement);
    expect(ada.getByRole('link', { name: 'Ada L' })).toHaveAttribute('href', '/admin/users/ada');
    expect(ada.getByRole('link', { name: 'Ada L' })).toHaveAttribute('data-username', 'ada');
    expect(ada.getByText('Person')).toBeInTheDocument();
    expect(ada.getByText('Site admin')).toBeInTheDocument();
    expect(ada.getByText('Enabled')).toHaveClass('status-pill', 'status-enabled');
    expect(ada.getAllByRole('cell')[4]).toHaveTextContent('2');
    expect(ada.getByText('2026-11-25 08:30 UTC')).toBeInTheDocument();
    expect(within(rows[2] as HTMLElement).getByText('Never')).toHaveClass('text-muted');
    expect(seen.filter((s) => s.path.endsWith('/weblogs'))).toEqual([]);
    const bot = within(rows[1] as HTMLElement);
    expect(bot.getByRole('link', { name: 'Bot' })).toHaveAttribute('href', '/admin/agents/bot');
    expect(bot.getByText('Agent')).toBeInTheDocument();
    expect(bot.getByText('Disabled')).toHaveClass('status-disabled');
    expect(within(rows[2] as HTMLElement).getByText('Reader')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'New person' })).toHaveAttribute('href', '/admin/users/new');
    expect(screen.getByRole('link', { name: 'New agent' })).toHaveAttribute('href', '/admin/agents/new');
    expect(screen.getByRole('link', { name: 'All' })).toHaveAttribute('aria-current', 'page');
  });

  it('asks the API for the tab and the text filter from the URL, and the tabs carry the filter', async () => {
    renderPeople('/admin/users?kind=agents&q=bo');
    expect(await screen.findByRole('link', { name: 'Bot' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Ada L' })).toBeNull();
    expect(seen.some((s) => s.path.startsWith('/admin/users?') && s.path.includes('kind=agents') && s.path.includes('q=bo'))).toBe(true);
    expect(screen.getByRole('link', { name: 'Agents' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'People' })).toHaveAttribute('href', '/admin/users?kind=people&q=bo');
    expect(screen.getByRole('link', { name: 'All' })).toHaveAttribute('href', '/admin/users?q=bo');
  });

  it('applies the filter form to the URL and refetches', async () => {
    const user = userEvent.setup();
    const { router } = renderPeople('/admin/users');
    await screen.findByRole('link', { name: 'Ada L' });
    await user.type(screen.getByRole('searchbox', { name: 'Username, name or email' }), 'rae');
    await user.click(screen.getByRole('button', { name: 'Filter' }));
    await waitFor(() => { expect(router.state.location.search).toBe('?q=rae'); });
    await waitFor(() => { expect(screen.queryByRole('link', { name: 'Ada L' })).toBeNull(); });
    expect(screen.getByRole('link', { name: 'Rae' })).toBeInTheDocument();
  });

  it('shows the empty state when nothing matches', async () => {
    renderPeople('/admin/users?q=zzz');
    expect(await screen.findByText('No accounts match.')).toHaveClass('empty-state-title');
    expect(screen.getByText('Clear the filter or pick another tab to see more accounts.')).toBeInTheDocument();
  });

  it('shows a refused list in place', async () => {
    server.use(http.get(`${API}/admin/users`, () => problem(403, 'forbidden', 'The list could not be read.')));
    renderPeople('/admin/users');
    expect(await screen.findByRole('alert')).toHaveTextContent('The list could not be read.');
  });

  it('pages through every page, not just the first', async () => {
    server.use(http.get(`${API}/admin/users`, ({ request }) => {
      const offset = Number(new URL(request.url).searchParams.get('offset') ?? '0');
      return HttpResponse.json(offset === 0
        ? { items: [accounts[0]], offset: 0, limit: 1, hasMore: true }
        : { items: [accounts[2]], offset: 1, limit: 1, hasMore: false });
    }));
    renderPeople('/admin/users');
    expect(await screen.findByRole('link', { name: 'Rae' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ada L' })).toBeInTheDocument();
  });

  it('keeps a non-admin out: no screen, and the API is never asked', async () => {
    renderPeople('/admin/users', editorBoot);
    expect(await screen.findByText('That page does not exist.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
    expect(seen).toEqual([]);
  });
});
