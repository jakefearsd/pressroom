import { Link, useSearchParams } from 'react-router';
import { DateTime } from '../../components/DateTime';
import { EmptyState } from '../../components/EmptyState';
import { ProblemBanner } from '../../components/ProblemBanner';
import { useT } from '../../i18n/messages';
import { PageTitle } from '../../frame/PageTitle';
import { useUsers, type Kind, type UserView } from './queries';

function kindOf(raw: string | null): Kind {
  return raw === 'people' || raw === 'agents' ? raw : undefined;
}

function Role({ user }: { user: UserView }) {
  const t = useT();
  const roles = user.globalRoles ?? [];
  return <>{roles.includes('admin') ? t('userList.role.siteAdmin') : roles.includes('editor') ? t('userList.role.author') : t('userList.role.reader')}</>;
}

/** Admin -> Users: every account, All / People / Agents tabs and the server-side filter (Users list, UserAdmin.jsp). */
export function UserList() {
  const t = useT();
  const [params, setParams] = useSearchParams();
  const kind = kindOf(params.get('kind'));
  const q = params.get('q') ?? '';
  const users = useUsers(kind, q);
  const tab = (to: Kind) => `/admin/users${new URLSearchParams({ ...(to ? { kind: to } : {}), ...(q === '' ? {} : { q }) }).toString().replace(/^(?=.)/, '?')}`;

  return (
    <div id="userListPage">
      <PageTitle>{t('userAdmin.title.searchUser')}</PageTitle>
      <ProblemBanner error={users.error} />
      <p className="subtitle">{t('userAdmin.subtitle.searchUser')}</p>

      <div className="d-flex flex-wrap gap-2 mb-3">
        <Link className="btn btn-primary btn-sm" id="newPersonLink" to="/admin/users/new">{t('userList.newPerson')}</Link>
        <Link className="btn btn-secondary btn-sm" id="newAgentLink" to="/admin/agents/new">{t('userList.newAgent')}</Link>
      </div>

      <nav className="nav nav-tabs mb-3" aria-label={t('userList.tabs')}>
        <Link className={`nav-link${kind === undefined ? ' active' : ''}`} id="userListTabAll" to={tab(undefined)}
              {...(kind === undefined ? { 'aria-current': 'page' as const } : {})}>{t('userList.tab.all')}</Link>
        <Link className={`nav-link${kind === 'people' ? ' active' : ''}`} id="userListTabPeople" to={tab('people')}
              {...(kind === 'people' ? { 'aria-current': 'page' as const } : {})}>{t('userList.tab.people')}</Link>
        <Link className={`nav-link${kind === 'agents' ? ' active' : ''}`} id="userListTabAgents" to={tab('agents')}
              {...(kind === 'agents' ? { 'aria-current': 'page' as const } : {})}>{t('userList.tab.agents')}</Link>
      </nav>

      <form className="d-flex flex-wrap gap-2 mb-3" id="userListFilterForm" key={q}
            onSubmit={(event) => {
              event.preventDefault();
              const text = String(new FormData(event.currentTarget).get('q') ?? '').trim();
              setParams({ ...(kind ? { kind } : {}), ...(text === '' ? {} : { q: text }) });
            }}>
        <label htmlFor="userListFilter" className="visually-hidden">{t('userList.filter')}</label>
        <input type="search" className="form-control" id="userListFilter" name="q" defaultValue={q}
               placeholder={t('userList.filter')} />
        <button type="submit" className="btn btn-secondary" id="userListFilterSubmit">{t('userList.filter.apply')}</button>
      </form>

      {users.data === undefined ? null : users.data.length > 0 ? (
        <div className="table-responsive">
          <table className="pressroomtable table" id="userListTable">
            <tbody>
              <tr>
                <th scope="col">{t('userList.column.account')}</th>
                <th scope="col">{t('userList.column.kind')}</th>
                <th scope="col">{t('userList.column.role')}</th>
                <th scope="col">{t('userList.column.status')}</th>
                <th scope="col">{t('userList.column.weblogs')}</th>
                <th scope="col">{t('userList.column.lastActive')}</th>
              </tr>
              {users.data.map((row) => {
                const agent = row.kind === 'AGENT';
                const name = row.screenName ?? row.userName ?? '';
                return (
                  <tr key={row.userName}>
                    <td>
                      <Link to={`/admin/${agent ? 'agents' : 'users'}/${encodeURIComponent(row.userName ?? '')}`}
                            data-username={row.userName}>{name}</Link>
                      <br /><span className="data">{row.userName}</span>
                    </td>
                    <td>{agent ? t('userList.kind.agent') : t('userList.kind.person')}</td>
                    <td><Role user={row} /></td>
                    <td>
                      {row.enabled === true
                        ? <span className="status-pill status-enabled">{t('userList.status.enabled')}</span>
                        : <span className="status-pill status-disabled">{t('userList.status.disabled')}</span>}
                    </td>
                    <td className="data">{row.weblogCount}</td>
                    <td className="data">
                      {row.lastActive === undefined
                        ? <span className="text-muted">{t('userList.lastActive.never')}</span>
                        : <DateTime value={row.lastActive} />}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : (
        <EmptyState title={t('userList.empty.title')} body={t('userList.empty.body')} />
      )}
    </div>
  );
}
