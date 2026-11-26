import { createBrowserRouter, type RouteObject } from 'react-router';
import { Frame, type FrameHandle } from './frame/Frame';
import { AdminHome, WeblogHome } from './screens/Placeholders';
import { Home, HomeSidebar } from './screens/Home';
import { SiteTraffic } from './screens/traffic/SiteTraffic';
import { WeblogTraffic } from './screens/traffic/WeblogTraffic';
import { NotFound } from './screens/NotFound';

const scoped = (scope: FrameHandle['scope'], sidebar?: FrameHandle['sidebar']): { handle: FrameHandle } =>
  ({ handle: { scope, ...(sidebar === undefined ? {} : { sidebar }) } });

/**
 * The routes, all inside the frame. `weblogs/:handle` and `admin` are placeholders so the
 * frame's weblog and site-admin rails are reachable; screens replace them wave by wave, and
 * each migrated screen also gets its `path` in routes/railTable.ts.
 */
export const appRoutes: RouteObject[] = [{
  element: <Frame />,
  children: [
    { index: true, element: <Home />, ...scoped('none', HomeSidebar) },
    { path: 'weblogs/:handle', element: <WeblogHome />, ...scoped('weblog') },
    { path: 'weblogs/:handle/traffic', element: <WeblogTraffic />, ...scoped('weblog') },
    { path: 'admin', element: <AdminHome />, ...scoped('admin') },
    { path: 'admin/traffic', element: <SiteTraffic />, ...scoped('admin') },
    { path: '*', element: <NotFound />, ...scoped('none') }
  ]
}];

/** Where the app lives: history routing under the context path (spec A1). */
export function appBasename(contextPath: string): string {
  return `${contextPath}/pressroom-ui/app`;
}

export function createAppRouter(contextPath: string) {
  return createBrowserRouter(appRoutes, { basename: appBasename(contextPath) });
}
