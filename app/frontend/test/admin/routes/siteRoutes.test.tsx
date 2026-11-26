import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { appRoutes } from '../../../src/admin/router';
import { adminBoot, editorBoot, renderRoutes } from '../support';

const API = 'http://pressroom.test/pressroom/api/v1';
const server = setupServer(
  http.get(`${API}/me`, () => HttpResponse.json({ userName: 'jake', screenName: 'Jake', kind: 'PERSON', globalAdmin: true, tokenScope: {}, weblogs: [] })),
  http.get(`${API}/meta`, () => HttpResponse.json({ version: '1', features: { customThemes: true, groupBlogging: true }, limits: {} })),
  http.get(`${API}/admin/businesses`, () => HttpResponse.json({ items: [], offset: 0, limit: 200, hasMore: false, total: 0 }))
);
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });

describe('site admin routes in the app', () => {
  it('serve a site admin inside the frame, with the Server admin rail', async () => {
    renderRoutes(appRoutes, '/admin/businesses', { boot: adminBoot });
    expect(await screen.findByRole('heading', { level: 1, name: 'Businesses' })).toBeInTheDocument();
    expect(await screen.findByText('Server admin', { selector: '.rail-group-label' })).toBeInTheDocument();
  });

  it('give an editor the not-found page, never the screen', async () => {
    renderRoutes(appRoutes, '/admin/businesses', { boot: editorBoot });
    expect(await screen.findByText('That page does not exist.')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 1, name: 'Businesses' })).not.toBeInTheDocument();
  });
});
