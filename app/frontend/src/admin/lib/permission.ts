import { useMe } from '../api/queries';

/**
 * What the signed-in user may do on a weblog, from /v1/me: a site admin and a POST or ADMIN
 * member publish; an EDIT_DRAFT member is an author whose publish is submitted for review
 * (the JSP's `userAnAuthor`).
 */
export function useWeblogRights(handle: string | undefined) {
  const me = useMe();
  const membership = me.data?.weblogs.find((w) => w.handle === handle)?.permission;
  const mayPublish = me.data?.globalAdmin === true || membership === 'ADMIN' || membership === 'POST';
  return { loaded: me.data !== undefined, mayPublish, isAuthor: !mayPublish };
}
