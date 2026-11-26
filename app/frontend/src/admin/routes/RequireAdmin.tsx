import type { ReactNode } from 'react';
import { useBoot } from '../context';
import { NotFound } from '../screens/NotFound';

/**
 * Route guard for the Server admin screens: only a site admin gets the screen, anyone else the
 * not-found page (the rail never links these for them, and the API refuses them anyway).
 */
export function RequireAdmin({ children }: { children: ReactNode }) {
  return useBoot().user.globalPermission === 'admin' ? <>{children}</> : <NotFound />;
}
