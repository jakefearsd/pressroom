import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { screen } from '@testing-library/react';
import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { appRoutes } from '../../../src/admin/router';
import { peopleRoutes } from '../../../src/admin/screens/people/routes';
import type { Bootstrap } from '../../../src/admin/bootstrap';
import { adminBoot, editorBoot, renderInApp, renderRoutes } from '../support';

export const API = 'http://pressroom.test/pressroom/api/v1';

export interface Call {
  method: string;
  path: string;
  search: string;
  body: unknown;
  csrf: string | null;
}

export const calls: Call[] = [];

export interface World {
  permission: string;
  globalAdmin: boolean;
  features: { customThemes: boolean; groupBlogging: boolean };
  weblog: Record<string, unknown>;
}

export const world: World = {
  permission: 'ADMIN', globalAdmin: false, features: { customThemes: true, groupBlogging: true }, weblog: {}
};

export function resetWorld(): void {
  calls.length = 0;
  world.permission = 'ADMIN';
  world.globalAdmin = false;
  world.features = { customThemes: true, groupBlogging: true };
  world.weblog = { handle: 'alpha', name: 'Alpha Blog', active: true, timeZone: 'Europe/Berlin', theme: 'custom',
    absoluteUrl: 'https://elsewhere.example/blogs/alpha/' };
}

/** The server every people-screen test shares: me, meta and the weblog, plus whatever the test adds. */
export const server = setupServer(
  http.get(`${API}/me`, () => HttpResponse.json({
    userName: 'jake', screenName: 'Jake', kind: 'PERSON', globalAdmin: world.globalAdmin, tokenScope: {},
    weblogs: world.permission === '' ? [] : [{ handle: 'alpha', name: 'Alpha Blog', permission: world.permission }]
  })),
  http.get(`${API}/meta`, () => HttpResponse.json({
    version: '1.2.3', siteUrl: 'https://blog.example.com', features: world.features, limits: {}
  })),
  http.get(`${API}/me/weblogs`, () => HttpResponse.json({ weblogs: [] })),
  http.get(`${API}/weblogs/:handle`, () => HttpResponse.json(world.weblog))
);

/** Records every request to the API (method, path, query, JSON body, CSRF header) for assertions. */
export function recordCalls(): void {
  server.events.on('request:start', async ({ request }) => {
    const url = new URL(request.url);
    const text = await request.clone().text();
    calls.push({
      method: request.method, path: url.pathname.replace('/pressroom/api/v1', ''), search: url.search,
      body: text === '' ? undefined : (JSON.parse(text) as unknown), csrf: request.headers.get('X-CSRF-TOKEN')
    });
  });
}

export const problem = (status: number, code: string, detail: string, errors?: { field: string; message: string }[]) =>
  HttpResponse.json({ status, code, detail, ...(errors === undefined ? {} : { errors }) },
    { status, headers: { 'Content-Type': 'application/problem+json' } });

export const writes = (): Call[] => calls.filter((c) => c.method !== 'GET');

export function open(path: string, boot = editorBoot) {
  return renderRoutes(appRoutes, path, { boot });
}

export { adminBoot, editorBoot };

// ---- the people-screen tests that stub individual endpoints and record them as `seen` ----

/** A request the fake server saw: method, path (with query) and parsed JSON body. */
export interface Seen { method: string; path: string; body: unknown }
export const seen: Seen[] = [];

/** Records every request, then lets the test's own handler answer it. */
export function recording<T extends Parameters<typeof http.get>[1]>(handler: T): T {
  return (async (info: Parameters<T>[0]) => {
    const url = new URL(info.request.url);
    const text = info.request.method === 'GET' || info.request.method === 'DELETE' ? '' : await info.request.clone().text();
    seen.push({ method: info.request.method, path: url.pathname.replace('/pressroom/api/v1', '') + url.search,
      body: text === '' ? undefined : JSON.parse(text) });
    return handler(info);
  }) as T;
}

/** The setup the unit-style people tests share: the world's server, plus the recorder their handlers go through. */
export function fakeServer(): void {
  beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
  beforeEach(() => { seen.length = 0; window.localStorage.clear(); });
  afterEach(() => { server.resetHandlers(); });
  afterAll(() => { server.close(); });
}

export const handlers = { http, HttpResponse };

export function renderPeople(at: string, boot: Bootstrap = adminBoot) {
  return renderRoutes([...peopleRoutes, { path: '*', element: <p>elsewhere</p> }], at, { boot });
}

export const heading = () => screen.findByRole('heading', { level: 1 });

/** Reaches the QueryClient the app's providers made, so a test can look inside its caches. */
const probe: { client?: QueryClient } = {};
function CacheProbe() {
  const client = useQueryClient();
  useEffect(() => { probe.client = client; }, [client]);
  return null;
}

/** Everything the query and mutation caches hold, as text: a secret that leaked into them is in it. */
export function cacheText(): string {
  const client = probe.client;
  if (client === undefined) {
    throw new Error('renderPeopleWithCache first');
  }
  return JSON.stringify({
    queries: client.getQueryCache().getAll().map((q) => q.state.data),
    mutations: client.getMutationCache().getAll().map((m) => m.state.data)
  });
}

export function renderPeopleWithCache(at: string, boot: Bootstrap = adminBoot) {
  const router = createMemoryRouter([...peopleRoutes, { path: '*', element: <p>elsewhere</p> }], { initialEntries: [at] });
  return { router, ...renderInApp(<><RouterProvider router={router} /><CacheProbe /></>, { boot }) };
}
