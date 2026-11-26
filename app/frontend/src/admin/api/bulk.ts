export interface Failure<T> {
  item: T;
  error: unknown;
}

/**
 * Runs `work` over every item, at most `concurrency` at a time (spec A6: no bulk routes,
 * per-item calls). A failing item never stops the rest; the failures come back for the
 * screen to report in place. `onProgress` gets the count finished so far.
 */
export async function runPool<T>(
  items: readonly T[], concurrency: number, work: (item: T) => Promise<unknown>,
  onProgress?: (finished: number) => void
): Promise<Failure<T>[]> {
  const failures: Failure<T>[] = [];
  let next = 0;
  let finished = 0;
  const lane = async () => {
    while (next < items.length) {
      const item = items[next++] as T;
      try {
        await work(item);
      } catch (error) {
        failures.push({ item, error });
      }
      finished++;
      onProgress?.(finished);
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, lane));
  return failures;
}
