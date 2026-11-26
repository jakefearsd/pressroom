import { skipToken, useQuery } from '@tanstack/react-query';
import { useApi } from '../context';
import { call } from './client';

/** The signed-in account and the weblogs it belongs to (menu visibility and the switcher). */
export function useMe() {
  const api = useApi();
  return useQuery({ queryKey: ['me'], queryFn: () => call(api.GET('/v1/me')) });
}

/** Feature switches the rail's enabledProperty gates read, and the site URL. */
export function useMeta() {
  const api = useApi();
  return useQuery({ queryKey: ['meta'], queryFn: () => call(api.GET('/v1/meta')) });
}

/** One weblog's settings view: name, status, time zone, theme. Idle without a handle. */
export function useWeblog(handle: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ['weblog', handle],
    queryFn: handle === undefined ? skipToken : () => call(api.GET('/v1/weblogs/{handle}', { params: { path: { handle } } }))
  });
}

/** The weblogs the caller belongs to with the facts the main menu shows, and the site space for a site admin. */
export function useMyWeblogs() {
  const api = useApi();
  return useQuery({ queryKey: ['me', 'weblogs'], queryFn: () => call(api.GET('/v1/me/weblogs')) });
}

/**
 * One weblog's traffic for a period. The site space is never served on the weblog route, so a site
 * admin reads it (and any other weblog's) on the admin route.
 */
export function useBlogTraffic(handle: string, period: string, viaAdminRoute: boolean) {
  const api = useApi();
  return useQuery({
    queryKey: ['traffic', handle, period, viaAdminRoute],
    queryFn: () => viaAdminRoute
      ? call(api.GET('/v1/admin/traffic/{handle}', { params: { path: { handle }, query: { period } } }))
      : call(api.GET('/v1/weblogs/{handle}/traffic', { params: { path: { handle }, query: { period } } }))
  });
}

/** Every blog's traffic for a period (site admins). */
export function useSiteTraffic(period: string) {
  const api = useApi();
  return useQuery({
    queryKey: ['traffic', 'site', period],
    queryFn: () => call(api.GET('/v1/admin/traffic', { params: { query: { period } } }))
  });
}

/** Themes, locales and time zones the create form offers. */
export function useWeblogOptions() {
  const api = useApi();
  return useQuery({ queryKey: ['weblogOptions'], queryFn: () => call(api.GET('/v1/meta/weblog-options')) });
}

/**
 * The shared themes this weblog may switch to, and whether it may go custom. The site space is withheld
 * from the weblog routes, so its themes come from the site admin's own route (never custom).
 */
export function useWeblogThemes(handle: string | undefined, siteSpace = false) {
  const api = useApi();
  return useQuery({
    queryKey: ['weblogThemes', handle],
    queryFn: handle === undefined ? skipToken : () => siteSpace
      ? call(api.GET('/v1/admin/site-theme'))
      : call(api.GET('/v1/weblogs/{handle}/themes', { params: { path: { handle } } }))
  });
}

/** The business profiles the settings picker offers (one page of up to 200). */
export function useWeblogBusinesses(handle: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ['weblogBusinesses', handle],
    queryFn: handle === undefined ? skipToken : () => call(api.GET('/v1/weblogs/{handle}/businesses', {
      params: { path: { handle }, query: { limit: 200 } }
    }))
  });
}

/** A weblog's pages (the front-page picker keeps the published ones). */
export function useWeblogPages(handle: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ['weblogPages', handle],
    queryFn: handle === undefined ? skipToken : () => call(api.GET('/v1/weblogs/{handle}/pages', { params: { path: { handle } } }))
  });
}
