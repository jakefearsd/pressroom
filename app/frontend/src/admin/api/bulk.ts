/** One item's outcome from runBulk: the item, and why it failed (absent on success). */
export interface BulkResult<T> {
  item: T;
  error?: unknown;
}

/**
 * Per-item calls with a small concurrency (spec A6: no bulk routes). Every item is attempted
 * whatever happens to the others; `onProgress` hears each completion. Results keep input order.
 */
export async function runBulk<T>(
  items: readonly T[],
  work: (item: T) => Promise<unknown>,
  concurrency = 4,
  onProgress?: (done: number, total: number) => void
): Promise<BulkResult<T>[]> {
  const results: BulkResult<T>[] = items.map((item) => ({ item }));
  let next = 0;
  let done = 0;
  const lane = async () => {
    while (next < items.length) {
      const index = next++;
      try {
        await work(items[index] as T);
      } catch (error) {
        (results[index] as BulkResult<T>).error = error;
      }
      done++;
      onProgress?.(done, items.length);
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, lane));
  return results;
}
