import { createBrowserRouter, type RouteObject } from 'react-router';
import { Frame, type FrameHandle } from './frame/Frame';
import { AdminHome, WeblogHome } from './screens/Placeholders';
import { Home } from './screens/Home';
import { NotFound } from './screens/NotFound';
import { Categories } from './screens/Categories';
import { Comments } from './screens/Comments';
import { Entries } from './screens/Entries';
import { Trash } from './screens/Trash';

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
    { path: 'weblogs/:handle/entries', element: <Entries />, ...scoped('weblog') },
    { path: 'weblogs/:handle/trash', element: <Trash />, ...scoped('weblog') },
    { path: 'weblogs/:handle/comments', element: <Comments />, ...scoped('weblog') },
    { path: 'weblogs/:handle/categories', element: <Categories />, ...scoped('weblog') },
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
