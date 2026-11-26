import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../context';
import { call } from './client';
import type { components } from './schema';

export type TemplateView = components['schemas']['TemplateView'];
export type TemplateWrite = components['schemas']['TemplateWrite'];
export type StylesheetView = components['schemas']['StylesheetView'];
export type MemberView = components['schemas']['MemberView'];
export type MemberPermission = MemberView['permission'];
export type SubmissionView = components['schemas']['SubmissionView'];

export const SUBMISSIONS_PAGE_SIZE = 30;

const handlePath = (handle: string) => ({ path: { handle } });

export function useTemplates(handle: string) {
  const api = useApi();
  return useQuery({
    queryKey: ['templates', handle],
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/templates', { params: handlePath(handle) }))
  });
}

export function useTemplate(handle: string, id: string) {
  const api = useApi();
  return useQuery({
    queryKey: ['template', handle, id],
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/templates/{id}', { params: { path: { handle, id } } }))
  });
}

export function useCreateTemplate(handle: string) {
  const api = useApi();
  const queries = useQueryClient();
  return useMutation({
    mutationFn: (body: { name: string; action: string }) =>
      call(api.POST('/v1/weblogs/{handle}/templates', { params: handlePath(handle), body })),
    onSuccess: () => queries.invalidateQueries({ queryKey: ['templates', handle] })
  });
}

export function useUpdateTemplate(handle: string, id: string) {
  const api = useApi();
  const queries = useQueryClient();
  return useMutation({
    mutationFn: (body: TemplateWrite) =>
      call(api.PATCH('/v1/weblogs/{handle}/templates/{id}', { params: { path: { handle, id } }, body })),
    onSuccess: (view) => {
      queries.setQueryData(['template', handle, id], view);
      return queries.invalidateQueries({ queryKey: ['templates', handle] });
    }
  });
}

export function useDeleteTemplate(handle: string) {
  const api = useApi();
  const queries = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      call(api.DELETE('/v1/weblogs/{handle}/templates/{id}', { params: { path: { handle, id } } })),
    onSuccess: () => queries.invalidateQueries({ queryKey: ['templates', handle] })
  });
}

export function useStylesheet(handle: string) {
  const api = useApi();
  return useQuery({
    queryKey: ['stylesheet', handle],
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/stylesheet', { params: handlePath(handle) }))
  });
}

/** The four stylesheet writes; each answers the new view (delete answers none, so it refetches). */
export function useStylesheetActions(handle: string) {
  const api = useApi();
  const queries = useQueryClient();
  const key = ['stylesheet', handle];
  const keep = (view: StylesheetView) => { queries.setQueryData(key, view); };
  return {
    save: useMutation({
      mutationFn: (contents: string) =>
        call(api.PUT('/v1/weblogs/{handle}/stylesheet', { params: handlePath(handle), body: { contents } })),
      onSuccess: keep
    }),
    copy: useMutation({
      mutationFn: () =>
        call(api.POST('/v1/weblogs/{handle}/stylesheet/copy-from-shared', { params: handlePath(handle) })),
      onSuccess: keep
    }),
    revert: useMutation({
      mutationFn: () => call(api.POST('/v1/weblogs/{handle}/stylesheet/revert', { params: handlePath(handle) })),
      onSuccess: keep
    }),
    remove: useMutation({
      mutationFn: () => call(api.DELETE('/v1/weblogs/{handle}/stylesheet', { params: handlePath(handle) })),
      onSuccess: () => queries.invalidateQueries({ queryKey: key })
    })
  };
}

const MEMBERS_PAGE = 200;

/** Every member: pages until `hasMore` is false (the API caps a page at 200). */
export function useMembers(handle: string) {
  const api = useApi();
  return useQuery({
    queryKey: ['members', handle],
    queryFn: async () => {
      const members: MemberView[] = [];
      for (let offset = 0; ; offset += MEMBERS_PAGE) {
        const page = await call(api.GET('/v1/weblogs/{handle}/members', {
          params: { ...handlePath(handle), query: { offset, limit: MEMBERS_PAGE } }
        }));
        members.push(...page.items);
        if (!page.hasMore || page.items.length === 0) {
          return members;
        }
      }
    }
  });
}

export function useMemberActions(handle: string) {
  const api = useApi();
  const queries = useQueryClient();
  const refresh = () => queries.invalidateQueries({ queryKey: ['members', handle] });
  return {
    grant: useMutation({
      mutationFn: (body: { userName: string; permission: MemberPermission }) =>
        call(api.POST('/v1/weblogs/{handle}/members', { params: handlePath(handle), body })),
      onSuccess: refresh
    }),
    change: (userName: string, permission: MemberPermission) =>
      call(api.PATCH('/v1/weblogs/{handle}/members/{userName}', { params: { path: { handle, userName } }, body: { permission } })),
    remove: (userName: string) =>
      call(api.DELETE('/v1/weblogs/{handle}/members/{userName}', { params: { path: { handle, userName } } })),
    refresh
  };
}

export function useSubmissions(handle: string, page: number) {
  const api = useApi();
  return useQuery({
    queryKey: ['submissions', handle, page],
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/submissions', {
      params: { ...handlePath(handle), query: { offset: page * SUBMISSIONS_PAGE_SIZE, limit: SUBMISSIONS_PAGE_SIZE } }
    }))
  });
}

export function useSubmissionActions(handle: string) {
  const api = useApi();
  const queries = useQueryClient();
  return {
    remove: (id: string) =>
      call(api.DELETE('/v1/weblogs/{handle}/submissions/{id}', { params: { path: { handle, id } } })),
    refresh: () => queries.invalidateQueries({ queryKey: ['submissions', handle] })
  };
}
