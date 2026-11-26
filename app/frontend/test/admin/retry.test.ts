import { ApiError } from '../../src/admin/api/problem';
import { retryable } from '../../src/admin/context';

// The screen tests answer errors with a 4xx so they never wait out the backoff; this pins the rule itself.
describe('the query retry rule', () => {
  it('never retries an answer the server meant (4xx)', () => {
    expect(retryable(0, new ApiError(404, 'weblog.notFound', '', []))).toBe(false);
    expect(retryable(0, new ApiError(409, 'entry.stale', '', []))).toBe(false);
    expect(retryable(0, new ApiError(499, 'x', '', []))).toBe(false);
  });

  it('retries a server failure (5xx) twice, then gives up', () => {
    const failure = new ApiError(500, 'server', '', []);
    expect(retryable(0, failure)).toBe(true);
    expect(retryable(1, failure)).toBe(true);
    expect(retryable(2, failure)).toBe(false);
  });

  it('retries a network failure, which is not an API answer, the same two times', () => {
    expect(retryable(1, new TypeError('Failed to fetch'))).toBe(true);
    expect(retryable(2, new TypeError('Failed to fetch'))).toBe(false);
  });
});
