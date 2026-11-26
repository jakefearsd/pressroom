import type { RouteObject } from 'react-router';
import type { FrameHandle } from '../../frame/Frame';
import { RequireAdmin } from '../../routes/RequireAdmin';
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
  { path: 'admin/users', element: <RequireAdmin><UserList /></RequireAdmin>, ...admin },
  { path: 'admin/users/new', element: <RequireAdmin><UserEdit /></RequireAdmin>, ...admin },
  { path: 'admin/users/:userName', element: <RequireAdmin><UserEdit /></RequireAdmin>, ...admin },
  { path: 'admin/agents/new', element: <RequireAdmin><AgentCreate /></RequireAdmin>, ...admin },
  { path: 'admin/agents/:userName', element: <RequireAdmin><AgentPage /></RequireAdmin>, ...admin },
  { path: 'admin/tokens', element: <RequireAdmin><SiteTokens /></RequireAdmin>, ...admin },
  { path: 'profile', element: <Profile />, ...own },
  { path: 'profile/tokens', element: <ProfileTokens />, ...own }
];
