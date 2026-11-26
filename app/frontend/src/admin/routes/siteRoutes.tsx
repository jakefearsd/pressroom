import type { RouteObject } from 'react-router';
import type { FrameHandle } from '../frame/Frame';
import { BusinessEdit } from '../screens/BusinessEdit';
import { Businesses } from '../screens/Businesses';
import { GlobalConfig } from '../screens/GlobalConfig';
import { Maintenance } from '../screens/Maintenance';
import { Setup } from '../screens/Setup';
import { RequireAdmin } from './RequireAdmin';

const admin: { handle: FrameHandle } = { handle: { scope: 'admin' } };
const guarded = (element: React.ReactNode) => <RequireAdmin>{element}</RequireAdmin>;

/**
 * Wave 4, site group: Global Config, Setup, Businesses and Maintenance. All site-admin screens
 * (the rail's Server admin menu), each behind the admin guard; spread into the framed routes.
 */
export const siteAdminRoutes: RouteObject[] = [
  { path: 'admin/config', element: guarded(<GlobalConfig />), ...admin },
  { path: 'admin/businesses', element: guarded(<Businesses />), ...admin },
  { path: 'admin/businesses/:id', element: guarded(<BusinessEdit />), ...admin },
  { path: 'admin/setup', element: guarded(<Setup />), ...admin },
  { path: 'admin/maintenance', element: guarded(<Maintenance />), ...admin }
];
