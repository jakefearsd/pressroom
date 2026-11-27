import { useQuery } from '@tanstack/react-query';
import { useApi } from '../context';
import { call } from './client';
import type { components } from './schema';

export type EntryView = components['schemas']['EntryView'];
export type CommentView = components['schemas']['CommentView'];
export type QueueComment = components['schemas']['QueueComment'];
export type CategoryView = components['schemas']['CategoryView'];

/** Entries per page, as the JSP list's COUNT. */
export const ENTRIES_PAGE = 30;
/** Comments per page, as CommentsModerationController's COUNT. */
export const COMMENTS_PAGE = 30;

/** The fields the list prints; the text stays on the server. */
const LIST_FIELDS = ['anchor', 'title', 'status', 'category', 'tags', 'pubTime', 'updateTime', 'permalink', 'pinned'];

export interface EntryFilters {
  status?: string | undefined;
  category?: string | undefined;
  tags?: string[] | undefined;
  text?: string | undefined;
  startDate?: string | undefined;
  endDate?: string | undefined;
  sortBy?: string | undefined;
  offset: number;
}

function present(value: string | undefined): string | undefined {
  return value === undefined || value === '' ? undefined : value;
}

/** One page of the entries list. A status of ALL (or none) is no filter, as on the JSP. */
export function useEntryList(handle: string, filters: EntryFilters, withViews = false) {
  const api = useApi();
  return useQuery({
    queryKey: ['entries', handle, 'list', filters, withViews],
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/entries', {
      params: {
        path: { handle },
        query: {
          ...(filters.status !== undefined && filters.status !== 'ALL' && filters.status !== '' ? { status: filters.status } : {}),
          ...(present(filters.category) === undefined ? {} : { category: filters.category as string }),
          ...(filters.tags !== undefined && filters.tags.length > 0 ? { tags: filters.tags } : {}),
          ...(present(filters.text) === undefined ? {} : { text: filters.text as string }),
          ...(present(filters.startDate) === undefined ? {} : { startDate: filters.startDate as string }),
          ...(present(filters.endDate) === undefined ? {} : { endDate: filters.endDate as string }),
          ...(present(filters.sortBy) === undefined ? {} : { sortBy: filters.sortBy as string }),
          offset: filters.offset,
          limit: ENTRIES_PAGE,
          fields: withViews ? [...LIST_FIELDS, 'views30d'] : LIST_FIELDS
        }
      }
    }))
  });
}

/** Every trashed entry (the trash page is not paged): pages through until hasMore is false. */
export function useTrash(handle: string) {
  const api = useApi();
  return useQuery({
    queryKey: ['entries', handle, 'trash'],
    queryFn: async () => {
      const all: EntryView[] = [];
      for (let offset = 0; ;) {
        const page = await call(api.GET('/v1/weblogs/{handle}/entries', {
          params: { path: { handle }, query: { status: 'TRASHED', offset, limit: 200, fields: ['anchor', 'title', 'category', 'trashedAt'] } }
        }));
        all.push(...page.items);
        if (!page.hasMore || page.items.length === 0) {
          return all;
        }
        offset += page.items.length;
      }
    }
  });
}

/** The weblog's categories (a plain list, not paged). */
export function useCategories(handle: string) {
  const api = useApi();
  return useQuery({
    queryKey: ['categories', handle],
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/categories', { params: { path: { handle } } }))
  });
}

export interface CommentFilters {
  status: string;
  entryId?: string | undefined;
  offset: number;
}

/** One page of one comment tab, optionally for a single entry. */
export function useComments(handle: string, filters: CommentFilters) {
  const api = useApi();
  return useQuery({
    queryKey: ['comments', handle, filters],
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/comments', {
      params: {
        path: { handle },
        query: {
          status: filters.status,
          ...(present(filters.entryId) === undefined ? {} : { entryId: filters.entryId as string }),
          offset: filters.offset,
          limit: COMMENTS_PAGE
        }
      }
    }))
  });
}

/** One page of the cross-blog approval queue: comments of one status on every blog the caller may moderate. */
export function useCommentQueue(status: string, offset: number) {
  const api = useApi();
  return useQuery({
    queryKey: ['commentQueue', status, offset],
    queryFn: () => call(api.GET('/v1/me/comments', { params: { query: { status, offset, limit: COMMENTS_PAGE } } }))
  });
}
