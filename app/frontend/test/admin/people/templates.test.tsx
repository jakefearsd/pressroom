import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { API, calls, open, problem, recordCalls, resetWorld, server, world, writes } from './harness';

const templates = [
  { id: 't1', name: 'Weblog', action: 'WEBLOG', description: 'The front page', required: true, hidden: false, navbar: false },
  { id: 't2', name: "Maiia's Sidebar", action: 'CUSTOM', description: '<b>side</b>', required: false, hidden: true, navbar: false, link: 'side' }
];
let list: { items: object[]; availableActions: string[] };

beforeAll(() => { recordCalls(); server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => {
  resetWorld();
  list = { items: [...templates], availableActions: ['CUSTOM', 'SEARCH'] };
  server.use(http.get(`${API}/weblogs/alpha/templates`, () => HttpResponse.json(list)));
});

const row = (name: string) => screen.getByRole('link', { name }).closest('tr') as HTMLElement;

describe('Templates screen', () => {
  it('lists templates with action names, escaped descriptions, a hidden marker and a lock on required ones of a custom theme', async () => {
    open('/weblogs/alpha/templates');
    expect(await screen.findByRole('heading', { level: 1, name: 'Templates' })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: "Maiia's Sidebar" })).toHaveAttribute('href', expect.stringContaining('/weblogs/alpha/templates/t2'));
    expect(within(row('Weblog')).getByText('Weblog', { selector: 'td:nth-child(2)' })).toBeInTheDocument();
    expect(within(row("Maiia's Sidebar")).getByText('<b>side</b>')).toBeInTheDocument();
    expect(within(row("Maiia's Sidebar")).getByRole('img', { name: 'Hidden' })).toBeInTheDocument();
    expect(within(row('Weblog')).getByRole('img', { name: 'This template cannot be deleted' })).toBeInTheDocument();
    expect(within(row('Weblog')).queryByRole('button')).toBeNull();
    expect(screen.queryByText(/Reminder:/)).toBeNull();
    expect(screen.getByText('alpha', { selector: 'p.subtitle b' })).toBeInTheDocument();
  });

  it('reminds a shared-theme weblog that template edits may not show, and lets it delete a required template', async () => {
    world.weblog = { ...world.weblog, theme: 'journal' };
    open('/weblogs/alpha/templates');
    expect(await screen.findByText(/Reminder:/)).toBeInTheDocument();
    await screen.findByRole('link', { name: 'Weblog' });
    expect(within(row('Weblog')).getByRole('button', { name: 'Delete: Weblog' })).toBeInTheDocument();
  });

  it('asks before deleting, names the template, and does nothing on cancel', async () => {
    const user = userEvent.setup();
    open('/weblogs/alpha/templates');
    await user.click(await screen.findByRole('button', { name: "Delete: Maiia's Sidebar" }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText("Delete this template: 'Maiia's Sidebar'?")).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(writes()).toEqual([]);
  });

  it('deletes after confirmation and says so', async () => {
    const user = userEvent.setup();
    server.use(http.delete(`${API}/weblogs/alpha/templates/t2`, () => {
      list = { ...list, items: [templates[0] as object] };
      return new HttpResponse(null, { status: 204 });
    }));
    open('/weblogs/alpha/templates');
    await user.click(await screen.findByRole('button', { name: "Delete: Maiia's Sidebar" }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('Template "Maiia\'s Sidebar" deleted.')).toBeInTheDocument();
    expect(writes()).toMatchObject([{ method: 'DELETE', path: '/weblogs/alpha/templates/t2', csrf: 'tok' }]);
    await waitFor(() => { expect(screen.queryByRole('link', { name: "Maiia's Sidebar" })).toBeNull(); });
  });

  it('shows a refused delete in place', async () => {
    const user = userEvent.setup();
    world.weblog = { ...world.weblog, theme: 'journal' };
    server.use(http.delete(`${API}/weblogs/alpha/templates/t1`, () =>
      problem(409, 'template.required', 'A required template cannot be removed.')));
    open('/weblogs/alpha/templates');
    await user.click(await screen.findByRole('button', { name: 'Delete: Weblog' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('A required template cannot be removed.')).toBeInTheDocument();
  });

  it('adds a template with the chosen action and clears the name', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/weblogs/alpha/templates`, () =>
      HttpResponse.json({ id: 't3', name: 'Sitemap', action: 'SEARCH', required: false, hidden: false, navbar: false }, { status: 201 })));
    open('/weblogs/alpha/templates');
    const name = await screen.findByLabelText('Name');
    await user.type(name, 'Sitemap');
    await user.selectOptions(screen.getByLabelText('Action'), 'SEARCH');
    await user.click(screen.getByRole('button', { name: 'Add' }));
    expect(await screen.findByText('Template "Sitemap" created.')).toBeInTheDocument();
    expect(writes()[0]).toMatchObject({ method: 'POST', body: { name: 'Sitemap', action: 'SEARCH' } });
    expect(name).toHaveValue('');
  });

  it('puts a refused add on the field: marked, focused, message in the banner', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/weblogs/alpha/templates`, () =>
      problem(409, 'template.nameInUse', 'The template Sitemap already exists.',
        [{ field: 'name', message: 'The template Sitemap already exists.' }])));
    open('/weblogs/alpha/templates');
    const name = await screen.findByLabelText('Name');
    await user.type(name, 'Sitemap');
    await user.click(screen.getByRole('button', { name: 'Add' }));
    await waitFor(() => { expect(name).toHaveAttribute('aria-invalid', 'true'); });
    expect(name).toHaveClass('is-invalid');
    // focus moves in an effect after the commit that marks the field: wait for it too
    await waitFor(() => { expect(name).toHaveFocus(); });
    expect(name).toHaveValue('Sitemap');
    expect(screen.getAllByText('The template Sitemap already exists.')).toHaveLength(1);
    expect(screen.getByLabelText('Action')).not.toHaveAttribute('aria-invalid');
  });

  it('shows the empty state and no action select when nothing may be added', async () => {
    list = { items: [], availableActions: [] };
    open('/weblogs/alpha/templates');
    expect(await screen.findByText('No templates yet.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Action')).toBeNull();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('is not there for an author, and is for a site admin who is no member', async () => {
    world.permission = 'POST';
    const first = open('/weblogs/alpha/templates');
    expect(await screen.findByText('That page does not exist.')).toBeInTheDocument();
    expect(calls.some((c) => c.path.endsWith('/templates'))).toBe(false);
    first.unmount();
    world.permission = '';
    world.globalAdmin = true;
    open('/weblogs/alpha/templates');
    expect(await screen.findByRole('link', { name: 'Weblog' })).toBeInTheDocument();
  });
});
