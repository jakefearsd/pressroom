import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { AppProviders } from '../../../src/admin/context';
import { useMediaActions, useMediaList, useDirectories } from '../../../src/admin/api/media';
import {
  useCreateTemplate, useDeleteTemplate, useMemberActions, useMembers, useStylesheet, useStylesheetActions,
  useSubmissionActions, useSubmissions, useTemplate, useTemplates, useUpdateTemplate
} from '../../../src/admin/api/people';
import {
  useAnalyticsStatus, useBusiness, useBusinesses, useConfig, useSyncAnalytics, useDeleteBusiness, useRevisionStats, useSitePages, useSaveBusiness, useSaveConfig, useSaveSettings, useSweepRevisions
} from '../../../src/admin/api/siteQueries';
import { useMeta } from '../../../src/admin/api/queries';
import { editorBoot } from '../support';

/**
 * A list must show the new state after a change without a reload. The fake server's data carries a
 * version that goes up with every write, so a list read after a write is visibly newer than before it;
 * `gets` counts the reads of each path, so a list that should not have been touched is caught too.
 * (The hooks are driven directly: every screen calls these same hooks and shows what they return.)
 */
function world(failWrites = false) {
  let version = 0;
  const gets: Record<string, number> = {};
  const fetchImpl = ((input: Request | string) => {
    const request = typeof input === 'string' ? new Request(input) : input;
    const path = new URL(request.url).pathname.replace('/pressroom/api/v1', '');
    if (request.method === 'GET') {
      gets[path] = (gets[path] ?? 0) + 1;
      return Promise.resolve(new Response(JSON.stringify({ v: version, items: [], hasMore: false, contents: 'c' }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }));
    }
    if (failWrites) {
      return Promise.resolve(new Response(JSON.stringify({ status: 500, code: 'x', detail: 'no' }),
        { status: 500, headers: { 'Content-Type': 'application/problem+json' } }));
    }
    version += 1;
    return Promise.resolve(new Response(JSON.stringify({ v: version, id: 'new1' }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
  }) as typeof fetch;
  const wrapper = ({ children }: { children: ReactNode }) => <AppProviders boot={editorBoot} fetchImpl={fetchImpl}>{children}</AppProviders>;
  return { gets, wrapper };
}
const versionOf = (data: unknown) => (data as { v: number } | undefined)?.v;
const loaded = async (get: () => unknown) => { await waitFor(() => { expect(get()).toBeDefined(); }); };

describe('lists refresh after a change', () => {
  it.each([
    ['creating a template', (h: ReturnType<typeof useCreateTemplate>) => h.mutateAsync({ name: 'n', action: 'a' }), () => useCreateTemplate('alpha')],
    ['deleting a template', (h: ReturnType<typeof useDeleteTemplate>) => h.mutateAsync('t1'), () => useDeleteTemplate('alpha')]
  ] as const)('the template list after %s, and no other blog\'s list', async (_name, act1, hook) => {
    const w = world();
    const view = renderHook(() => ({ alpha: useTemplates('alpha'), beta: useTemplates('beta'), change: (hook as () => never)() }), { wrapper: w.wrapper });
    await loaded(() => view.result.current.alpha.data);
    await loaded(() => view.result.current.beta.data);
    await act(async () => { await (act1 as (h: unknown) => Promise<unknown>)(view.result.current.change); });
    await waitFor(() => { expect(versionOf(view.result.current.alpha.data)).toBe(1); });
    expect(w.gets['/weblogs/alpha/templates']).toBe(2);
    expect(w.gets['/weblogs/beta/templates']).toBe(1);
  });

  it('the template list after an edit, and the edited template as the server answered it', async () => {
    const w = world();
    const view = renderHook(() => ({ list: useTemplates('alpha'), one: useTemplate('alpha', 't1'), other: useTemplates('beta'), save: useUpdateTemplate('alpha', 't1') }),
      { wrapper: w.wrapper });
    await loaded(() => view.result.current.list.data);
    await loaded(() => view.result.current.other.data);
    await act(async () => { await view.result.current.save.mutateAsync({} as never); });
    await waitFor(() => { expect(versionOf(view.result.current.list.data)).toBe(1); });
    expect(versionOf(view.result.current.one.data)).toBe(1);
    expect(w.gets['/weblogs/alpha/templates/t1']).toBe(1);
    expect(w.gets['/weblogs/beta/templates']).toBe(1);
  });

  it('the stylesheet after it is removed', async () => {
    const w = world();
    const view = renderHook(() => ({ sheet: useStylesheet('alpha'), actions: useStylesheetActions('alpha'), other: useStylesheet('beta') }), { wrapper: w.wrapper });
    await loaded(() => view.result.current.sheet.data);
    await loaded(() => view.result.current.other.data);
    await act(async () => { await view.result.current.actions.remove.mutateAsync(); });
    await waitFor(() => { expect(versionOf(view.result.current.sheet.data)).toBe(1); });
    expect(w.gets['/weblogs/beta/stylesheet']).toBe(1);
  });

  it('the member list after a grant, and when a screen asks for a refresh, but not another blog\'s', async () => {
    const w = world();
    const view = renderHook(() => ({ members: useMembers('alpha'), other: useMembers('beta'), actions: useMemberActions('alpha') }), { wrapper: w.wrapper });
    await loaded(() => view.result.current.members.data);
    await loaded(() => view.result.current.other.data);
    await act(async () => { await view.result.current.actions.grant.mutateAsync({ userName: 'ann', permission: 'POST' } as never); });
    await waitFor(() => { expect(w.gets['/weblogs/alpha/members']).toBe(2); });
    await act(async () => { await view.result.current.actions.refresh(); });
    await waitFor(() => { expect(w.gets['/weblogs/alpha/members']).toBe(3); });
    expect(w.gets['/weblogs/beta/members']).toBe(1);
  });

  it('the submissions after a removal and a refresh, but not another blog\'s', async () => {
    const w = world();
    const view = renderHook(() => ({ list: useSubmissions('alpha', 0), other: useSubmissions('beta', 0), actions: useSubmissionActions('alpha') }), { wrapper: w.wrapper });
    await loaded(() => view.result.current.list.data);
    await loaded(() => view.result.current.other.data);
    await act(async () => { await view.result.current.actions.remove('s1'); await view.result.current.actions.refresh(); });
    await waitFor(() => { expect(versionOf(view.result.current.list.data)).toBe(1); });
    expect(w.gets['/weblogs/beta/submissions']).toBe(1);
  });

  it('the media files and folders after a run, but not another blog\'s', async () => {
    const w = world();
    const view = renderHook(() => ({ files: useMediaList('alpha', {}), dirs: useDirectories('alpha'), other: useDirectories('beta'), actions: useMediaActions('alpha') }),
      { wrapper: w.wrapper });
    await loaded(() => view.result.current.files.data);
    await loaded(() => view.result.current.dirs.data);
    await loaded(() => view.result.current.other.data);
    await act(async () => { await view.result.current.actions.remove('m1'); await view.result.current.actions.refresh(); });
    await waitFor(() => { expect(versionOf(view.result.current.files.data)).toBe(1); });
    await waitFor(() => { expect(versionOf(view.result.current.dirs.data)).toBe(1); });
    expect(w.gets['/weblogs/beta/media/directories']).toBe(1);
  });
});

describe('site lists refresh after a change', () => {
  it.each([['creating', true], ['updating', false]])('the businesses after %s one', async (_name, create) => {
    const w = world();
    const view = renderHook(() => ({ list: useBusinesses(), save: useSaveBusiness(create ? undefined : 'b1'), other: useRevisionStats() }), { wrapper: w.wrapper });
    await loaded(() => view.result.current.list.data);
    await loaded(() => view.result.current.other.data);
    await act(async () => { await view.result.current.save.mutateAsync({} as never); });
    await waitFor(() => { expect(versionOf(view.result.current.list.data)).toBe(1); });
    expect(w.gets['/admin/revisions']).toBe(1);
  });

  it('the businesses after deleting one', async () => {
    const w = world();
    const view = renderHook(() => ({ list: useBusinesses(), del: useDeleteBusiness(), other: useRevisionStats() }), { wrapper: w.wrapper });
    await loaded(() => view.result.current.list.data);
    await loaded(() => view.result.current.other.data);
    await act(async () => { await view.result.current.del.mutateAsync('b1'); });
    await waitFor(() => { expect(versionOf(view.result.current.list.data)).toBe(1); });
    expect(w.gets['/admin/revisions']).toBe(1);
  });

  it.each([['succeeds', false], ['fails', true]])('the revision figures once a sweep %s, and nothing else', async (_name, fails) => {
    const w = world(fails);
    const view = renderHook(() => ({ stats: useRevisionStats(), sweep: useSweepRevisions(), config: useConfig() }), { wrapper: w.wrapper });
    await loaded(() => view.result.current.stats.data);
    await loaded(() => view.result.current.config.data);
    await act(async () => { await view.result.current.sweep.mutateAsync().catch(() => undefined); });
    await waitFor(() => { expect(w.gets['/admin/revisions']).toBe(2); });
    expect(w.gets['/admin/config']).toBe(1);
  });

  it('the settings and the site facts after a settings save, but not the revision figures', async () => {
    const w = world();
    const view = renderHook(() => ({ config: useConfig(), meta: useMeta(), stats: useRevisionStats(), save: useSaveConfig(() => undefined) }), { wrapper: w.wrapper });
    await loaded(() => view.result.current.config.data);
    await loaded(() => view.result.current.meta.data);
    await loaded(() => view.result.current.stats.data);
    await act(async () => { await view.result.current.save.mutateAsync({ 'site.name': 'x' }); });
    await waitFor(() => { expect(w.gets['/admin/config']).toBe(2); });
    await waitFor(() => { expect(w.gets['/meta']).toBe(2); });
    expect(w.gets['/admin/revisions']).toBe(1);
  });

  it('the settings after the setup screen saves two, but not the site facts', async () => {
    const w = world();
    const view = renderHook(() => ({ config: useConfig(), meta: useMeta(), save: useSaveSettings() }), { wrapper: w.wrapper });
    await loaded(() => view.result.current.config.data);
    await loaded(() => view.result.current.meta.data);
    await act(async () => { await view.result.current.save.mutateAsync({ a: 'b' }); });
    await waitFor(() => { expect(w.gets['/admin/config']).toBe(2); });
    expect(w.gets['/meta']).toBe(1);
  });
});

describe('site queries: what a save or a sync leaves on screen', () => {
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': status === 200 ? 'application/json' : 'application/problem+json' } });
  const net = (answer: (method: string, path: string) => Response) => {
    const calls: string[] = [];
    const fetchImpl = ((input: Request | string) => {
      const request = typeof input === 'string' ? new Request(input) : input;
      const path = new URL(request.url).pathname.replace('/pressroom/api/v1', '');
      calls.push(`${request.method} ${path}`);
      return Promise.resolve(answer(request.method, path));
    }) as typeof fetch;
    return { calls, wrapper: ({ children }: { children: ReactNode }) => <AppProviders boot={editorBoot} fetchImpl={fetchImpl}>{children}</AppProviders> };
  };

  it('shows the analytics status a sync answered, without reading it again', async () => {
    const n = net((method) => json(method === 'GET' ? { state: 'old' } : { state: 'synced' }));
    const view = renderHook(() => ({ status: useAnalyticsStatus(), sync: useSyncAnalytics() }), { wrapper: n.wrapper });
    await loaded(() => view.result.current.status.data);
    await act(async () => { await view.result.current.sync.mutateAsync(); });
    await waitFor(() => { expect(view.result.current.status.data).toEqual({ state: 'synced' }); });
    expect(n.calls.filter((c) => c === 'GET /admin/analytics')).toHaveLength(1);
  });

  describe('saving a revision limit says what the next sweep will remove', () => {
    const save = async (stats: () => Response, say: (total: number | undefined) => string | undefined) => {
      const n = net((method, path) => (method === 'PATCH' ? json({}) : path === '/admin/revisions' ? stats() : json({})));
      const { result } = renderHook(() => useSaveConfig(say), { wrapper: n.wrapper });
      let messages: string[] = [];
      await act(async () => { messages = await result.current.mutateAsync({ 'revisions.maxAgeDays': '30' }); });
      return { messages, calls: n.calls };
    };

    it('passes the predicted total to the message and returns the line', async () => {
      const say = vi.fn((total: number | undefined) => `will remove ${String(total)}`);
      const out = await save(() => json({ nextSweep: { total: 7 } }), say);
      expect(out.messages).toEqual(['will remove 7']);
      expect(say).toHaveBeenCalledWith(7);
    });

    it('says zero when the server gives no prediction, and nothing is known when the prediction is incomplete', async () => {
      const say = vi.fn((total: number | undefined) => `n=${String(total)}`);
      expect((await save(() => json({}), say)).messages).toEqual(['n=0']);
      expect((await save(() => json({ nextSweep: { incomplete: true, total: 5 } }), say)).messages).toEqual(['n=undefined']);
    });

    it('adds no line when the message has nothing to say, or the prediction cannot be read, and the save still counts', async () => {
      expect((await save(() => json({ nextSweep: { total: 1 } }), () => undefined)).messages).toEqual([]);
      const failed = await save(() => json({ status: 500, code: 'x', detail: 'no' }, 500), () => 'never');
      expect(failed.messages).toEqual([]);
      expect(failed.calls).toContain('PATCH /admin/config');
    });

    it('reads no prediction when no revision limit changed', async () => {
      const n = net(() => json({}));
      const { result } = renderHook(() => useSaveConfig(() => 'never'), { wrapper: n.wrapper });
      await act(async () => { await result.current.mutateAsync({ 'site.name': 'x' }); });
      expect(n.calls).toEqual(['PATCH /admin/config']);
    });
  });

  it('gives up on a business at once when the server fails, without retrying', async () => {
    const n = net(() => json({ status: 500, code: 'x', detail: 'no' }, 500));
    const { result } = renderHook(() => useBusiness('b1'), { wrapper: n.wrapper });
    await waitFor(() => { expect(result.current.isError).toBe(true); });
    expect(n.calls).toEqual(['GET /admin/businesses/b1']);
  });

  it('gives up on the site pages at once when the server fails, without retrying', async () => {
    const n = net(() => json({ status: 500, code: 'x', detail: 'no' }, 500));
    const { result } = renderHook(() => useSitePages(), { wrapper: n.wrapper });
    await waitFor(() => { expect(result.current.isError).toBe(true); });
    expect(n.calls).toEqual(['GET /admin/site-pages']);
  });
});
