import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { createApiClient } from '../../src/admin/api/client';
import type { Bootstrap } from '../../src/admin/bootstrap';
import { sample } from './fixtures';

const boot: Bootstrap = { ...sample, contextPath: 'http://pressroom.test/pressroom' };
const seen: { method: string; csrf: string | null; url: string }[] = [];
const server = setupServer(
  http.all('http://pressroom.test/pressroom/api/*', ({ request }) => {
    seen.push({ method: request.method, csrf: request.headers.get('X-CSRF-TOKEN'), url: request.url });
    return HttpResponse.json({});
  })
);

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => { seen.length = 0; });
afterAll(() => server.close());

describe('createApiClient', () => {
  it('sends no CSRF header on a read and the named header on a write, under the context path', async () => {
    const client = createApiClient(boot);

    await client.GET('/v1/me');
    await client.DELETE('/v1/tokens/{id}', { params: { path: { id: 't1' } } });

    expect(seen.map((s) => [s.method, s.csrf, s.url])).toEqual([
      ['GET', null, 'http://pressroom.test/pressroom/api/v1/me'],
      ['DELETE', 'tok', 'http://pressroom.test/pressroom/api/v1/tokens/t1']
    ]);
  });

  it('uses an injected fetch when given one', async () => {
    const calls: string[] = [];
    const client = createApiClient(boot, (input) => {
      calls.push((input as Request).url);
      return Promise.resolve(new Response('{}', { headers: { 'content-type': 'application/json' } }));
    });

    await client.GET('/v1/me');

    expect(calls).toEqual(['http://pressroom.test/pressroom/api/v1/me']);
  });
});
