import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { screen } from '@testing-library/react';
import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { renderInApp } from '../support';
import { peopleRoutes } from '../../../src/admin/screens/people/routes';
import { adminBoot, renderRoutes } from '../support';
import type { Bootstrap } from '../../../src/admin/bootstrap';

export const API = 'http://pressroom.test/pressroom/api/v1';

/** A request the fake server saw: method, path (with query) and parsed JSON body. */
export interface Seen { method: string; path: string; body: unknown }
export const seen: Seen[] = [];

export const problem = (status: number, code: string, detail: string, errors?: { field: string; message: string }[]) =>
  HttpResponse.json({ code, detail, ...(errors ? { errors } : {}) }, { status });

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

export const server = setupServer();

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
