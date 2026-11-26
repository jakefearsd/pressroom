import createClient, { type Middleware } from 'openapi-fetch';
import type { Bootstrap } from '../bootstrap';
import type { paths } from './schema';

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

/**
 * The typed /api/v1 client. The session cookie authenticates (same origin);
 * every unsafe method carries the CSRF header the shell named.
 */
export function createApiClient(boot: Bootstrap, fetchImpl?: typeof fetch) {
  const csrf: Middleware = {
    onRequest({ request }) {
      if (!SAFE_METHODS.has(request.method)) {
        request.headers.set(boot.csrf.headerName, boot.csrf.token);
      }
      return request;
    }
  };
  const client = createClient<paths>({
    baseUrl: `${boot.contextPath}/api`,
    credentials: 'same-origin',
    ...(fetchImpl ? { fetch: fetchImpl } : {})
  });
  client.use(csrf);
  return client;
}
