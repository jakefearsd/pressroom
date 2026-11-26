import { useT } from '../i18n/messages';
import { PageTitle } from '../frame/PageTitle';

/** Placeholder: the app home (the main menu's replacement) is the next task. */
export function Home() {
  const t = useT();
  return <PageTitle>{t('yourWebsites.title')}</PageTitle>;
}
