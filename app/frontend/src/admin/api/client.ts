import createClient, { type Middleware } from 'openapi-fetch';
import type { Bootstrap } from '../bootstrap';
import { toApiError } from './problem';
import type { paths } from './schema';

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

/**
 * The typed /api/v1 client. The session cookie authenticates (same origin);
 * every unsafe method carries the CSRF header the shell named; any 401 is
 * reported to `onUnauthorized` (the session-expired treatment).
 */
export function createApiClient(boot: Bootstrap, fetchImpl?: typeof fetch, onUnauthorized?: () => void) {
  const middleware: Middleware = {
    onRequest({ request }) {
      if (!SAFE_METHODS.has(request.method)) {
        request.headers.set(boot.csrf.headerName, boot.csrf.token);
      }
      return request;
    },
    onResponse({ response }) {
      if (response.status === 401) {
        onUnauthorized?.();
      }
      return response;
    }
  };
  const client = createClient<paths>({
    baseUrl: `${boot.contextPath}/api`,
    credentials: 'same-origin',
    ...(fetchImpl ? { fetch: fetchImpl } : {})
  });
  client.use(middleware);
  return client;
}

export type ApiClient = ReturnType<typeof createApiClient>;

/** What every openapi-fetch call resolves to. */
interface Result<T> {
  data?: T;
  error?: unknown;
  response: Response;
}

/** Unwraps a client call: the data on success, a thrown ApiError (problem+json parsed) otherwise. */
export async function call<T>(request: Promise<Result<T>>): Promise<T> {
  const { data, error, response } = await request;
  if (!response.ok) {
    throw toApiError(response.status, error);
  }
  return data as T;
}
