import { useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { useBlogTraffic, useWeblog } from '../../api/queries';
import { ProblemBanner } from '../../components/ProblemBanner';
import { useBoot } from '../../context';
import { PageTitle } from '../../frame/PageTitle';
import { useT } from '../../i18n/messages';
import { Notice, PeriodNav, usePageLink } from './parts';
import {
  DASH, TABS, figure, periodOf, sortOf, tabOf, trafficView, type Period, type Sort, type Tab, type TrafficView
} from './model';

const SORT_COLUMNS = ['views', 'visitors', 'trend'] as const;
const SPEED_METRICS = ['lcp', 'inp', 'cls', 'fcp', 'ttfb'];

function Tiles({ view }: { view: TrafficView }) {
  const t = useT();
  return (
    <ul className="traffic-tiles" aria-label={t('traffic.tile.changeNote')}>
      {view.tiles.map((tile) => (
        <li className="traffic-tile" key={tile.labelKey}>
          <span className="traffic-tile-label">{t(tile.labelKey)}</span>
          <span className="traffic-tile-value">{tile.value}</span>
          <span className={`traffic-tile-change ${tile.trend}`}>{tile.change}</span>
        </li>
      ))}
    </ul>
  );
}

function ChartSvg({ view }: { view: TrafficView }) {
  const t = useT();
  const { chart } = view;
  return (
    <svg className="traffic-chart" viewBox={`0 0 ${String(chart.width)} ${String(chart.height)}`} role="img"
         aria-label={t('traffic.chart.label')} preserveAspectRatio="none">
      <title>{t('traffic.chart.label')}</title>
      <line className="traffic-baseline" x1="0" y1={chart.plotHeight} x2={chart.width} y2={chart.plotHeight} />
      {chart.bars.map((bar) => (
        <g key={bar.x}>
          <rect className="traffic-bar" x={bar.x} y={bar.y} width={bar.width} height={bar.height}>
            <title>{bar.title}</title>
          </rect>
          {bar.label === null ? null : (
            <text className="traffic-axis" x={bar.x + bar.width / 2} y={chart.height - 6} textAnchor="middle">{bar.label}</text>
          )}
        </g>
      ))}
    </svg>
  );
}

function PostsTable({ view, sort, handle, period, locale }: {
  view: TrafficView; sort: Sort; handle: string; period: Period; locale: string;
}) {
  const t = useT();
  const { contextPath } = useBoot();
  const link = usePageLink();
  if (view.posts.length === 0) {
    return <p className="traffic-empty">{t('traffic.empty.posts')}</p>;
  }
  return (
    <>
      <div className="table-responsive">
        <table className="pressroomtable table table-striped traffic-table" width="100%">
          <tbody>
            <tr>
              <th scope="col" className="pressroomtable">{t('traffic.column.post')}</th>
              {SORT_COLUMNS.map((col) => (
                <th key={col} scope="col" className="pressroomtable n" {...(col === sort ? { 'aria-sort': 'descending' as const } : {})}>
                  <Link to={link({ period, tab: 'posts', sort: col })} className="quiet-link">{t(`traffic.column.${col}`)}</Link>
                </th>
              ))}
            </tr>
            {view.posts.map((line) => (
              <tr key={`${line.entryId ?? ''}${line.path}`}>
                <td>
                  {line.entryId !== null && line.title !== null && line.title !== '' ? (
                    <a href={`${contextPath}/pressroom-ui/authoring/entryEdit.rol?weblog=${encodeURIComponent(handle)}&bean.id=${encodeURIComponent(line.entryId)}`}>{line.title}</a>
                  ) : <span className="data">{line.path}</span>}
                </td>
                <td className="n">{figure(line.views, locale)}</td>
                <td className="n">{line.visitors < 0 ? DASH : figure(line.visitors, locale)}</td>
                <td className={`n ${line.trendClass}`}>{line.trendText}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {view.pathsCappedAt > 0 ? <p className="traffic-empty">{t('traffic.posts.capped', view.pathsCappedAt)}</p> : null}
    </>
  );
}

/** Which rows open a new group: a grouped row whose group differs from the last grouped row's. */
function groupHeadings(rows: readonly { group: string }[]): boolean[] {
  let last = '';
  return rows.map((row) => {
    const opens = row.group !== '' && row.group !== last;
    last = row.group === '' ? last : row.group;
    return opens;
  });
}

function BreakdownTable({ view, tab }: { view: TrafficView; tab: Exclude<Tab, 'posts'> }) {
  const t = useT();
  if (view.rows.length === 0) {
    return <p className="traffic-empty">{t(tab === 'speed' ? 'traffic.empty.speed' : 'traffic.empty.tab')}</p>;
  }
  const headings = groupHeadings(view.rows);
  return (
    <div className="table-responsive">
      <table className="pressroomtable table table-striped traffic-table" width="100%">
        <tbody>
          <tr>
            <th scope="col" className="pressroomtable">{t(`traffic.column.name.${tab}`)}</th>
            <th scope="col" className="pressroomtable n">{t(`traffic.column.value.${tab}`)}</th>
          </tr>
          {view.rows.map((row, i) => (
            <FragmentRow key={`${row.group}:${row.label}`} heading={headings[i] === true ? row.group : null}>
              <td>{tab === 'speed' && SPEED_METRICS.includes(row.label) ? t(`traffic.speed.${row.label}`) : row.label}</td>
              <td className="n">{row.value}</td>
            </FragmentRow>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function FragmentRow({ heading, children }: { heading: string | null; children: React.ReactNode }) {
  const t = useT();
  return (
    <>
      {heading === null ? null : (
        <tr className="traffic-group"><th scope="rowgroup" colSpan={2}>{t(`traffic.group.${heading}`)}</th></tr>
      )}
      <tr>{children}</tr>
    </>
  );
}

/** A weblog's Traffic page (Traffic.jsp + TrafficBody.jsp): numbers from the local snapshot only. */
export function WeblogTraffic() {
  const t = useT();
  const boot = useBoot();
  const handle = useParams()['handle'] ?? '';
  const [search] = useSearchParams();
  const period = periodOf(search.get('period'));
  const tab = tabOf(search.get('tab'));
  const sort = sortOf(search.get('sort'));
  const siteSpace = handle === boot.site.siteSpaceHandle;
  const link = usePageLink();
  const weblog = useWeblog(siteSpace ? undefined : handle);
  const traffic = useBlogTraffic(handle, period, siteSpace);
  const [now] = useState(() => new Date());
  const locale = boot.uiLocale;
  const view = traffic.data === undefined ? undefined : trafficView(traffic.data, tab, sort, locale, now);
  const name = siteSpace ? t('mainMenu.site.title') : weblog.data?.name ?? handle;
  return (
    <>
      <PageTitle>{t('traffic.title')}</PageTitle>
      <div id="trafficPage">
      <p className="subtitle">{t('traffic.subtitle', name)}</p>
      <PeriodNav period={period} keep={{ tab, sort }} />
      <ProblemBanner error={traffic.error} />
      {view === undefined ? null : (
        <>
          <Notice view={view} />
          {view.notRegisteredError === null ? null : (
            <p className="traffic-notice traffic-notice-notRegistered" role="status">
              {t('traffic.state.notRegistered', view.notRegisteredError)}
            </p>
          )}
          {view.showBody ? (
            <>
              <Tiles view={view} />
              <ChartSvg view={view} />
              <nav className="traffic-tabs" aria-label={t('traffic.tabs.label')}>
                {TABS.map((id) => (
                  <Link key={id} to={link({ period, tab: id })} className={`traffic-tab${id === tab ? ' on' : ''}`}
                        {...(id === tab ? { 'aria-current': 'page' as const } : {})}>
                    {t(`traffic.tab.${id}`)}
                  </Link>
                ))}
              </nav>
              {tab === 'posts'
                ? <PostsTable view={view} sort={sort} handle={handle} period={period} locale={locale} />
                : <BreakdownTable view={view} tab={tab} />}
            </>
          ) : null}
          <p className="traffic-fresh">
            {view.asOfText === null ? null : t('traffic.asOf', view.asOfText)}
            {view.umamiUrl === null ? null : (
              <> <a href={view.umamiUrl} target="_blank" rel="noopener" className="quiet-link">{t('traffic.openInUmami')}</a></>
            )}
          </p>
        </>
      )}
      </div>
    </>
  );
}
