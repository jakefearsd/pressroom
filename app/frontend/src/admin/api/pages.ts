import { useQuery } from '@tanstack/react-query';
import { useApi } from '../context';
import { call } from './client';
import type { components } from './schema';

export type PageView = components['schemas']['PageView'];
export type PageWrite = components['schemas']['PageWrite'];
export type PartView = components['schemas']['PartView'];
export type RevisionSummary = components['schemas']['RevisionSummary'];
export type RevisionView = components['schemas']['RevisionView'];
export type PreviewView = components['schemas']['PreviewView'];

/** The weblog's pages in navigation order, or (`TRASHED`) its page trash. */
export function usePages(handle: string, status?: 'TRASHED') {
  const api = useApi();
  return useQuery({
    queryKey: ['pages', handle, status ?? 'live'],
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/pages', {
      params: { path: { handle }, ...(status === undefined ? {} : { query: { status } }) }
    }))
  });
}

/** One live page with its parts. Idle without an id (a new page). */
export function usePage(handle: string, id: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ['page', handle, id],
    enabled: id !== undefined,
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/pages/{id}', { params: { path: { handle, id: id ?? '' } } }))
  });
}

/** A saved page's revisions, newest first. */
export function usePageRevisions(handle: string, id: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ['page-revisions', handle, id],
    enabled: id !== undefined,
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/pages/{id}/revisions',
      { params: { path: { handle, id: id ?? '' } } }))
  });
}

/** The weblog's posts that could join a strand: those not in one already (the editor's "Add posts" choices). */
export function useStrandCandidates(handle: string, enabled: boolean) {
  const api = useApi();
  return useQuery({
    queryKey: ['strand-candidates', handle],
    enabled,
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/entries', {
      params: { path: { handle }, query: { limit: 200, fields: ['title', 'status', 'strand'] } }
    }))
  });
}

/**
 * Runs `work` over `items` with at most `limit` in flight (spec A6: bulk actions are per-item
 * calls). Resolves with the items that failed, each with its error; never rejects.
 */
export async function runPool<T>(
  items: readonly T[], limit: number, work: (item: T) => Promise<void>, onProgress?: (done: number) => void
): Promise<{ item: T; error: unknown }[]> {
  const failed: { item: T; error: unknown }[] = [];
  let next = 0;
  let done = 0;
  const lane = async () => {
    while (next < items.length) {
      const item = items[next++] as T;
      try {
        await work(item);
      } catch (error) {
        failed.push({ item, error });
      }
      onProgress?.(++done);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, lane));
  return failed;
}
