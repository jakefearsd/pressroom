import type { RouteObject } from 'react-router';
import type { FrameHandle } from '../../frame/Frame';
import { AdminOnly } from './AdminOnly';
import { AgentCreate } from './AgentCreate';
import { AgentPage } from './AgentPage';
import { Profile } from './Profile';
import { ProfileTokens } from './ProfileTokens';
import { SiteTokens } from './SiteTokens';
import { UserEdit } from './UserEdit';
import { UserList } from './UserList';

const admin: { handle: FrameHandle } = { handle: { scope: 'admin' } };
const own: { handle: FrameHandle } = { handle: { scope: 'none' } };

/**
 * Wave 4, people: Users, User edit, Agents, Site tokens (site admins only: the guard is the
 * JSP's admin-only URL space) and the signed-in user's Profile and My tokens.
 */
export const peopleRoutes: RouteObject[] = [
  { path: 'admin/users', element: <AdminOnly><UserList /></AdminOnly>, ...admin },
  { path: 'admin/users/new', element: <AdminOnly><UserEdit /></AdminOnly>, ...admin },
  { path: 'admin/users/:userName', element: <AdminOnly><UserEdit /></AdminOnly>, ...admin },
  { path: 'admin/agents/new', element: <AdminOnly><AgentCreate /></AdminOnly>, ...admin },
  { path: 'admin/agents/:userName', element: <AdminOnly><AgentPage /></AdminOnly>, ...admin },
  { path: 'admin/tokens', element: <AdminOnly><SiteTokens /></AdminOnly>, ...admin },
  { path: 'profile', element: <Profile />, ...own },
  { path: 'profile/tokens', element: <ProfileTokens />, ...own }
];
