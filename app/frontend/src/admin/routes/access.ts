import { useMe } from '../api/queries';

/**
 * Whether the signed-in user may administer a weblog (the screens behind the Design and
 * Website rail groups): a site admin, or a member holding ADMIN. Undefined until /v1/me loads.
 */
export function useCanAdminister(handle: string | undefined): boolean | undefined {
  const me = useMe();
  if (me.data === undefined) {
    return undefined;
  }
  return me.data.globalAdmin || me.data.weblogs.some((w) => w.handle === handle && w.permission === 'ADMIN');
}
