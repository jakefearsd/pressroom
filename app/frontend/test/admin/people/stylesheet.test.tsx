import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { API, open, problem, recordCalls, resetWorld, server, world, writes } from './harness';

let view: Record<string, unknown>;
const own = { exists: true, customTheme: false, sharedThemeStylesheet: true, contents: 'body{}', lastModified: '2026-01-01T00:00:00Z' };

beforeAll(() => { recordCalls(); server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => {
  resetWorld();
  window.localStorage.clear();
  view = own;
  server.use(http.get(`${API}/weblogs/alpha/stylesheet`, () => HttpResponse.json(view)));
});

describe('Stylesheet screen', () => {
  it('saves the edited CSS and says so', async () => {
    const user = userEvent.setup();
    server.use(http.put(`${API}/weblogs/alpha/stylesheet`, async ({ request }) => {
      const body = await request.json() as { contents: string };
      return HttpResponse.json({ ...own, contents: body.contents, lastModified: '2026-02-02T00:00:00Z' });
    }));
    open('/weblogs/alpha/stylesheet');
    const source = await screen.findByLabelText('Stylesheet source');
    expect(source).toHaveValue('body{}');
    expect(screen.getByText('You can customize!')).toBeInTheDocument();
    await user.clear(source);
    await user.type(source, 'p{{}');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Stylesheet saved.')).toBeInTheDocument();
    expect(writes()).toMatchObject([{ method: 'PUT', path: '/weblogs/alpha/stylesheet', body: { contents: 'p{}' }, csrf: 'tok' }]);
    expect(screen.getByLabelText('Stylesheet source')).toHaveValue('p{}');
  });

  it('reverts only after confirmation, and shows the reverted CSS', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/weblogs/alpha/stylesheet/revert`, () =>
      HttpResponse.json({ ...own, contents: 'default{}', lastModified: '2026-03-03T00:00:00Z' })));
    open('/weblogs/alpha/stylesheet');
    await user.click(await screen.findByRole('button', { name: 'Revert' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText("Revert your stylesheet to the theme's default?")).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(writes()).toEqual([]);
    await user.click(screen.getByRole('button', { name: 'Revert' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Revert' }));
    expect(await screen.findByText('Stylesheet reverted.')).toBeInTheDocument();
    expect(writes()).toMatchObject([{ method: 'POST', path: '/weblogs/alpha/stylesheet/revert' }]);
    expect(screen.getByLabelText('Stylesheet source')).toHaveValue('default{}');
  });

  it('deletes after confirmation and falls back to the copy step', async () => {
    const user = userEvent.setup();
    server.use(http.delete(`${API}/weblogs/alpha/stylesheet`, () => {
      view = { exists: false, customTheme: false, sharedThemeStylesheet: true };
      return new HttpResponse(null, { status: 204 });
    }));
    open('/weblogs/alpha/stylesheet');
    await user.click(await screen.findByRole('button', { name: 'Delete' }));
    expect(screen.getByText("Delete your stylesheet and use the theme's default?")).toBeInTheDocument();
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText("Stylesheet deleted. The theme's default stylesheet is back in use.")).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Copy stylesheet' })).toBeInTheDocument();
    expect(writes()).toMatchObject([{ method: 'DELETE', path: '/weblogs/alpha/stylesheet' }]);
  });

  it('offers the copy step on a shared theme with a stylesheet of its own, then the editor', async () => {
    const user = userEvent.setup();
    view = { exists: false, customTheme: false, sharedThemeStylesheet: true };
    server.use(http.post(`${API}/weblogs/alpha/stylesheet/copy-from-shared`, () => HttpResponse.json(own)));
    open('/weblogs/alpha/stylesheet');
    expect(await screen.findByText(/Would you like to copy that stylesheet/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Stylesheet source')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Copy stylesheet' }));
    expect(await screen.findByText('Custom stylesheet created.')).toBeInTheDocument();
    expect(await screen.findByLabelText('Stylesheet source')).toHaveValue('body{}');
    expect(writes()).toMatchObject([{ method: 'POST', path: '/weblogs/alpha/stylesheet/copy-from-shared' }]);
  });

  it('says a shared theme without a stylesheet cannot be customised', async () => {
    view = { exists: false, customTheme: false, sharedThemeStylesheet: false };
    open('/weblogs/alpha/stylesheet');
    expect(await screen.findByText(/does not provide a customizable stylesheet\./)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Copy stylesheet' })).toBeNull();
  });

  it('on a custom theme offers neither revert nor delete, and without a stylesheet says why', async () => {
    view = { exists: true, customTheme: true, sharedThemeStylesheet: false, contents: 'x{}', lastModified: 'a' };
    const first = open('/weblogs/alpha/stylesheet');
    await screen.findByLabelText('Stylesheet source');
    expect(screen.queryByRole('button', { name: 'Revert' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull();
    first.unmount();
    view = { exists: false, customTheme: true, sharedThemeStylesheet: false };
    open('/weblogs/alpha/stylesheet');
    expect(await screen.findByText(/since you are using a Custom Theme/)).toBeInTheDocument();
  });

  it('shows refusals in place: a save with no stylesheet, a revert on a custom theme, an empty-CSS field error', async () => {
    const user = userEvent.setup();
    server.use(
      http.put(`${API}/weblogs/alpha/stylesheet`, () =>
        problem(400, 'request.invalidFields', 'contents is required.', [{ field: 'contents', message: 'contents is required.' }])),
      http.post(`${API}/weblogs/alpha/stylesheet/revert`, () =>
        problem(409, 'stylesheet.customTheme', 'Only a shared theme can be reverted.'))
    );
    open('/weblogs/alpha/stylesheet');
    await user.click(await screen.findByRole('button', { name: 'Save' }));
    const source = screen.getByLabelText('Stylesheet source');
    expect(await screen.findByText('contents is required.')).toBeInTheDocument();
    expect(source).toHaveAttribute('aria-invalid', 'true');
    expect(source).toHaveFocus();
    expect(source).toHaveValue('body{}');
    await user.click(screen.getByRole('button', { name: 'Revert' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Revert' }));
    expect(await screen.findByText('Only a shared theme can be reverted.')).toBeInTheDocument();
  });

  it('shows a save that lost its stylesheet (404) in the banner', async () => {
    const user = userEvent.setup();
    server.use(http.put(`${API}/weblogs/alpha/stylesheet`, () => problem(404, 'stylesheet.notFound', 'This weblog has no stylesheet of its own yet.')));
    open('/weblogs/alpha/stylesheet');
    await user.click(await screen.findByRole('button', { name: 'Save' }));
    expect(await screen.findByText('This weblog has no stylesheet of its own yet.')).toBeInTheDocument();
  });

  it('is not there for an author', async () => {
    world.permission = 'POST';
    open('/weblogs/alpha/stylesheet');
    expect(await screen.findByText('That page does not exist.')).toBeInTheDocument();
  });
});
