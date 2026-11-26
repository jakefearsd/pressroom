import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { AppProviders } from '../../../src/admin/context';
import { useBlogTraffic, useWeblogBusinesses } from '../../../src/admin/api/queries';
import { useComments, useEntryList, useTrash } from '../../../src/admin/api/listQueries';
import { useMediaList } from '../../../src/admin/api/media';
import { useMembers } from '../../../src/admin/api/people';
import { usePage, usePageRevisions, usePages, useStrandCandidates } from '../../../src/admin/api/pages';
import { useBusinesses } from '../../../src/admin/api/siteQueries';
import { useAllWeblogs, useUsers } from '../../../src/admin/screens/people/queries';
import { editorBoot } from '../support';

/** What each query asks the server for: the request each hook builds from what the screen passes it. */
type Answer = (url: URL) => unknown;
function network(answer: Answer) {
  const seen: URL[] = [];
  const fetchImpl = ((input: Request | string) => {
    const url = new URL((typeof input === 'string' ? new Request(input) : input).url);
    seen.push(url);
    return Promise.resolve(new Response(JSON.stringify(answer(url)), { status: 200, headers: { 'Content-Type': 'application/json' } }));
  }) as typeof fetch;
  const wrapper = ({ children }: { children: ReactNode }) => <AppProviders boot={editorBoot} fetchImpl={fetchImpl}>{children}</AppProviders>;
  return { seen, wrapper };
}
const empty = () => ({ items: [], hasMore: false, offset: 0, limit: 30 });
const params = (url: URL) => Object.fromEntries([...url.searchParams.keys()].map((k) => [k, url.searchParams.getAll(k)]));
const settle = async (get: () => { isSuccess: boolean }) => { await waitFor(() => { expect(get().isSuccess).toBe(true); }); };

describe('the entry list asks only for the filters that are set', () => {
  it('sends just paging and the fields for no filters, and leaves out an ALL status, blanks and an empty tag list', async () => {
    const net = network(empty);
    const { result } = renderHook(() => useEntryList('alpha', { status: 'ALL', category: '', tags: [], text: '', startDate: '', endDate: '', sortBy: '', offset: 0 }),
      { wrapper: net.wrapper });
    await settle(() => result.current);
    const asked = params(net.seen[0] as URL);
    expect(Object.keys(asked).sort()).toEqual(['fields', 'limit', 'offset']);
    expect(asked['limit']).toEqual(['30']);
    expect(asked['fields']).not.toContain('views30d');
  });

  it('sends every filter that has a value, and the views column on request', async () => {
    const net = network(empty);
    const { result } = renderHook(() => useEntryList('alpha', { status: 'DRAFT', category: 'Trips', tags: ['a', 'b'], text: 'rome',
      startDate: '2026-01-01', endDate: '2026-02-01', sortBy: 'UPDATE_TIME', offset: 60 }, true), { wrapper: net.wrapper });
    await settle(() => result.current);
    expect(params(net.seen[0] as URL)).toMatchObject({ status: ['DRAFT'], category: ['Trips'], tags: ['a', 'b'], text: ['rome'],
      startDate: ['2026-01-01'], endDate: ['2026-02-01'], sortBy: ['UPDATE_TIME'], offset: ['60'] });
    expect(params(net.seen[0] as URL)['fields']).toContain('views30d');
  });

  it('treats an empty status like no status', async () => {
    const net = network(empty);
    const { result } = renderHook(() => useEntryList('alpha', { status: '', offset: 0 }), { wrapper: net.wrapper });
    await settle(() => result.current);
    expect(params(net.seen[0] as URL)).not.toHaveProperty('status');
  });
});

describe('the comment list', () => {
  it('names an entry only when one is given', async () => {
    const net = network(empty);
    const blank = renderHook(() => useComments('alpha', { status: 'PENDING', entryId: '', offset: 0 }), { wrapper: net.wrapper });
    await settle(() => blank.result.current);
    expect(params(net.seen[0] as URL)).not.toHaveProperty('entryId');
    const one = renderHook(() => useComments('alpha', { status: 'PENDING', entryId: 'e1', offset: 30 }), { wrapper: net.wrapper });
    await settle(() => one.result.current);
    expect(params(net.seen[1] as URL)).toMatchObject({ entryId: ['e1'], status: ['PENDING'], offset: ['30'] });
  });
});

describe('reading every page of a long list', () => {
  it('moves the trash on by the number of entries it got, and stops on an empty page even if told there is more', async () => {
    const net = network((url) => {
      const offset = Number(url.searchParams.get('offset'));
      return offset === 0 ? { items: [{ id: 'a' }, { id: 'b' }], hasMore: true }
        : offset === 2 ? { items: [{ id: 'c' }], hasMore: true } : { items: [], hasMore: true };
    });
    const { result } = renderHook(() => useTrash('alpha'), { wrapper: net.wrapper });
    await waitFor(() => { expect(result.current.data).toEqual([{ id: 'a' }, { id: 'b' }, { id: 'c' }]); });
    expect(net.seen.map((u) => u.searchParams.get('offset'))).toEqual(['0', '2', '3']);
  });

  it('does the same for members, in steps of the page size', async () => {
    const net = network((url) => (url.searchParams.get('offset') === '0' ? { items: [{ userName: 'a' }], hasMore: true } : { items: [], hasMore: true }));
    const { result } = renderHook(() => useMembers('alpha'), { wrapper: net.wrapper });
    await waitFor(() => { expect(result.current.data).toEqual([{ userName: 'a' }]); });
    expect(net.seen.map((u) => u.searchParams.get('offset'))).toEqual(['0', '200']);
  });

  it('asks for the weblogs and accounts 200 at a time, moving forward each page, and names only the filters given', async () => {
    const blogs = network((url) => ({ items: [{ handle: url.searchParams.get('offset') ?? '' }], hasMore: url.searchParams.get('offset') === '0' }));
    const all = renderHook(() => useAllWeblogs(), { wrapper: blogs.wrapper });
    await waitFor(() => { expect(all.result.current.data).toEqual([{ handle: '0' }, { handle: '200' }]); });

    const users = network(() => ({ items: [], hasMore: false }));
    const { result } = renderHook(() => useUsers(undefined, ''), { wrapper: users.wrapper });
    await settle(() => result.current);
    expect(params(users.seen[0] as URL)).toEqual({ limit: ['200'], offset: ['0'] });
  });
});

describe('page queries', () => {
  it('reads the live pages with no status, the trash with one, and stays idle without an id', async () => {
    const net = network(() => []);
    const live = renderHook(() => usePages('alpha'), { wrapper: net.wrapper });
    const trash = renderHook(() => usePages('alpha', 'TRASHED'), { wrapper: net.wrapper });
    await settle(() => live.result.current);
    await settle(() => trash.result.current);
    expect(net.seen.map((u) => u.search).sort()).toEqual(['', '?status=TRASHED']);
    expect(renderHook(() => usePage('alpha', undefined), { wrapper: net.wrapper }).result.current.fetchStatus).toBe('idle');
    expect(renderHook(() => usePageRevisions('alpha', undefined), { wrapper: net.wrapper }).result.current.fetchStatus).toBe('idle');
    expect(net.seen).toHaveLength(2);
  });

  it('offers strand candidates by the newest, or by a trimmed search', async () => {
    const net = network(empty);
    const plain = renderHook(() => useStrandCandidates('alpha', true, '   '), { wrapper: net.wrapper });
    await settle(() => plain.result.current);
    expect(params(net.seen[0] as URL)).not.toHaveProperty('text');
    expect(params(net.seen[0] as URL)).toMatchObject({ limit: ['20'], fields: ['title', 'status', 'strand'] });
    const found = renderHook(() => useStrandCandidates('alpha', true, '  rome '), { wrapper: net.wrapper });
    await settle(() => found.result.current);
    expect(params(net.seen[1] as URL)).toMatchObject({ text: ['rome'] });
    expect(renderHook(() => useStrandCandidates('alpha', false, ''), { wrapper: net.wrapper }).result.current.fetchStatus).toBe('idle');
  });
});

describe('media, traffic and business reads', () => {
  it('lists media with only the filters that mean something', async () => {
    const net = network(() => []);
    const { result } = renderHook(() => useMediaList('alpha', { directoryId: '', q: undefined, type: 'image', sort: '', tags: [], largerThan: 0 }),
      { wrapper: net.wrapper });
    await settle(() => result.current);
    expect(params(net.seen[0] as URL)).toEqual({ type: ['image'], largerThan: ['0'] });
    const tagged = renderHook(() => useMediaList('alpha', { tags: ['x', 'y'], directoryId: 'd1' }), { wrapper: net.wrapper });
    await settle(() => tagged.result.current);
    expect(params(net.seen[1] as URL)).toEqual({ directoryId: ['d1'], tags: ['x', 'y'] });
  });

  it('reads one blog\'s traffic for a period from the route that serves it', async () => {
    const net = network(() => ({}));
    const own = renderHook(() => useBlogTraffic('alpha', '30d', false), { wrapper: net.wrapper });
    const admin = renderHook(() => useBlogTraffic('alpha', '7d', true), { wrapper: net.wrapper });
    await settle(() => own.result.current);
    await settle(() => admin.result.current);
    const byPath = Object.fromEntries(net.seen.map((u) => [u.pathname.replace('/pressroom/api/v1', ''), u.search]));
    expect(byPath).toEqual({ '/weblogs/alpha/traffic': '?period=30d', '/admin/traffic/alpha': '?period=7d' });
  });

  it('asks for up to 200 businesses', async () => {
    const net = network(empty);
    const all = renderHook(() => useBusinesses(), { wrapper: net.wrapper });
    const mine = renderHook(() => useWeblogBusinesses('alpha'), { wrapper: net.wrapper });
    await settle(() => all.result.current);
    await settle(() => mine.result.current);
    expect(net.seen.map((u) => u.searchParams.get('limit'))).toEqual(['200', '200']);
  });
});
