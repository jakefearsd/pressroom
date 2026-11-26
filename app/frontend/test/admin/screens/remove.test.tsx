import '@testing-library/jest-dom/vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { appRoutes } from '../../../src/admin/router';
import { adminBoot, editorBoot, renderRoutes } from '../support';
import { API, alpha, me, meta, problem, record, type Seen } from './weblogFixtures';

let seen: Seen[];
let who: object;
let refuse: ReturnType<typeof problem> | null;
const server = setupServer(
  http.get(`${API}/me`, () => HttpResponse.json(who)),
  http.get(`${API}/meta`, () => HttpResponse.json(meta())),
  http.get(`${API}/weblogs/alpha`, () => HttpResponse.json({ ...alpha, name: 'Alpha <i>Blog</i>' })),
  http.delete(`${API}/weblogs/alpha`, async ({ request }) => {
    await record(seen, request);
    return refuse ?? new HttpResponse(null, { status: 204 });
  })
);
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => {
  seen = [];
  who = me();
  refuse = null;
});

describe('RemoveWeblog', () => {
  it('asks, names the weblog as text, warns, and offers cancel back to the settings', async () => {
    renderRoutes(appRoutes, '/weblogs/alpha/remove', { boot: editorBoot });
    expect(await screen.findByText('Alpha <i>Blog</i>', { selector: 'b' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Remove weblog' })).toBeInTheDocument();
    expect(screen.getByText('This cannot be undone.')).toBeInTheDocument();
    expect(document.querySelector('.warning')).toHaveTextContent(/Removing a weblog deletes everything in it/);
    expect(screen.getByRole('link', { name: 'Cancel' })).toHaveAttribute('href', '/weblogs/alpha/settings');
    expect(seen).toHaveLength(0);
  });

  it('removes on the button, sends DELETE with CSRF, and lands on the app home with the message', async () => {
    const user = userEvent.setup();
    const { router } = renderRoutes(appRoutes, '/weblogs/alpha/remove', { boot: adminBoot });
    await user.click(await screen.findByRole('button', { name: 'Remove weblog' }));
    await waitFor(() => { expect(router.state.location.pathname).toBe('/'); });
    expect(seen[0]).toEqual({ method: 'DELETE', path: '/weblogs/alpha', csrf: 'tok', body: null });
    expect(await screen.findByText('Weblog "Alpha <i>Blog</i>" removed.')).toBeInTheDocument();
  });

  it('shows a refusal in place and stays on the page', async () => {
    const user = userEvent.setup();
    refuse = problem('auth.forbidden', 'Only a weblog admin may remove it.', undefined, 403);
    const { router } = renderRoutes(appRoutes, '/weblogs/alpha/remove', { boot: editorBoot });
    await user.click(await screen.findByRole('button', { name: 'Remove weblog' }));
    expect(await screen.findByText('Only a weblog admin may remove it.')).toBeInTheDocument();
    expect(screen.getByText(/could not be removed/)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/weblogs/alpha/remove');
  });

  it('refuses a member who is not a weblog admin, and a weblog that cannot be read', async () => {
    who = me('POST');
    const view = renderRoutes(appRoutes, '/weblogs/alpha/remove', { boot: editorBoot });
    expect(await screen.findByText('Access denied.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove weblog' })).toBeNull();
    view.unmount();
    who = me();
    server.use(http.get(`${API}/weblogs/alpha`, () => problem('weblog.notFound', 'No such weblog.', undefined, 404)));
    renderRoutes(appRoutes, '/weblogs/alpha/remove', { boot: editorBoot });
    expect((await screen.findAllByText('No such weblog.')).length).toBeGreaterThan(0);
  });
});
