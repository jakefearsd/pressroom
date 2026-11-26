import { useCallback, useState } from 'react';

/** Spec A6: bulk actions are per-item calls, this many at a time. */
export const BULK_CONCURRENCY = 4;

export interface BulkFailure<T> {
  item: T;
  error: unknown;
}

/**
 * Runs `worker` over every item, `concurrency` at a time, never stopping at a failure:
 * each failed item is collected with its error so the screen can say which and why in place.
 * `onProgress` gets the number finished (succeeded or failed) after each one.
 */
export async function runBulk<T>(
  items: readonly T[],
  worker: (item: T) => Promise<unknown>,
  onProgress: (done: number) => void,
  concurrency = BULK_CONCURRENCY
): Promise<BulkFailure<T>[]> {
  const failed: BulkFailure<T>[] = [];
  let next = 0;
  let done = 0;
  const lane = async () => {
    while (next < items.length) {
      const item = items[next++] as T;
      try {
        await worker(item);
      } catch (error) {
        failed.push({ item, error });
      }
      onProgress(++done);
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, lane));
  return failed;
}

export interface BulkOutcome<T> {
  ok: number;
  failed: BulkFailure<T>[];
}

/** `run` a bulk action and watch its `progress` ({done,total}, null while idle). */
export function useBulk<T>() {
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
  const run = useCallback(async (items: readonly T[], worker: (item: T) => Promise<unknown>): Promise<BulkOutcome<T>> => {
    setProgress({ done: 0, total: items.length });
    try {
      const failed = await runBulk(items, worker, (done) => { setProgress({ done, total: items.length }); });
      return { ok: items.length - failed.length, failed };
    } finally {
      setProgress(null);
    }
  }, []);
  return { progress, run };
}
