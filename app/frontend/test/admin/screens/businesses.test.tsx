import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { siteAdminRoutes } from '../../../src/admin/routes/siteRoutes';
import { adminBoot, editorBoot, renderRoutes } from '../support';

const API = 'http://pressroom.test/pressroom/api/v1';
const acme = { id: 'b1', name: 'Acme <Travel>', businessType: 'LocalBusiness', weblogCount: 2, sameAs: [] };
const solo = { id: 'b2', name: 'Solo', businessType: 'Organization', weblogCount: 0, sameAs: ['https://a.example', 'https://b.example'],
  websiteUrl: 'https://solo.example', description: 'Does things' };
let listed: object[];
let sent: { method: string; url: string; body: unknown; csrf: string | null }[];

const server = setupServer(
  http.get(`${API}/admin/businesses`, () => HttpResponse.json({ items: listed, offset: 0, limit: 200, hasMore: false, total: listed.length })),
  http.get(`${API}/admin/businesses/:id`, ({ params }) => {
    const found = listed.find((b) => (b as { id: string }).id === params['id']);
    return found === undefined ? HttpResponse.json({ code: 'business.notFound', detail: 'That business does not exist.' }, { status: 404 })
      : HttpResponse.json(found);
  })
);
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => { listed = [acme, solo]; sent = []; });

const record = async (request: Request) => {
  const text = await request.text();
  sent.push({ method: request.method, url: new URL(request.url).pathname, body: text === '' ? null : JSON.parse(text) as unknown,
    csrf: request.headers.get('X-CSRF-TOKEN') });
};

describe('Businesses list', () => {
  it('lists each business with its type, its usage and edit and delete actions', async () => {
    renderRoutes(siteAdminRoutes, '/admin/businesses', { boot: adminBoot });
    expect(await screen.findByRole('heading', { level: 1, name: 'Businesses' })).toBeInTheDocument();
    await screen.findByText('Acme <Travel>');
    expect(screen.getByText(/A business is stored once/)).toHaveClass('subtitle');
    expect(document.querySelectorAll('#businesses-list-marker table.pressroomtable tr')).toHaveLength(3);
    const row = screen.getByText('Acme <Travel>').closest('tr') as HTMLElement;
    expect(within(row).getByText('Local business')).toBeInTheDocument();
    expect(within(row).getByText('2')).toBeInTheDocument();
    expect(within(row).getByRole('link', { name: 'Edit: Acme <Travel>' })).toHaveAttribute('href', '/admin/businesses/b1');
    expect(within(row).getByRole('button', { name: 'Delete: Acme <Travel>' })).toHaveClass('row-action-danger');
    expect(screen.getByRole('link', { name: 'New business' })).toHaveAttribute('id', 'business-new');
    expect(document.title).toBe('Pressroom: Businesses');
  });

  it('invites the first business when there is none', async () => {
    listed = [];
    renderRoutes(siteAdminRoutes, '/admin/businesses', { boot: adminBoot });
    expect(await screen.findByText('No businesses yet.')).toHaveClass('empty-state-title');
    expect(screen.getByRole('link', { name: 'New business' })).toHaveAttribute('href', '/admin/businesses/new');
  });

  it('confirms by name, deletes, and reports it', async () => {
    server.use(http.delete(`${API}/admin/businesses/:id`, async ({ request }) => {
      await record(request);
      listed = [acme];
      return new HttpResponse(null, { status: 204 });
    }));
    const user = userEvent.setup();
    renderRoutes(siteAdminRoutes, '/admin/businesses', { boot: adminBoot });
    await user.click(await screen.findByRole('button', { name: 'Delete: Solo' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Delete the business "Solo"? This cannot be undone.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('Business "Solo" deleted.')).toBeInTheDocument();
    await waitFor(() => { expect(screen.queryByText('Solo')).not.toBeInTheDocument(); });
    expect(sent).toEqual([{ method: 'DELETE', url: '/pressroom/api/v1/admin/businesses/b2', body: null, csrf: 'tok' }]);
  });

  it('does not delete when the confirmation is cancelled', async () => {
    const user = userEvent.setup();
    renderRoutes(siteAdminRoutes, '/admin/businesses', { boot: adminBoot });
    await user.click(await screen.findByRole('button', { name: 'Delete: Solo' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(sent).toEqual([]);
  });

  it('shows a refused delete (in use) in place and keeps the row', async () => {
    server.use(http.delete(`${API}/admin/businesses/:id`, () => HttpResponse.json(
      { code: 'business.inUse', detail: 'Business "Acme" is used by 2 weblogs and cannot be deleted.' }, { status: 409 })));
    const user = userEvent.setup();
    renderRoutes(siteAdminRoutes, '/admin/businesses', { boot: adminBoot });
    await user.click(await screen.findByRole('button', { name: 'Delete: Acme <Travel>' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText(/is used by 2 weblogs/)).toBeInTheDocument();
    expect(screen.getByText('Acme <Travel>')).toBeInTheDocument();
  });

  it('shows a list that cannot load as the problem', async () => {
    server.use(http.get(`${API}/admin/businesses`, () => HttpResponse.json({ code: 'x', detail: 'Cannot read.' }, { status: 500 })));
    renderRoutes(siteAdminRoutes, '/admin/businesses', { boot: adminBoot });
    expect(await screen.findByText('Cannot read.', undefined, { timeout: 4000 })).toBeInTheDocument();
  });

  it('is not offered to an editor', async () => {
    renderRoutes(siteAdminRoutes, '/admin/businesses', { boot: editorBoot });
    expect(await screen.findByText('That page does not exist.')).toBeInTheDocument();
    expect(screen.queryByText('Businesses')).not.toBeInTheDocument();
  });
});

describe('Business edit', () => {
  it('creates a business: required name, type default, sameAs one per line, then the list with a message', async () => {
    server.use(http.post(`${API}/admin/businesses`, async ({ request }) => {
      await record(request);
      listed = [acme];
      return HttpResponse.json({ ...acme, id: 'b9' }, { status: 201 });
    }));
    const user = userEvent.setup();
    renderRoutes(siteAdminRoutes, '/admin/businesses/new', { boot: adminBoot });
    expect(await screen.findByText('New business', { selector: 'p.subtitle' })).toBeInTheDocument();
    expect(screen.getByLabelText('Name')).toBeRequired();
    expect(screen.getByLabelText('Type')).toHaveValue('Organization');
    expect(screen.getByText('One http or https address per line, up to 10. Social profiles and listings that identify this business.'))
      .toHaveClass('form-text');
    await user.type(screen.getByLabelText('Name'), 'New Co');
    await user.selectOptions(screen.getByLabelText('Type'), 'ProfessionalService');
    await user.type(screen.getByLabelText('Website'), 'https://new.example');
    await user.type(screen.getByLabelText('Profile links'), 'https://x.example{Enter}  https://y.example  {Enter}');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Business saved.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Businesses' })).toBeInTheDocument();
    expect(sent).toEqual([{ method: 'POST', url: '/pressroom/api/v1/admin/businesses', csrf: 'tok', body: {
      name: 'New Co', businessType: 'ProfessionalService', websiteUrl: 'https://new.example',
      sameAs: ['https://x.example', 'https://y.example'] } }]);
  });

  it('edits: fills the form, and every field left empty is sent in clear (blank means none, as the form did)', async () => {
    server.use(http.patch(`${API}/admin/businesses/:id`, async ({ request }) => {
      await record(request);
      return HttpResponse.json(solo);
    }));
    const user = userEvent.setup();
    renderRoutes(siteAdminRoutes, '/admin/businesses/b2', { boot: adminBoot });
    expect(await screen.findByText('Edit business', { selector: 'p.subtitle' })).toBeInTheDocument();
    await waitFor(() => { expect(screen.getByLabelText('Name')).toHaveValue('Solo'); });
    expect(screen.getByLabelText('Profile links')).toHaveValue('https://a.example\nhttps://b.example');
    expect(screen.getByLabelText('Description')).toHaveValue('Does things');
    await user.clear(screen.getByLabelText('Website'));
    await user.clear(screen.getByLabelText('Profile links'));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('Business saved.');
    expect(sent).toEqual([{ method: 'PATCH', url: '/pressroom/api/v1/admin/businesses/b2', csrf: 'tok', body: {
      name: 'Solo', businessType: 'Organization', description: 'Does things',
      clear: ['websiteUrl', 'bookingUrl', 'telephone', 'email', 'logoUrl', 'areaServed', 'sameAs'] } }]);
  });

  it('puts the server message on the refused field, focused, and stays on the form', async () => {
    server.use(http.post(`${API}/admin/businesses`, () => HttpResponse.json({
      code: 'validation.failed', detail: 'Invalid.', errors: [{ field: 'websiteUrl', message: 'Website must be a public, full http or https address.' }]
    }, { status: 400 })));
    const user = userEvent.setup();
    renderRoutes(siteAdminRoutes, '/admin/businesses/new', { boot: adminBoot });
    await user.type(await screen.findByLabelText('Name'), 'X');
    await user.type(screen.getByLabelText('Website'), 'http://localhost');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    const field = await screen.findByLabelText('Website');
    await waitFor(() => { expect(field).toHaveAttribute('aria-invalid', 'true'); });
    expect(field).toHaveClass('is-invalid');
    expect(field).toHaveFocus();
    expect(screen.getAllByText('Website must be a public, full http or https address.').length).toBeGreaterThan(0);
    expect(screen.queryByText('Business saved.')).not.toBeInTheDocument();
  });

  it('cancels back to the list', async () => {
    renderRoutes(siteAdminRoutes, '/admin/businesses/new', { boot: adminBoot });
    expect(await screen.findByRole('link', { name: 'Cancel' })).toHaveAttribute('href', '/admin/businesses');
  });

  it('says so when the business does not exist', async () => {
    renderRoutes(siteAdminRoutes, '/admin/businesses/nope', { boot: adminBoot });
    expect(await screen.findByText('That business does not exist.')).toBeInTheDocument();
  });

  it('is not offered to an editor', async () => {
    renderRoutes(siteAdminRoutes, '/admin/businesses/new', { boot: editorBoot });
    expect(await screen.findByText('That page does not exist.')).toBeInTheDocument();
  });
});
