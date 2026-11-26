import { useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { call } from '../../api/client';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { DateTime } from '../../components/DateTime';
import { EmptyState } from '../../components/EmptyState';
import { ProblemBanner } from '../../components/ProblemBanner';
import { useApi } from '../../context';
import { PageTitle } from '../../frame/PageTitle';
import { useT } from '../../i18n/messages';
import { useState } from 'react';
import { useSiteTokens } from './queries';
import { roleLabel } from './tokens';
import { useAction } from './useAction';

/** Admin -> API tokens (ApiTokens.jsp): every live token on the site; the toggle adds revoked and expired ones. */
export function SiteTokens() {
  const t = useT();
  const api = useApi();
  const queries = useQueryClient();
  const [params] = useSearchParams();
  const showAll = params.get('all') === 'true';
  const tokens = useSiteTokens(showAll);
  const [revoking, setRevoking] = useState<string | null>(null);
  const [messages, setMessages] = useState<string[]>([]);
  const revoke = useAction(async (id: string) => {
    await call(api.DELETE('/v1/admin/tokens/{id}', { params: { path: { id } } }));
    await queries.invalidateQueries({ queryKey: ['siteTokens'] });
  });
  const rows = tokens.data ?? [];

  return (
    <div id="apiTokensPage">
      <PageTitle>{t('apiTokens.admin.title')}</PageTitle>
      <ProblemBanner messages={messages} error={revoke.error ?? tokens.error} />
      <p className="subtitle">{t('apiTokens.admin.subtitle')}</p>
      <p>
        {showAll
          ? <Link to="/admin/tokens" id="apiTokensToggle">{t('apiTokens.admin.showLive')}</Link>
          : <Link to="/admin/tokens?all=true" id="apiTokensToggle">{t('apiTokens.admin.showAll')}</Link>}
      </p>

      {tokens.data === undefined ? null : rows.length > 0 ? (
        <div className="table-responsive">
          <table className="pressroomtable table" id="apiTokensTable">
            <tbody>
              <tr>
                <th scope="col">{t('apiTokens.owner')}</th>
                <th scope="col">{t('userList.column.kind')}</th>
                <th scope="col">{t('apiTokens.label')}</th>
                <th scope="col">{t('apiTokens.role')}</th>
                <th scope="col">{t('apiTokens.weblog')}</th>
                <th scope="col">{t('apiTokens.created')}</th>
                <th scope="col">{t('apiTokens.lastUsed')}</th>
                <th scope="col">{t('apiTokens.expiry')}</th>
                <th scope="col">{t('apiTokens.state')}</th>
              </tr>
              {rows.map((row) => (
                <tr key={row.id}>
                  <td>
                    {row.owner}
                    {row.issuedBy !== undefined && row.issuedBy !== '' && row.issuedBy !== row.owner ? (
                      <div className="entry-meta">{t('apiTokens.issuedBy')}: <span className="data">{row.issuedBy}</span></div>
                    ) : null}
                  </td>
                  <td>{row.ownerKind === 'AGENT' ? t('userList.kind.agent') : t('userList.kind.person')}</td>
                  <td>{row.label}</td>
                  <td>{roleLabel(row.scopeRole, t)}</td>
                  <td>
                    {row.scopeWeblog === undefined || row.scopeWeblog === ''
                      ? t('apiTokens.weblog.all') : <span className="data">{row.scopeWeblog}</span>}
                  </td>
                  <td className="data text-nowrap"><DateTime value={row.created} /></td>
                  <td className="data text-nowrap">
                    {row.lastUsedAt === undefined ? <span className="data-none">{t('apiTokens.lastUsed.never')}</span> : <DateTime value={row.lastUsedAt} />}
                  </td>
                  <td className="data text-nowrap">
                    {row.expiresAt === undefined ? <span className="data-none">{t('apiTokens.expiry.never')}</span> : <DateTime value={row.expiresAt} />}
                  </td>
                  <td>
                    {row.revokedAt !== undefined ? (
                      <>{t('apiTokens.state.revoked')} <DateTime value={row.revokedAt} /></>
                    ) : row.live !== true ? (
                      t('apiTokens.state.expired')
                    ) : (
                      <button type="button" className="btn btn-secondary btn-sm"
                              onClick={() => { setRevoking(row.id ?? ''); }}>{t('apiTokens.revoke')}</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <EmptyState title={t('apiTokens.admin.none')} />
      )}

      {revoking === null ? null : (
        <ConfirmDialog title={t('apiTokens.revoke.confirm')} confirmLabel={t('apiTokens.revoke')}
                       onCancel={() => { setRevoking(null); }}
                       onConfirm={() => {
                         const id = revoking;
                         setRevoking(null);
                         setMessages([]);
                         void revoke.run(id).then((outcome) => { if (outcome.ok) { setMessages([t('apiTokens.revoked')]); } });
                       }} />
      )}
    </div>
  );
}
