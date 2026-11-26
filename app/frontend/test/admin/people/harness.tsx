import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { appRoutes } from '../../../src/admin/router';
import { adminBoot, editorBoot, renderRoutes } from '../support';

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
