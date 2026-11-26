import { Outlet, matchPath, useLocation, useMatches } from 'react-router';
import { useMe, useMeta, useWeblog } from '../api/queries';
import { ApiError } from '../api/problem';
import { TimeZoneContext } from '../components/DateTime';
import { ProblemBanner } from '../components/ProblemBanner';
import { useBoot } from '../context';
import { plainText, useT } from '../i18n/messages';
import { RAIL_ENTRIES, entryTarget } from '../routes/railTable';
import { visibleGroups } from '../routes/visibility';
import { SessionExpiredBar } from '../session/SessionExpiredBar';
import { Banner, type SwitcherOption } from './Banner';
import { Footer } from './Footer';
import { Rail, type RailWeblog } from './Rail';
import { useState, type ComponentType } from 'react';

/** Route `handle` data: which rail menu a screen shows (the JSP's desiredMenu); none for the main menu. */
export interface FrameHandle {
  scope: 'none' | 'weblog' | 'admin';
  /** The page's sidebar (`aside#adminSidebar`, under the rail), as the tiles layouts' tile_sidebar was. */
  sidebar?: ComponentType;
}

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
  const Sidebar = isFrameHandle(last?.handle) ? last.handle.sidebar : undefined;
  const [railOpen, setRailOpen] = useState(false);

  const me = useMe();
  const meta = useMeta();
  // The API never serves the site space, so the frame knows it from the shell and asks for nothing.
  const siteSpace = handle !== undefined && handle === boot.site.siteSpaceHandle;
  const weblogQuery = useWeblog(siteSpace ? undefined : handle);
  const weblogView = weblogQuery.data;

  const membership = me.data?.weblogs.find((w) => w.handle === handle);
  const active = RAIL_ENTRIES.find((e) => e.path !== undefined && matchPath({ path: e.path, end: false }, location.pathname));
  const groups = me.data !== undefined && meta.data !== undefined && scope !== 'none'
    ? visibleGroups(scope, {
      globalAdmin: me.data.globalAdmin,
      membership: membership?.permission,
      features: { customThemes: meta.data.features.customThemes, groupBlogging: meta.data.features.groupBlogging },
      siteSpace
    })
    : [];

  let weblog: RailWeblog | undefined;
  if (siteSpace && handle !== undefined) {
    weblog = { handle, name: plainText(t('mainMenu.site.title')), visible: false, url: undefined };
  } else if (handle !== undefined) {
    const domain = weblogView?.customDomain;
    weblog = {
      handle,
      name: weblogView?.name ?? membership?.name ?? handle,
      visible: weblogView?.active ?? false,
      url: weblogView?.absoluteUrl ?? (domain !== undefined && domain !== '' ? `https://${domain}/` : undefined)
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
      <Banner siteName={boot.site.name} switcher={switcher} currentHandle={handle} currentName={weblog?.name} />
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
              {Sidebar === undefined ? null : <aside className="sidebar" id="adminSidebar"><Sidebar /></aside>}
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
      <Footer colophon={{ ...boot.colophon, version: boot.version }} />
    </TimeZoneContext.Provider>
  );
}
