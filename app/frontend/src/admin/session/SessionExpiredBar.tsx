import { useBoot, useSessionExpired } from '../context';
import { useT } from '../i18n/messages';
import { CHROME_LINKS } from '../routes/railTable';

/** The login page, returning here (ReturnPathLoginSuccessHandler's `return` is a path under the context). */
export function signInHref(contextPath: string, here: Pick<Location, 'pathname' | 'search' | 'hash'>): string {
  return `${contextPath}${CHROME_LINKS.login.legacy}?return=${encodeURIComponent(here.pathname + here.search + here.hash)}`;
}

/**
 * The session-expired treatment: SessionExpiryBar.jsp's look (`.draft-bar`, a polite offer
 * not an alarm) with a sign-in link back to the current URL. Unsaved editor text is kept
 * by useDraftKeeper and offered back after sign-in.
 */
export function SessionExpiredBar() {
  const t = useT();
  const { contextPath } = useBoot();
  if (!useSessionExpired()) {
    return null;
  }
  return (
    <div id="sessionExpiryBar" className="draft-bar" role="alert">
      <span className="draft-bar-text">{t('session.expired')}</span>
      <a className="draft-bar-restore" href={signInHref(contextPath, window.location)}>{t('navigationBar.login')}</a>
    </div>
  );
}
