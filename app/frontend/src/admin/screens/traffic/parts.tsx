import { Link, useLocation } from 'react-router';
import { useT } from '../../i18n/messages';
import { PERIODS, type Period, type TrafficView, type SiteTrafficView } from './model';

/** A link back to the page under the current route with these query parameters (client-side). */
export function usePageLink(): (params: Record<string, string>) => string {
  const { pathname } = useLocation();
  return (params) => `${pathname}?${new URLSearchParams(params).toString()}`;
}

/** The period switch (TrafficHeader.jsp), carrying `keep` (tab, sort) along. */
export function PeriodNav({ period, keep }: { period: Period; keep: Record<string, string> }) {
  const t = useT();
  const link = usePageLink();
  return (
    <nav className="traffic-period" aria-label={t('traffic.period.label')}>
      {PERIODS.map((p) => (
        <Link key={p} to={link({ period: p, ...keep })}
              className={`traffic-period-link${p === period ? ' on' : ''}`}
              {...(p === period ? { 'aria-current': 'true' as const } : {})}>
          {t(`traffic.period.${p}`)}
        </Link>
      ))}
    </nav>
  );
}

/** The state notice (and the failed-registration notice) under the period switch. */
export function Notice({ view }: { view: Pick<TrafficView | SiteTrafficView, 'noticeKey' | 'ageMinutes'> }) {
  const t = useT();
  if (view.noticeKey === '') {
    return null;
  }
  const suffix = view.noticeKey.slice('traffic.state.'.length);
  return <p className={`traffic-notice traffic-notice-${suffix}`} role="status">{t(view.noticeKey, view.ageMinutes)}</p>;
}
