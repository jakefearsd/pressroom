import { act, renderHook } from '@testing-library/react';
import { runBulk, useBulk } from '../../../src/admin/lib/bulk';

describe('runBulk', () => {
  it('runs every item, at most four at once, and reports progress and failures per item', async () => {
    let live = 0;
    let peak = 0;
    const progress: number[] = [];
    const items = Array.from({ length: 10 }, (_v, i) => i);
    const failed = await runBulk(items, async (n) => {
      live++;
      peak = Math.max(peak, live);
      await new Promise((resolve) => setTimeout(resolve, 2));
      live--;
      if (n === 3 || n === 7) {
        throw new Error(`no ${String(n)}`);
      }
    }, (done) => progress.push(done));
    expect(peak).toBe(4);
    expect(progress).toHaveLength(10);
    expect(progress.at(-1)).toBe(10);
    expect(failed.map((f) => f.item).sort()).toEqual([3, 7]);
    expect(failed[0]?.error).toBeInstanceOf(Error);
  });

  it('does nothing for an empty list', async () => {
    expect(await runBulk([], () => Promise.reject(new Error('never')), () => undefined)).toEqual([]);
  });
});

describe('useBulk', () => {
  it('exposes progress while running and the failures afterwards', async () => {
    const { result } = renderHook(() => useBulk<string>());
    expect(result.current.progress).toBeNull();
    let outcome: Awaited<ReturnType<typeof result.current.run>> | undefined;
    await act(async () => {
      outcome = await result.current.run(['a', 'b', 'c'], (id) => (id === 'b' ? Promise.reject(new Error('x')) : Promise.resolve()));
    });
    expect(outcome?.ok).toBe(2);
    expect(outcome?.failed.map((f) => f.item)).toEqual(['b']);
    expect(result.current.progress).toBeNull();
  });
});
