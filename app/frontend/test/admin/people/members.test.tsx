import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { API, open, problem, recordCalls, resetWorld, server, world, writes } from './harness';

let members: { userName: string; screenName: string; kind: string; permission: string }[];
const member = (userName: string, permission: string) => ({ userName, screenName: userName, kind: 'PERSON', permission });

beforeAll(() => { recordCalls(); server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => {
  resetWorld();
  members = [member('jake', 'ADMIN'), member('maiia', 'POST'), member('lee', 'EDIT_DRAFT')];
  server.use(http.get(`${API}/weblogs/alpha/members`, () =>
    HttpResponse.json({ items: members, offset: 0, limit: 200, hasMore: false, total: members.length })));
});

const radio = (level: string, who: string) => screen.getByRole('radio', { name: `${level}: ${who}` });

describe('Members screen', () => {
  it('shows each member at their level, and only the admin radio live on your own row', async () => {
    open('/weblogs/alpha/members');
    expect(await screen.findByRole('heading', { level: 1, name: 'Members' })).toBeInTheDocument();
    await screen.findByText('maiia');
    expect(radio('Author', 'maiia')).toBeChecked();
    expect(radio('Limited', 'lee')).toBeChecked();
    expect(radio('Admin', 'jake')).toBeChecked();
    expect(radio('Admin', 'jake')).toBeEnabled();
    for (const level of ['Author', 'Limited', 'Remove']) {
      expect(radio(level, 'jake')).toBeDisabled();
    }
    expect(radio('Remove', 'maiia')).toBeEnabled();
  });

  it('says there is nothing to save when nothing changed, without calling the API', async () => {
    const user = userEvent.setup();
    open('/weblogs/alpha/members');
    await screen.findByText('maiia');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('No changes to save.')).toBeInTheDocument();
    expect(writes()).toEqual([]);
  });

  it('changes levels with one PATCH per changed member', async () => {
    const user = userEvent.setup();
    server.use(http.patch(`${API}/weblogs/alpha/members/:name`, async ({ params, request }) =>
      HttpResponse.json(member(String(params['name']), ((await request.json()) as { permission: string }).permission))));
    open('/weblogs/alpha/members');
    await screen.findByText('maiia');
    await user.click(radio('Limited', 'maiia'));
    await user.click(radio('Author', 'lee'));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Members changed: 2.')).toBeInTheDocument();
    expect(writes().map((c) => [c.method, c.path, c.body, c.csrf]).sort()).toEqual([
      ['PATCH', '/weblogs/alpha/members/lee', { permission: 'POST' }, 'tok'],
      ['PATCH', '/weblogs/alpha/members/maiia', { permission: 'EDIT_DRAFT' }, 'tok']
    ]);
  });

  it('asks once for the whole table when anyone is to be removed, and removes only after the answer', async () => {
    const user = userEvent.setup();
    server.use(http.delete(`${API}/weblogs/alpha/members/:name`, () => new HttpResponse(null, { status: 204 })));
    open('/weblogs/alpha/members');
    await screen.findByText('maiia');
    await user.click(radio('Remove', 'maiia'));
    await user.click(radio('Remove', 'lee'));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Remove the selected members from this weblog?')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(writes()).toEqual([]);
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove' }));
    expect(await screen.findByText('Members removed: 2.')).toBeInTheDocument();
    expect(writes().map((c) => c.path).sort()).toEqual(['/weblogs/alpha/members/lee', '/weblogs/alpha/members/maiia']);
  });

  it('does not ask when only levels change', async () => {
    const user = userEvent.setup();
    server.use(http.patch(`${API}/weblogs/alpha/members/:name`, () => HttpResponse.json(member('maiia', 'EDIT_DRAFT'))));
    open('/weblogs/alpha/members');
    await screen.findByText('maiia');
    await user.click(radio('Limited', 'maiia'));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    await screen.findByText('Members changed: 1.');
  });

  it('promotes before it demotes, and refuses a table that ends with no admin', async () => {
    const user = userEvent.setup();
    world.globalAdmin = true;
    members = [member('zed', 'ADMIN'), member('maiia', 'POST')];
    server.use(http.patch(`${API}/weblogs/alpha/members/:name`, async ({ params, request }) =>
      HttpResponse.json(member(String(params['name']), ((await request.json()) as { permission: string }).permission))));
    open('/weblogs/alpha/members');
    await screen.findByText('maiia');
    await user.click(radio('Limited', 'zed'));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('A weblog must have at least one admin.')).toBeInTheDocument();
    expect(writes()).toEqual([]);
    await user.click(radio('Admin', 'maiia'));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('Members changed: 2.');
    expect(writes().map((c) => c.path)).toEqual(['/weblogs/alpha/members/maiia', '/weblogs/alpha/members/zed']);
  });

  it('reports each member the server refused, and still applies the rest', async () => {
    const user = userEvent.setup();
    server.use(http.patch(`${API}/weblogs/alpha/members/:name`, async ({ params }) =>
      params['name'] === 'lee' ? problem(409, 'member.lastAdmin', 'The change would leave no admin.')
        : HttpResponse.json(member('maiia', 'EDIT_DRAFT'))));
    open('/weblogs/alpha/members');
    await screen.findByText('maiia');
    await user.click(radio('Limited', 'maiia'));
    await user.click(radio('Author', 'lee'));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('lee: The change would leave no admin.')).toBeInTheDocument();
    expect(screen.getByText('Members changed: 1.')).toBeInTheDocument();
  });

  it('grants access and clears the field', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/weblogs/alpha/members`, () => HttpResponse.json(member('newbie', 'EDIT_DRAFT'), { status: 201 })));
    open('/weblogs/alpha/members');
    const name = await screen.findByLabelText('Username');
    expect(screen.getByLabelText('Permission')).toHaveValue('POST');
    await user.type(name, 'newbie');
    await user.selectOptions(screen.getByLabelText('Permission'), 'EDIT_DRAFT');
    await user.click(screen.getByRole('button', { name: 'Add member' }));
    expect(await screen.findByText('Permissions updated for newbie.')).toBeInTheDocument();
    expect(writes()).toMatchObject([{ method: 'POST', path: '/weblogs/alpha/members', body: { userName: 'newbie', permission: 'EDIT_DRAFT' } }]);
    expect(name).toHaveValue('');
  });

  it('puts an unknown user on the Username field', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/weblogs/alpha/members`, () =>
      problem(400, 'member.userUnknown', 'No such user.', [{ field: 'userName', message: 'No such user.' }])));
    open('/weblogs/alpha/members');
    const name = await screen.findByLabelText('Username');
    await user.type(name, 'nobody');
    await user.click(screen.getByRole('button', { name: 'Add member' }));
    await waitFor(() => { expect(name).toHaveFocus(); });
    expect(name).toHaveAttribute('aria-invalid', 'true');
    expect(name).toHaveValue('nobody');
    expect(screen.getByText('No such user.')).toBeInTheDocument();
  });

  it('hides the add form when group blogging is off, but keeps the table to revoke with', async () => {
    world.features = { customThemes: true, groupBlogging: false };
    open('/weblogs/alpha/members');
    await screen.findByText('maiia');
    expect(screen.queryByLabelText('Username')).toBeNull();
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument();
  });

  it('invites the first member when there are none', async () => {
    members = [];
    open('/weblogs/alpha/members');
    expect(await screen.findByText('Just you so far.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('pages through every member', async () => {
    const seen: string[] = [];
    server.use(http.get(`${API}/weblogs/alpha/members`, ({ request }) => {
      const offset = Number(new URL(request.url).searchParams.get('offset'));
      seen.push(String(offset));
      return HttpResponse.json(offset === 0
        ? { items: [member('jake', 'ADMIN')], offset: 0, limit: 200, hasMore: true, total: 2 }
        : { items: [member('zed', 'POST')], offset, limit: 200, hasMore: false, total: 2 });
    }));
    open('/weblogs/alpha/members');
    expect(await screen.findByText('zed')).toBeInTheDocument();
    expect(seen).toEqual(['0', '200']);
  });

  it('is not there for an author', async () => {
    world.permission = 'POST';
    open('/weblogs/alpha/members');
    expect(await screen.findByText('That page does not exist.')).toBeInTheDocument();
  });
});
