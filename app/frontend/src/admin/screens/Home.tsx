import { useT } from '../i18n/messages';
import { PageTitle } from '../frame/PageTitle';
import { ProblemBanner } from '../components/ProblemBanner';
import { useFlash } from '../session/flash';

/** Placeholder: the app home (the main menu's replacement) is the next task. */
export function Home() {
  const t = useT();
  const flash = useFlash();
  return (
    <>
      <PageTitle>{t('yourWebsites.title')}</PageTitle>
      {flash === undefined ? null : <ProblemBanner messages={[flash]} />}
    </>
  );
}
