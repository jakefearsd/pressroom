import { renderHook, waitFor } from '@testing-library/react';
import type { UseQueryResult } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { AppProviders } from '../../../src/admin/context';
import { useWeblog, useWeblogBusinesses, useWeblogPages, useWeblogThemes } from '../../../src/admin/api/queries';
import { useAgent, useAllWeblogs, useSiteTokens, useUser, useUserWeblogs, useUsers } from '../../../src/admin/screens/people/queries';
import { ApiError } from '../../../src/admin/api/problem';
import { editorBoot } from '../support';

/** A fake network: answers by path (and offset), remembers each URL it was asked for. */
function network(answers: Record<string, (url: URL) => { status?: number; body: unknown }>) {
  const seen: string[] = [];
  const fetchImpl = ((input: Request | string) => {
    const request = typeof input === 'string' ? new Request(input) : input;
    const url = new URL(request.url);
    seen.push(url.pathname.replace('/pressroom/api/v1', '') + url.search);
    const answer = answers[url.pathname.replace('/pressroom/api/v1', '')];
    const { status = 200, body } = answer === undefined ? { status: 404, body: { status: 404, code: 'nope', detail: 'no' } } : answer(url);
    return Promise.resolve(new Response(JSON.stringify(body), {
      status, headers: { 'Content-Type': status === 200 ? 'application/json' : 'application/problem+json' }
    }));
  }) as typeof fetch;
  const wrapper = ({ children }: { children: ReactNode }) => <AppProviders boot={editorBoot} fetchImpl={fetchImpl}>{children}</AppProviders>;
  return { seen, wrapper };
}

type Hook = () => UseQueryResult<unknown>;
type Row = [string, Hook, Hook, string];

describe('weblog queries stay idle without a handle', () => {
  it.each<Row>([
    ['useWeblog', () => useWeblog(undefined), () => useWeblog('alpha'), '/weblogs/alpha'],
    ['useWeblogThemes', () => useWeblogThemes(undefined), () => useWeblogThemes('alpha'), '/weblogs/alpha/themes'],
    ['useWeblogBusinesses', () => useWeblogBusinesses(undefined), () => useWeblogBusinesses('alpha'), '/weblogs/alpha/businesses'],
    ['useWeblogPages', () => useWeblogPages(undefined), () => useWeblogPages('alpha'), '/weblogs/alpha/pages']
  ])('%s asks nothing until it has a handle, then reads that weblog', async (_name, idle, active, path) => {
    const net = network({ [path]: () => ({ body: { handle: 'alpha' } }) });
    const quiet = renderHook(idle, { wrapper: net.wrapper });
    expect(quiet.result.current.fetchStatus).toBe('idle');
    expect(net.seen).toEqual([]);
    const live = renderHook(active, { wrapper: net.wrapper });
    await waitFor(() => { expect(live.result.current.data).toBeDefined(); });
    expect(net.seen[0]).toContain(path);
  });

  it('reads the site admin theme route for the site space, whatever the handle', async () => {
    const net = network({ '/admin/site-theme': () => ({ body: { shared: [] } }) });
    const { result } = renderHook(() => useWeblogThemes('site', true), { wrapper: net.wrapper });
    await waitFor(() => { expect(result.current.data).toBeDefined(); });
    expect(net.seen).toEqual(['/admin/site-theme']);
  });
});

describe('account queries', () => {
  it.each<Row>([
    ['useUser', () => useUser(undefined), () => useUser('ann'), '/admin/users/ann'],
    ['useUserWeblogs', () => useUserWeblogs(undefined), () => useUserWeblogs('ann'), '/admin/users/ann/weblogs'],
    ['useAgent', () => useAgent(undefined), () => useAgent('bot'), '/admin/agents/bot']
  ])('%s is idle without a user name and reads that account otherwise', async (_name, idle, active, path) => {
    const net = network({ [path]: () => ({ body: { items: [], userName: 'x' } }) });
    expect(renderHook(idle, { wrapper: net.wrapper }).result.current.fetchStatus).toBe('idle');
    expect(net.seen).toEqual([]);
    const live = renderHook(active, { wrapper: net.wrapper });
    await waitFor(() => { expect(live.result.current.data).toBeDefined(); });
    expect(net.seen).toEqual([path]);
  });

  it('reads a missing account as null, but surfaces any other failure', async () => {
    const missing = network({ '/admin/users/ghost': () => ({ status: 404, body: { status: 404, code: 'user.notFound', detail: 'gone' } }) });
    const ghost = renderHook(() => useUser('ghost'), { wrapper: missing.wrapper });
    await waitFor(() => { expect(ghost.result.current.data).toBeNull(); });

    const broken = network({ '/admin/users/ann': () => ({ status: 403, body: { status: 403, code: 'forbidden', detail: 'no' } }) });
    const ann = renderHook(() => useUser('ann'), { wrapper: broken.wrapper });
    await waitFor(() => { expect(ann.result.current.isError).toBe(true); });
    expect(ann.result.current.error).toBeInstanceOf(ApiError);
    expect((ann.result.current.error as ApiError).status).toBe(403);
  });

  it('pages through every account, 200 at a time, passing the filters', async () => {
    const net = network({
      '/admin/users': (url) => url.searchParams.get('offset') === '0'
        ? { body: { items: [{ userName: 'a' }], hasMore: true } }
        : { body: { items: [{ userName: 'b' }], hasMore: false } }
    });
    const { result } = renderHook(() => useUsers('agents', 'bo'), { wrapper: net.wrapper });
    await waitFor(() => { expect(result.current.data).toEqual([{ userName: 'a' }, { userName: 'b' }]); });
    expect(net.seen).toHaveLength(2);
    expect(net.seen[0]).toContain('kind=agents');
    expect(net.seen[0]).toContain('q=bo');
    expect(net.seen[1]).toContain('offset=200');
  });

  it('pages through every weblog, and stays idle when told to', async () => {
    const net = network({
      '/weblogs': (url) => url.searchParams.get('offset') === '0'
        ? { body: { items: [{ handle: 'a' }], hasMore: true } }
        : { body: { items: [{ handle: 'b' }], hasMore: false } }
    });
    expect(renderHook(() => useAllWeblogs(false), { wrapper: net.wrapper }).result.current.fetchStatus).toBe('idle');
    const { result } = renderHook(() => useAllWeblogs(), { wrapper: net.wrapper });
    await waitFor(() => { expect(result.current.data).toEqual([{ handle: 'a' }, { handle: 'b' }]); });
    expect(net.seen).toHaveLength(2);
  });

  it('lists tokens, as an empty list when the server names none', async () => {
    const net = network({ '/admin/tokens': () => ({ body: {} }) });
    const { result } = renderHook(() => useSiteTokens(true), { wrapper: net.wrapper });
    await waitFor(() => { expect(result.current.data).toEqual([]); });
    expect(net.seen[0]).toContain('all=true');
  });
});
