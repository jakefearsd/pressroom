import { screen } from '@testing-library/react';
import { appRoutes } from '../../../src/admin/router';
import { adminBoot, editorBoot, renderRoutes } from '../support';
import { API, fakeServer, handlers, seen, server } from './harness';

const { http, HttpResponse } = handlers;

fakeServer();
beforeEach(() => {
  server.use(
    http.get(`${API}/me`, () => HttpResponse.json({ userName: 'jake', screenName: 'Jake', kind: 'PERSON', globalAdmin: true, tokenScope: {}, weblogs: [] })),
    http.get(`${API}/meta`, () => HttpResponse.json({ features: { customThemes: true, groupBlogging: true }, limits: {} })),
    http.get(`${API}/admin/users`, () => HttpResponse.json({ items: [], offset: 0, limit: 200, hasMore: false })),
    http.get(`${API}/me/profile`, () => HttpResponse.json({ userName: 'jake', kind: 'PERSON' })),
    http.get(`${API}/meta/weblog-options`, () => HttpResponse.json({ themes: [], locales: [], timeZones: [] }))
  );
});

describe('People routes in the app', () => {
  it('serves Users inside the frame with the site-admin rail', async () => {
    renderRoutes(appRoutes, '/admin/users', { boot: adminBoot });
    expect(await screen.findByRole('heading', { level: 1, name: 'Users' })).toHaveClass('pressroom-page-title');
    expect(document.getElementById('adminRail')).not.toBeNull();
    expect(await screen.findByText('No accounts match.')).toBeInTheDocument();
  });

  it('serves Profile inside the frame for an editor, with no weblog rail', async () => {
    renderRoutes(appRoutes, '/profile', { boot: editorBoot });
    expect(await screen.findByRole('heading', { level: 1, name: 'Your profile' })).toBeInTheDocument();
    expect(document.querySelectorAll('#adminRail .rail-group')).toHaveLength(0);
  });

  it('never reaches the user list for an editor typing the URL', async () => {
    renderRoutes(appRoutes, '/admin/users', { boot: editorBoot });
    expect(await screen.findByText('That page does not exist.')).toBeInTheDocument();
    expect(seen.some((s) => s.path.startsWith('/admin/users'))).toBe(false);
  });
});
