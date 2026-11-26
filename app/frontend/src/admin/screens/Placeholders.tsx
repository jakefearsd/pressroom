import { useParams } from 'react-router';
import { useT } from '../i18n/messages';
import { PageTitle } from '../frame/PageTitle';
import { useWeblog } from '../api/queries';
import { useBoot } from '../context';

/** Placeholder weblog-scoped route so the frame's weblog context is reachable until screens land. */
export function WeblogHome() {
  const handle = useParams()['handle'];
  const { site } = useBoot();
  const weblog = useWeblog(handle === site.siteSpaceHandle ? undefined : handle);
  return <PageTitle>{weblog.data?.name ?? handle ?? ''}</PageTitle>;
}

/** Placeholder site-admin route, for the same reason. */
export function AdminHome() {
  const t = useT();
  return <PageTitle>{t('tabbedmenu.admin')}</PageTitle>;
}
