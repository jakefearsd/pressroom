import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createContext, useContext, useMemo, useState, useSyncExternalStore, type ReactNode } from 'react';
import { ApiError } from './api/problem';
import { createApiClient, type ApiClient } from './api/client';
import type { Bootstrap } from './bootstrap';
import { createSessionStore, type SessionStore } from './session/store';

interface AppContextValue {
  boot: Bootstrap;
  api: ApiClient;
  session: SessionStore;
}

const AppContext = createContext<AppContextValue | null>(null);

function useAppContext(): AppContextValue {
  const value = useContext(AppContext);
  if (value === null) {
    throw new Error('Needs an AppProviders ancestor');
  }
  return value;
}

export const useBoot = (): Bootstrap => useAppContext().boot;
export const useApi = (): ApiClient => useAppContext().api;
/** Reports a 401 from a call that bypasses the API client (multipart upload progress). */
export const useExpire = (): (() => void) => useAppContext().session.expire;

/** True once any API call has answered 401. */
export function useSessionExpired(): boolean {
  const { session } = useAppContext();
  return useSyncExternalStore(session.subscribe, session.expired);
}

/** A 4xx is the answer, not a glitch: retrying it only delays the message. */
export function retryable(count: number, error: Error): boolean {
  return !(error instanceof ApiError && error.status < 500) && count < 2;
}

/** Bootstrap, the typed API client (401 wired to the session store) and TanStack Query. */
export function AppProviders(
  { boot, fetchImpl, children }: { boot: Bootstrap; fetchImpl?: typeof fetch; children: ReactNode }
) {
  const [session] = useState(createSessionStore);
  const api = useMemo(() => createApiClient(boot, fetchImpl, session.expire), [boot, fetchImpl, session]);
  const [queries] = useState(() => new QueryClient({
    defaultOptions: { queries: { retry: retryable, staleTime: 30_000, refetchOnWindowFocus: false } }
  }));
  const value = useMemo(() => ({ boot, api, session }), [boot, api, session]);
  return (
    <AppContext.Provider value={value}>
      <QueryClientProvider client={queries}>{children}</QueryClientProvider>
    </AppContext.Provider>
  );
}
