import type { ReactNode } from 'react';
import { useMe } from '../api/queries';
import { RANK, weblogLevel } from '../routes/visibility';
import type { WeblogLevel } from '../routes/railTable';
import { NotFound } from '../screens/NotFound';

/**
 * What the signed-in user may do on a weblog, from /v1/me: a site admin and a POST or ADMIN
 * member publish (and manage media); an EDIT_DRAFT member is an author whose publish is
 * submitted for review (the JSP's `userAnAuthor`). The ONE place a weblog-level decision is
 * read: screens call this or wrap themselves in `RequireLevel`, never re-derive it.
 */
export function useWeblogRights(handle: string | undefined) {
  const me = useMe();
  const membership = me.data?.weblogs.find((w) => w.handle === handle)?.permission;
  const mayPublish = me.data?.globalAdmin === true || membership === 'ADMIN' || membership === 'POST';
  return { loaded: me.data !== undefined, mayPublish, isAuthor: !mayPublish };
}

/**
 * Renders its children only for someone who holds `level` on the weblog (a site admin holds
 * every one): what requiredWeblogPermissionActions did for the JSP controllers. Anyone else
 * sees the not-found treatment, as the API answers 404 for what the caller may not see.
 * (The site-admin counterpart is routes/RequireAdmin.)
 */
export function RequireLevel({ level, handle, children }: { level: WeblogLevel; handle: string; children: ReactNode }) {
  const me = useMe();
  if (me.data === undefined) {
    return null;
  }
  const membership = me.data.weblogs.find((w) => w.handle === handle)?.permission;
  return weblogLevel({ globalAdmin: me.data.globalAdmin, membership }) >= RANK[level] ? <>{children}</> : <NotFound />;
}
