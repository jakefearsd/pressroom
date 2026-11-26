import { useQuery } from '@tanstack/react-query';
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
    enabled: handle !== undefined,
    queryFn: () => call(api.GET('/v1/weblogs/{handle}', { params: { path: { handle: handle ?? '' } } }))
  });
}

/** Themes, locales and time zones the create form offers. */
export function useWeblogOptions() {
  const api = useApi();
  return useQuery({ queryKey: ['weblogOptions'], queryFn: () => call(api.GET('/v1/meta/weblog-options')) });
}

/** The shared themes this weblog may switch to, and whether it may go custom. */
export function useWeblogThemes(handle: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ['weblogThemes', handle],
    enabled: handle !== undefined,
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/themes', { params: { path: { handle: handle ?? '' } } }))
  });
}

/** The business profiles the settings picker offers (one page of up to 200). */
export function useWeblogBusinesses(handle: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ['weblogBusinesses', handle],
    enabled: handle !== undefined,
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/businesses', {
      params: { path: { handle: handle ?? '' }, query: { limit: 200 } }
    }))
  });
}

/** A weblog's pages (the front-page picker keeps the published ones). */
export function useWeblogPages(handle: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ['weblogPages', handle],
    enabled: handle !== undefined,
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/pages', { params: { path: { handle: handle ?? '' } } }))
  });
}
