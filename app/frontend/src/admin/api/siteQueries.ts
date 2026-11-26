import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../context';
import { call } from './client';
import { ApiError } from './problem';
import type { components } from './schema';

export type BusinessView = components['schemas']['BusinessView'];
export type BusinessWrite = components['schemas']['BusinessWrite'];

/** The businesses, whole: a site has a handful, and one page holds up to 200. */
export function useBusinesses() {
  const api = useApi();
  return useQuery({
    queryKey: ['admin', 'businesses'],
    queryFn: () => call(api.GET('/v1/admin/businesses', { params: { query: { limit: 200 } } }))
  });
}

/** One business; idle with no id (the "new" form). */
export function useBusiness(id: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ['admin', 'businesses', id],
    enabled: id !== undefined,
    retry: false,
    queryFn: () => call(api.GET('/v1/admin/businesses/{id}', { params: { path: { id: id ?? '' } } }))
  });
}

export function useSaveBusiness(id: string | undefined) {
  const api = useApi();
  const queries = useQueryClient();
  return useMutation({
    mutationFn: (body: BusinessWrite) => id === undefined
      ? call(api.POST('/v1/admin/businesses', { body }))
      : call(api.PATCH('/v1/admin/businesses/{id}', { params: { path: { id } }, body })),
    onSuccess: () => queries.invalidateQueries({ queryKey: ['admin', 'businesses'] })
  });
}

export function useDeleteBusiness() {
  const api = useApi();
  const queries = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => call(api.DELETE('/v1/admin/businesses/{id}', { params: { path: { id } } })),
    onSuccess: () => queries.invalidateQueries({ queryKey: ['admin', 'businesses'] })
  });
}

export type Stats = components['schemas']['Stats'];
export type Report = components['schemas']['Report'];
export type DiscoveryStatus = components['schemas']['DiscoveryStatusView'];
export type AnalyticsStatus = components['schemas']['AnalyticsStatusView'];

export type SiteAction = 'flush-cache' | 'rebuild-index' | 'regenerate-renditions';

/** Every weblog (all pages), for the pickers and the setup checklist's total. */
export function useAllWeblogs() {
  const api = useApi();
  return useQuery({
    queryKey: ['admin', 'weblogs'],
    queryFn: async () => {
      const items: components['schemas']['WeblogView'][] = [];
      for (let offset = 0; ; offset += 200) {
        const page = await call(api.GET('/v1/weblogs', { params: { query: { limit: 200, offset } } }));
        items.push(...page.items);
        if (!page.hasMore) {
          return { items };
        }
      }
    }
  });
}

/** What the revision store holds. Never cached for long: it is computed on the server per request. */
export function useRevisionStats() {
  const api = useApi();
  return useQuery({ queryKey: ['admin', 'revisions'], retry: false, queryFn: () => call(api.GET('/v1/admin/revisions')) });
}

export function useDiscoveryStatus() {
  const api = useApi();
  return useQuery({ queryKey: ['admin', 'discovery'], queryFn: () => call(api.GET('/v1/admin/discovery')) });
}

export function useAnalyticsStatus() {
  const api = useApi();
  return useQuery({ queryKey: ['admin', 'analytics'], queryFn: () => call(api.GET('/v1/admin/analytics')) });
}

/** Flush the cache, rebuild the index or regenerate renditions on one weblog (each answers 202). */
export function useSiteAction() {
  const api = useApi();
  return useMutation({
    mutationFn: ({ handle, action }: { handle: string; action: SiteAction }) => {
      const params = { params: { path: { handle } } };
      switch (action) {
        case 'flush-cache': return call(api.POST('/v1/admin/weblogs/{handle}/actions/flush-cache', params));
        case 'rebuild-index': return call(api.POST('/v1/admin/weblogs/{handle}/actions/rebuild-index', params));
        case 'regenerate-renditions': return call(api.POST('/v1/admin/weblogs/{handle}/actions/regenerate-renditions', params));
      }
    }
  });
}

/** Rebuild the popularity snapshot now; answers the status it left. */
export function useRefreshDiscovery() {
  const api = useApi();
  const queries = useQueryClient();
  return useMutation({
    mutationFn: () => call(api.POST('/v1/admin/discovery/refresh')),
    onSuccess: (status) => { queries.setQueryData(['admin', 'discovery'], status); }
  });
}

/** Ask for an analytics sync; it runs in the background. */
export function useSyncAnalytics() {
  const api = useApi();
  const queries = useQueryClient();
  return useMutation({
    mutationFn: () => call(api.POST('/v1/admin/analytics/sync')),
    onSuccess: (status) => { queries.setQueryData(['admin', 'analytics'], status); }
  });
}

/** Run the revision sweep for real (the API's default is a dry run). */
export function useSweepRevisions() {
  const api = useApi();
  const queries = useQueryClient();
  return useMutation({
    mutationFn: () => call(api.POST('/v1/admin/revisions/sweep', { body: { dryRun: false } })),
    onSettled: () => queries.invalidateQueries({ queryKey: ['admin', 'revisions'] })
  });
}

export type ConfigGroup = components['schemas']['ConfigGroup'];
export type ConfigEntry = components['schemas']['ConfigEntry'];
export type ConfigPropertyDefinition = components['schemas']['ConfigPropertyDefinition'];

/** The display groups and property definitions Global Config draws itself from. */
export function useConfigDefinitions() {
  const api = useApi();
  return useQuery({ queryKey: ['admin', 'config', 'definitions'], retry: false, queryFn: () => call(api.GET('/v1/admin/config/definitions')) });
}

/** The runtime properties' current values. */
export function useConfig() {
  const api = useApi();
  return useQuery({ queryKey: ['admin', 'config'], queryFn: () => call(api.GET('/v1/admin/config')) });
}

/** The site space's pages (the front-door choices); none when there is no site space. */
export function useSitePages(enabled = true) {
  const api = useApi();
  return useQuery({
    queryKey: ['admin', 'sitePages'],
    enabled,
    retry: false,
    queryFn: async () => {
      try {
        return await call(api.GET('/v1/weblogs/{handle}/pages', { params: { path: { handle: 'site-space' } } }));
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) {
          return [];
        }
        throw error;
      }
    }
  });
}

/** The settings that remove revisions: saving one deletes nothing, and the save says what the next sweep will. */
export const REVISION_LIMITS = ['entry.revisions.retention', 'page.revisions.retention', 'revisions.maxAgeDays', 'revisions.total.max'];

/**
 * PATCH the changed settings. After a revision limit changed it also reads what the next sweep would
 * remove (a courtesy: a prediction that cannot be made adds nothing), and the saved message says so.
 */
export function useSaveConfig(sayNextSweep: (total: number | undefined) => string | undefined) {
  const api = useApi();
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (changes: Record<string, string>): Promise<string[]> => {
      if (Object.keys(changes).length > 0) {
        await call(api.PATCH('/v1/admin/config', { body: changes }));
      }
      const messages: string[] = [];
      if (REVISION_LIMITS.some((name) => name in changes)) {
        try {
          const stats = await call(api.GET('/v1/admin/revisions'));
          const next = sayNextSweep(stats.nextSweep?.incomplete === true ? undefined : stats.nextSweep?.total ?? 0);
          if (next !== undefined) {
            messages.push(next);
          }
        } catch {
          // A courtesy line after a stored save: no prediction, no line.
        }
      }
      return messages;
    },
    onSuccess: () => Promise.all([
      queries.invalidateQueries({ queryKey: ['admin', 'config'] }),
      queries.invalidateQueries({ queryKey: ['meta'] })
    ])
  });
}

/** Accounts on the site (people and agents), counted over every page, for the setup checklist. */
export function useAccountCount() {
  const api = useApi();
  return useQuery({
    queryKey: ['admin', 'accountCount'],
    queryFn: async () => {
      let count = 0;
      for (let offset = 0; ; offset += 200) {
        const page = await call(api.GET('/v1/admin/users', { params: { query: { limit: 200, offset } } }));
        count += page.items.length;
        if (!page.hasMore) {
          return count;
        }
      }
    }
  });
}

/** PATCH settings by name (the setup screen's two front page settings). */
export function useSaveSettings() {
  const api = useApi();
  const queries = useQueryClient();
  return useMutation({
    mutationFn: (body: Record<string, string>) => call(api.PATCH('/v1/admin/config', { body })),
    onSuccess: () => queries.invalidateQueries({ queryKey: ['admin', 'config'] })
  });
}
