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
