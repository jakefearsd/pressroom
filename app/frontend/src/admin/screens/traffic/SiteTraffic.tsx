import { useState } from 'react';
import { Link } from 'react-router';
import { useSearchParams } from 'react-router';
import { useSiteTraffic } from '../../api/queries';
import { ProblemBanner } from '../../components/ProblemBanner';
import { useBoot } from '../../context';
import { PageTitle } from '../../frame/PageTitle';
import { useT } from '../../i18n/messages';
import { DASH, figure, periodOf, siteTrafficView, type PostLine, type Row } from './model';
import { Notice, PeriodNav } from './parts';

function TopPosts({ posts, locale }: { posts: PostLine[]; locale: string }) {
  const t = useT();
  if (posts.length === 0) {
    return <p className="traffic-empty">{t('traffic.empty.posts')}</p>;
  }
  return (
    <div className="table-responsive">
      <table className="pressroomtable table table-striped traffic-table" width="100%">
        <tbody>
          <tr>
            <th scope="col" className="pressroomtable">{t('traffic.column.post')}</th>
            <th scope="col" className="pressroomtable n">{t('traffic.column.views')}</th>
            <th scope="col" className="pressroomtable n">{t('traffic.column.visitors')}</th>
          </tr>
          {posts.map((top) => (
            <tr key={`${top.entryId ?? ''}${top.path}`}>
              <td>{top.title !== null && top.title !== '' ? top.title : <span className="data">{top.path}</span>}</td>
              <td className="n">{figure(top.views, locale)}</td>
              <td className="n">{top.visitors < 0 ? DASH : figure(top.visitors, locale)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function TopSources({ sources }: { sources: Row[] }) {
  const t = useT();
  if (sources.length === 0) {
    return <p className="traffic-empty">{t('traffic.empty.tab')}</p>;
  }
  return (
    <div className="table-responsive">
      <table className="pressroomtable table table-striped traffic-table" width="100%">
        <tbody>
          <tr>
            <th scope="col" className="pressroomtable">{t('traffic.column.name.sources')}</th>
            <th scope="col" className="pressroomtable n" title={t('siteTraffic.sources.why')}>{t('traffic.column.value.sources')}</th>
          </tr>
          {sources.map((row) => (
            <tr key={`${row.group}:${row.label}`}><td>{row.label}</td><td className="n">{row.value}</td></tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Admin > Traffic (SiteTraffic.jsp): every blog's numbers, a total for views and visits only, top posts and sources. */
export function SiteTraffic() {
  const t = useT();
  const { uiLocale: locale } = useBoot();
  const [search] = useSearchParams();
  const period = periodOf(search.get('period'));
  const traffic = useSiteTraffic(period);
  const [now] = useState(() => new Date());
  const view = traffic.data === undefined ? undefined : siteTrafficView(traffic.data, locale, now);
  return (
    <>
      <PageTitle>{t('siteTraffic.title')}</PageTitle>
      <div id="siteTrafficPage">
            <p className="subtitle">{t('siteTraffic.subtitle')}</p>
      <PeriodNav period={period} keep={{}} />
      <ProblemBanner error={traffic.error} />
      {view === undefined ? null : (
        <>
          <Notice view={view} />
          {view.showBody ? (
            <>
              <div className="table-responsive">
                <table className="pressroomtable table table-striped traffic-table" width="100%">
                  <tbody>
                    <tr>
                      <th scope="col" className="pressroomtable">{t('siteTraffic.column.blog')}</th>
                      <th scope="col" className="pressroomtable n" title={t('siteTraffic.visitors.why')}>{t('traffic.column.visitors')}</th>
                      <th scope="col" className="pressroomtable n">{t('traffic.column.views')}</th>
                      <th scope="col" className="pressroomtable n">{t('traffic.tile.visits')}</th>
                      <th scope="col" className="pressroomtable n">{t('siteTraffic.column.change')}</th>
                      <th scope="col" className="pressroomtable">{t('siteTraffic.column.topPost')}</th>
                    </tr>
                    {view.blogs.map((line) => (
                      <tr key={line.handle}>
                        <td>
                          <Link to={`/weblogs/${encodeURIComponent(line.handle)}/traffic?period=${period}`}>
                            {line.siteSpace ? t('siteTraffic.siteSpace') : line.name}
                          </Link>
                        </td>
                        <td className="n">{line.visitors}</td>
                        <td className="n">{line.views}</td>
                        <td className="n">{line.visits}</td>
                        <td className={`n ${line.changeClass}`}>{line.change}</td>
                        <td>
                          {line.standing === 'NOT_REGISTERED' ? <span className="traffic-empty">{t('siteTraffic.standing.notRegistered')}</span>
                            : line.standing === 'NO_DATA' ? <span className="traffic-empty">{t('siteTraffic.standing.noData')}</span>
                              : line.topPostTitle}
                        </td>
                      </tr>
                    ))}
                    <tr className="traffic-total">
                      <th scope="row" className="pressroomtable">{t('siteTraffic.total')}</th>
                      <td className="n"><span title={t('siteTraffic.visitors.why')}>{DASH}</span></td>
                      <td className="n">{view.totalViews}</td>
                      <td className="n">{view.totalVisits}</td>
                      <td className="n" />
                      <td />
                    </tr>
                  </tbody>
                </table>
              </div>
              <h2 className="traffic-heading">{t('siteTraffic.topPosts')}</h2>
              <TopPosts posts={view.posts} locale={locale} />
              <h2 className="traffic-heading">{t('siteTraffic.topSources')}</h2>
              <TopSources sources={view.sources} />
            </>
          ) : null}
          <p className="traffic-fresh">{view.asOfText === null ? null : t('traffic.asOf', view.asOfText)}</p>
        </>
      )}
      </div>
    </>
  );
}
