import { useEffect, useRef } from 'react';
import { useBoot } from '../context';
import { useT } from '../i18n/messages';

/** Warn this long before the container would end the session (SessionExpiryBar.jsp: 120 seconds). */
const LEAD_SECONDS = 120;

/**
 * The session-expiring-soon bar the source editors carry (SessionExpiryBar.jsp, reproduced): purely
 * local arithmetic against the container's own inactivity timeout from the bootstrap, so no endpoint
 * is polled and nothing here keeps the session alive. The clock restarts on any key, click or input,
 * which measures inactivity the way the container does; drift can only warn early. A session no
 * longer than the lead time plus a minute never warns, since a bar that is always up teaches people to
 * ignore it. (The signed-out bar, SessionExpiredBar, is the other half: it follows a 401.)
 */
export function SessionExpiryBar() {
  const t = useT();
  const { sessionTimeoutSeconds } = useBoot();
  const bar = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!(sessionTimeoutSeconds > LEAD_SECONDS + 60)) {
      return undefined;
    }
    const warnAfterMs = (sessionTimeoutSeconds - LEAD_SECONDS) * 1000;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const arm = () => {
      if (bar.current !== null) {
        bar.current.hidden = true;
      }
      clearTimeout(timer);
      timer = setTimeout(() => {
        if (bar.current !== null) {
          bar.current.hidden = false;
        }
      }, warnAfterMs);
    };
    const events = ['keydown', 'click', 'input'] as const;
    events.forEach((type) => { document.addEventListener(type, arm, true); });
    arm();
    return () => {
      clearTimeout(timer);
      events.forEach((type) => { document.removeEventListener(type, arm, true); });
    };
  }, [sessionTimeoutSeconds]);

  return (
    <div id="sessionExpiringBar" ref={bar} className="draft-bar" hidden role="status" aria-live="polite">
      <span className="draft-bar-text">{t('session.expiringSoon')}</span>
    </div>
  );
}
