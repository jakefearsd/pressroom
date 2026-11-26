import { Outlet, matchPath, useLocation, useMatches } from 'react-router';
import { useMe, useMeta, useWeblog } from '../api/queries';
import { ApiError } from '../api/problem';
import { TimeZoneContext } from '../components/DateTime';
import { ProblemBanner } from '../components/ProblemBanner';
import { useBoot } from '../context';
import { useT } from '../i18n/messages';
import { RAIL_ENTRIES, entryTarget } from '../routes/railTable';
import { visibleGroups } from '../routes/visibility';
import { SessionExpiredBar } from '../session/SessionExpiredBar';
import { Banner, type SwitcherOption } from './Banner';
import { Footer } from './Footer';
import { Rail, type RailWeblog } from './Rail';
import { useState } from 'react';

/** Route `handle` data: which rail menu a screen shows (the JSP's desiredMenu); none for the main menu. */
export interface FrameHandle {
  scope: 'none' | 'weblog' | 'admin';
}

const SITE_NAME = 'Pressroom';

function isFrameHandle(value: unknown): value is FrameHandle {
  return typeof value === 'object' && value !== null && 'scope' in value;
}

/**
 * The app frame the tiles layouts were: banner, rail (`#adminRail`), the page card, footer.
 * What the rail shows is computed from /v1/me and /v1/meta with the menu XML's own rules
 * (routes/visibility.ts); the weblog comes from the URL (`/weblogs/:handle/...`).
 */
export function Frame() {
  const t = useT();
  const boot = useBoot();
  const location = useLocation();
  const matches = useMatches();
  const last = matches[matches.length - 1];
  const scope = isFrameHandle(last?.handle) ? last.handle.scope : 'none';
  const handle = last?.params['handle'];
  const [railOpen, setRailOpen] = useState(false);

  const me = useMe();
  const meta = useMeta();
  const weblogQuery = useWeblog(handle);
  const weblogView = weblogQuery.data;

  const membership = me.data?.weblogs.find((w) => w.handle === handle);
  const active = RAIL_ENTRIES.find((e) => e.path !== undefined && matchPath({ path: e.path, end: false }, location.pathname));
  const groups = me.data !== undefined && meta.data !== undefined && scope !== 'none'
    ? visibleGroups(scope, {
      globalAdmin: me.data.globalAdmin,
      membership: membership?.permission,
      features: { customThemes: meta.data.features.customThemes, groupBlogging: meta.data.features.groupBlogging }
    })
    : [];

  const siteUrl = meta.data?.siteUrl;
  let weblog: RailWeblog | undefined;
  if (handle !== undefined) {
    const domain = weblogView?.customDomain;
    weblog = {
      handle,
      name: weblogView?.name ?? membership?.name ?? handle,
      visible: weblogView?.active ?? false,
      url: domain !== undefined && domain !== '' ? `https://${domain}/` : siteUrl === undefined ? undefined
        : `${siteUrl}/${encodeURIComponent(handle)}/`
    };
  }

  // The switcher keeps the screen the reader is on when it is a rail screen; else it lands on entries.
  const switchTarget = active?.switchable === true ? active : RAIL_ENTRIES.find((e) => e.id === 'entries');
  const memberships = me.data?.weblogs ?? [];
  const switcher: SwitcherOption[] = memberships.length < 2 || switchTarget === undefined ? [] : memberships.map((w) => ({
    handle: w.handle,
    name: w.name === '' ? w.handle : w.name,
    target: entryTarget(switchTarget, boot.contextPath, w.handle)
  }));

  const failure = [me.error, meta.error, weblogQuery.error].find((e) => e !== null && !(e instanceof ApiError && e.status === 401));

  return (
    <TimeZoneContext.Provider value={weblogView?.timeZone}>
      <Banner siteName={SITE_NAME} switcher={switcher} currentHandle={handle} currentName={weblog?.name} />
      <div className="container-fluid">
        <div className="row">
          <div className="col-md-3 pressroom-column-left">
            <button type="button" className="btn btn-secondary btn-sm d-md-none rail-toggle"
                    aria-expanded={railOpen} aria-controls="adminRailColumn"
                    onClick={() => { setRailOpen((o) => !o); }}>
              <span className="bi bi-list" aria-hidden="true" /> {t('adminRail.toggle')}
            </button>
            <div className={`collapse d-md-block${railOpen ? ' show' : ''}`} id="adminRailColumn">
              <Rail weblog={weblog} groups={groups} active={active} />
            </div>
          </div>
          <main className="col-md-9 pressroom-column-right">
            <SessionExpiredBar />
            <div className="card">
              <div className={`card-body${scope === 'none' ? '' : ' pressroom-content-body'}`}>
                <Outlet />
                <ProblemBanner error={failure} />
              </div>
            </div>
          </main>
        </div>
      </div>
      <Footer colophon={{ version: boot.version }} />
    </TimeZoneContext.Provider>
  );
}
