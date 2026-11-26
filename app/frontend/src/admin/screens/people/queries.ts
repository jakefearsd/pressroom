import { skipToken, useQuery } from '@tanstack/react-query';
import { call } from '../../api/client';
import { ApiError } from '../../api/problem';
import type { components } from '../../api/schema';
import { useApi } from '../../context';

export type UserView = components['schemas']['UserView'];
export type AgentView = components['schemas']['AgentView'];
export type TokenView = components['schemas']['TokenView'];
export type SiteTokenView = components['schemas']['SiteTokenView'];
export type Membership = components['schemas']['WeblogMembership'];
export type WeblogView = components['schemas']['WeblogView'];

export type Kind = 'people' | 'agents' | undefined;

/** Every account for the tab and filter: all pages, the JSP listed them all. */
export function useUsers(kind: Kind, q: string) {
  const api = useApi();
  return useQuery({
    queryKey: ['users', kind ?? 'all', q],
    queryFn: async () => {
      const items: UserView[] = [];
      for (let offset = 0; ; offset += 200) {
        const page = await call(api.GET('/v1/admin/users', {
          params: { query: { limit: 200, offset, ...(kind ? { kind } : {}), ...(q === '' ? {} : { q }) } }
        }));
        items.push(...page.items);
        if (!page.hasMore) {
          return items;
        }
      }
    }
  });
}

/** One account by user name; null when there is no such account (404 `user.notFound`). */
export function useUser(userName: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ['user', userName],
    queryFn: userName === undefined ? skipToken : async () => {
      try {
        return await call(api.GET('/v1/admin/users/{userName}', { params: { path: { userName } } }));
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) {
          return null;
        }
        throw error;
      }
    }
  });
}

/** The weblogs an account belongs to, with its level on each. */
export function useUserWeblogs(userName: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ['userWeblogs', userName],
    queryFn: userName === undefined ? skipToken : async () => (await call(api.GET('/v1/admin/users/{userName}/weblogs', {
      params: { path: { userName } }
    }))).items
  });
}

/** Every weblog on the site (an admin sees all): the grant form's choices. */
export function useAllWeblogs(enabled = true) {
  const api = useApi();
  return useQuery({
    queryKey: ['allWeblogs'],
    enabled,
    queryFn: async () => {
      const items: WeblogView[] = [];
      for (let offset = 0; ; offset += 200) {
        const page = await call(api.GET('/v1/weblogs', { params: { query: { limit: 200, offset } } }));
        items.push(...page.items);
        if (!page.hasMore) {
          return items;
        }
      }
    }
  });
}

/** The locale and time-zone lists every settings form offers. */
export function useWeblogOptions() {
  const api = useApi();
  return useQuery({
    queryKey: ['weblogOptions'],
    staleTime: Infinity,
    queryFn: () => call(api.GET('/v1/meta/weblog-options'))
  });
}

export function useAgent(userName: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ['agent', userName],
    queryFn: userName === undefined ? skipToken : () => call(api.GET('/v1/admin/agents/{userName}', { params: { path: { userName } } }))
  });
}

export function useSiteTokens(all: boolean) {
  const api = useApi();
  return useQuery({
    queryKey: ['siteTokens', all],
    queryFn: async () => (await call(api.GET('/v1/admin/tokens', { params: { query: { all } } }))).items ?? []
  });
}

/** The signed-in account's live tokens. */
export function useMyTokens() {
  const api = useApi();
  return useQuery({ queryKey: ['myTokens'], queryFn: () => call(api.GET('/v1/tokens')) });
}

export function useProfile() {
  const api = useApi();
  return useQuery({ queryKey: ['profile'], queryFn: () => call(api.GET('/v1/me/profile')) });
}
