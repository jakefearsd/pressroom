import type { ReactNode } from 'react';
import { useBoot } from '../../context';
import { NotFound } from '../NotFound';

/**
 * The JSP admin space answered a non-admin with a refusal; here the screen is never
 * rendered (so nothing is fetched) and the page says it does not exist. The API refuses
 * the same calls to a non-admin regardless: this guard is the screen's half.
 */
export function AdminOnly({ children }: { children: ReactNode }) {
  return useBoot().user.globalPermission === 'admin' ? children : <NotFound />;
}
