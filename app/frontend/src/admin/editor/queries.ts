import { useQuery } from '@tanstack/react-query';
import { call } from '../api/client';
import { useApi } from '../context';

/**
 * Reads the entry editor needs, one hook each, all keyed by weblog handle so a switch of
 * weblog never shows another's data. Writes are made in the screens with `useMutation`.
 */

export const entryKey = (handle: string, id: string) => ['entry', handle, id] as const;

/** The entry to edit, text included. Idle for a new entry (no id). */
export function useEntry(handle: string, id: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: entryKey(handle, id ?? ''),
    enabled: id !== undefined,
    // A form is seeded from this once; refetching under the author's hands would be a surprise.
    staleTime: Infinity,
    gcTime: 0,
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/entries/{id}', { params: { path: { handle, id: id ?? '' } } }))
  });
}

/** The weblog's categories, in the order the JSP's select listed them. */
export function useCategories(handle: string) {
  const api = useApi();
  return useQuery({
    queryKey: ['categories', handle],
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/categories', { params: { path: { handle } } }))
  });
}

/**
 * The weblog's strand pages, plus the entry's current strand when that page is in the trash
 * (the JSP listed it so the membership shows). Only publishers see the control, so only they ask.
 */
export function useStrands(handle: string, enabled: boolean, currentStrand: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ['strands', handle, currentStrand ?? ''],
    enabled,
    queryFn: async () => {
      const pages = await call(api.GET('/v1/weblogs/{handle}/pages', { params: { path: { handle } } }));
      const strands = pages.filter((p) => p.strand === true).map((p) => ({ id: p.id ?? '', title: p.title ?? '', trashed: false }));
      if (currentStrand !== undefined && currentStrand !== '' && !strands.some((s) => s.id === currentStrand)) {
        const trashed = await call(api.GET('/v1/weblogs/{handle}/pages', {
          params: { path: { handle }, query: { status: 'TRASHED' } }
        }));
        const found = trashed.find((p) => p.id === currentStrand);
        if (found !== undefined) {
          strands.push({ id: currentStrand, title: found.title ?? '', trashed: true });
        }
      }
      return strands;
    }
  });
}

export const revisionsKey = (handle: string, id: string) => ['entry-revisions', handle, id] as const;

export function useRevisions(handle: string, id: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: revisionsKey(handle, id ?? ''),
    enabled: id !== undefined,
    staleTime: 0,
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/entries/{id}/revisions', { params: { path: { handle, id: id ?? '' } } }))
  });
}

/** Published and pending top-level comments of the entry, as the counts line shows them: the list's own totals. */
export function useCommentCounts(handle: string, id: string | undefined, enabled: boolean) {
  const api = useApi();
  return useQuery({
    queryKey: ['entry-comment-counts', handle, id ?? ''],
    enabled: enabled && id !== undefined,
    queryFn: async () => {
      const count = async (status: string) => {
        const page = await call(api.GET('/v1/weblogs/{handle}/comments', {
          params: { path: { handle }, query: { entryId: id ?? '', status, topLevel: true, limit: 1 } }
        }));
        return page.total;
      };
      return { published: await count('PUBLISHED'), pending: await count('PENDING') };
    }
  });
}
