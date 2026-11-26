import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';

export const API = 'http://pressroom.test/pressroom/api/v1';

export const me = {
  userName: 'jake', screenName: 'Jake', kind: 'PERSON', globalAdmin: false, tokenScope: {},
  weblogs: [{ handle: 'alpha', name: 'Alpha Blog', permission: 'POST' }, { handle: 'site-space', name: 'Site', permission: 'POST' }]
};
export const meta = {
  version: '1.2.3', siteUrl: 'https://blog.example.com', features: { customThemes: true, groupBlogging: true }, limits: {}
};
const weblogs: Record<string, object> = {
  alpha: { handle: 'alpha', name: 'Alpha Blog', active: true, timeZone: 'UTC', frontPage: 'p1' },
  'site-space': { handle: 'site-space', name: 'Site', active: true, timeZone: 'UTC' }
};

export const page = (over: Record<string, unknown> = {}) => ({
  id: 'p2', slug: 'about', title: 'About us', text: 'Hello **world**', status: 'PUBLISHED', showInNav: true, navOrder: 1,
  contentHash: 'h1', updateTime: '2026-11-20T10:00:00Z', strand: false, parts: [], noindex: false, ...over
});

export interface Recorded {
  method: string;
  url: string;
  body: unknown;
}

/** The recorded writes, in order, for a test to assert on. */
export const writes: Recorded[] = [];

export const problem = (status: number, code: string, detail: string, errors?: object[]) =>
  HttpResponse.json({ code, detail, status, ...(errors === undefined ? {} : { errors }) },
    { status, headers: { 'content-type': 'application/problem+json' } });

const record = async (request: Request) => {
  writes.push({ method: request.method, url: new URL(request.url).pathname.replace('/pressroom/api/v1', ''),
    body: request.headers.get('content-type')?.includes('json') === true ? await request.clone().json() as unknown : null });
};

/** The handlers every pages test starts from; a test overrides with server.use. */
export function baseHandlers() {
  return [
    http.get(`${API}/me`, () => HttpResponse.json(me)),
    http.get(`${API}/meta`, () => HttpResponse.json(meta)),
    http.get(`${API}/weblogs/:handle`, ({ params }) => HttpResponse.json(weblogs[String(params['handle'])] ?? weblogs['alpha'])),
    http.get(`${API}/weblogs/:handle/pages/:id/revisions`, () => HttpResponse.json([])),
    http.get(`${API}/weblogs/:handle/pages`, ({ request }) =>
      HttpResponse.json(new URL(request.url).searchParams.get('status') === 'TRASHED' ? [] : [
        page({ id: 'p1', slug: 'home', title: 'Welcome', navOrder: 0, showInNav: false }),
        page(),
        page({ id: 'p3', slug: 'draft-one', title: 'Draft <b>one</b>', status: 'DRAFT', navOrder: 2, showInNav: false })
      ]))
  ];
}

export function makeServer() {
  const server = setupServer(...baseHandlers());
  return {
    server,
    start() {
      beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
      afterEach(() => { server.resetHandlers(...baseHandlers()); writes.length = 0; window.localStorage.clear(); });
      afterAll(() => { server.close(); });
    }
  };
}

/** A write handler that records the request then answers. */
export const writing = (
  method: 'post' | 'patch' | 'put' | 'delete', path: string, answer: (request: Request) => Response | Promise<Response>
) => http[method](`${API}${path}`, async ({ request }) => {
  await record(request);
  return answer(request);
});
