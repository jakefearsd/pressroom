import { act, renderHook, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { AppProviders } from '../../../src/admin/context';
import { useBulk } from '../../../src/admin/lib/bulk';
import { RequireLevel, useWeblogRights } from '../../../src/admin/lib/permission';
import { editorBoot, realMessages, renderInApp } from '../support';

const me = (globalAdmin: boolean) => ({
  userName: 'jake', screenName: 'Jake', kind: 'PERSON', globalAdmin, tokenScope: {},
  weblogs: [{ handle: 'alpha', name: 'Alpha', permission: 'EDIT_DRAFT' }, { handle: 'beta', name: 'Beta', permission: 'POST' },
    { handle: 'gamma', name: 'Gamma', permission: 'ADMIN' }]
});
const net = (globalAdmin: boolean) => ((() => Promise.resolve(new Response(JSON.stringify(me(globalAdmin)),
  { status: 200, headers: { 'Content-Type': 'application/json' } }))) as unknown as typeof fetch);
const rights = (handle: string | undefined, globalAdmin = false) => renderHook(() => useWeblogRights(handle), {
  wrapper: ({ children }: { children: ReactNode }) => <AppProviders boot={editorBoot} fetchImpl={net(globalAdmin)}>{children}</AppProviders>
});

describe('useWeblogRights', () => {
  it('is not loaded until the account answers', async () => {
    const { result } = rights('alpha');
    expect(result.current.loaded).toBe(false);
    await waitFor(() => { expect(result.current.loaded).toBe(true); });
  });

  it.each([['alpha', false], ['beta', true], ['gamma', true], ['unknown', false], [undefined, false]])(
    'for member level on %s, publishing is %s (an author otherwise)', async (handle, mayPublish) => {
      const { result } = rights(handle);
      await waitFor(() => { expect(result.current.loaded).toBe(true); });
      expect(result.current.mayPublish).toBe(mayPublish);
      expect(result.current.isAuthor).toBe(!mayPublish);
    });

  it('lets a site admin publish on any weblog', async () => {
    const { result } = rights('unknown', true);
    await waitFor(() => { expect(result.current.mayPublish).toBe(true); });
  });
});

const NOT_FOUND = realMessages['app.notFound'] as string;

describe('RequireLevel', () => {
  const guarded = (handle: string, level: 'edit_draft' | 'post' | 'admin', globalAdmin = false) => renderInApp(
    <RequireLevel level={level} handle={handle}><p>secret</p></RequireLevel>,
    { fetchImpl: net(globalAdmin) });

  it('shows nothing until the account is known, then the content to a member who holds the level', async () => {
    guarded('beta', 'post');
    expect(screen.queryByText('secret')).toBeNull();
    expect(await screen.findByText('secret')).toBeInTheDocument();
  });

  it('shows the not-found treatment to a member below the level, picking the right membership among several', async () => {
    guarded('alpha', 'post');
    expect(await screen.findByText(NOT_FOUND)).toBeInTheDocument();
    expect(screen.queryByText('secret')).toBeNull();
  });

  it('holds a site admin to every level', async () => {
    guarded('unknown', 'admin', true);
    expect(await screen.findByText('secret')).toBeInTheDocument();
  });
});

describe('useBulk', () => {
  it('reports progress while it runs and none afterwards', async () => {
    const gates: (() => void)[] = [];
    const worker = () => new Promise<void>((resolve) => { gates.push(resolve); });
    const { result } = renderHook(() => useBulk<number>());
    let done: Promise<unknown> = Promise.resolve();
    act(() => { done = result.current.run([1, 2], worker); });
    expect(result.current.progress).toEqual({ done: 0, total: 2 });
    await waitFor(() => { expect(gates).toHaveLength(2); });
    await act(async () => { gates[0]?.(); await Promise.resolve(); });
    await waitFor(() => { expect(result.current.progress).toEqual({ done: 1, total: 2 }); });
    await act(async () => { gates[1]?.(); await done; });
    expect(result.current.progress).toBeNull();
  });

  it('keeps one stable run function across renders', () => {
    const { result, rerender } = renderHook(() => useBulk<number>());
    const first = result.current.run;
    rerender();
    expect(result.current.run).toBe(first);
  });
});
