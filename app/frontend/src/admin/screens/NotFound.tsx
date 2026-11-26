import { useT } from '../i18n/messages';
import { EmptyState } from '../components/EmptyState';

export function NotFound() {
  const t = useT();
  return <EmptyState title={t('app.notFound')} />;
}
