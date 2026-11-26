import { createBrowserRouter, type RouteObject } from 'react-router';
import { Frame, type FrameHandle } from './frame/Frame';
import { AdminHome, WeblogHome } from './screens/Placeholders';
import { Home } from './screens/Home';
import { NotFound } from './screens/NotFound';
import { Inbox } from './screens/people/Inbox';
import { Members } from './screens/people/Members';
import { Stylesheet } from './screens/people/Stylesheet';
import { TemplateEdit } from './screens/people/TemplateEdit';
import { Templates } from './screens/people/Templates';

const scoped = (scope: FrameHandle['scope']): { handle: FrameHandle } => ({ handle: { scope } });

/**
 * The routes, all inside the frame. `weblogs/:handle` and `admin` are placeholders so the
 * frame's weblog and site-admin rails are reachable; screens replace them wave by wave, and
 * each migrated screen also gets its `path` in routes/railTable.ts.
 */
export const appRoutes: RouteObject[] = [{
  element: <Frame />,
  children: [
    { index: true, element: <Home />, ...scoped('none') },
    { path: 'weblogs/:handle', element: <WeblogHome />, ...scoped('weblog') },
    { path: 'weblogs/:handle/templates', element: <Templates />, ...scoped('weblog') },
    { path: 'weblogs/:handle/templates/:id', element: <TemplateEdit />, ...scoped('weblog') },
    { path: 'weblogs/:handle/stylesheet', element: <Stylesheet />, ...scoped('weblog') },
    { path: 'weblogs/:handle/members', element: <Members />, ...scoped('weblog') },
    { path: 'weblogs/:handle/inbox', element: <Inbox />, ...scoped('weblog') },
    { path: 'admin', element: <AdminHome />, ...scoped('admin') },
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
