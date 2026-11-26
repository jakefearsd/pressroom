import { useState } from 'react';
import { Link, useLocation } from 'react-router';
import { useBusinesses, useDeleteBusiness, type BusinessView } from '../api/siteQueries';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { EmptyState } from '../components/EmptyState';
import { ProblemBanner } from '../components/ProblemBanner';
import { PageTitle } from '../frame/PageTitle';
import { useT } from '../i18n/messages';

/** The message a screen was sent here with (a saved form's confirmation), from the router state. */
export function useFlash(): string | undefined {
  const state: unknown = useLocation().state;
  const flash = typeof state === 'object' && state !== null ? (state as Record<string, unknown>)['flash'] : undefined;
  return typeof flash === 'string' ? flash : undefined;
}

/** Businesses.jsp: the site's business profiles, with edit and (when unused) delete. */
export function Businesses() {
  const t = useT();
  const flash = useFlash();
  const list = useBusinesses();
  const remove = useDeleteBusiness();
  const [asked, setAsked] = useState<BusinessView | null>(null);
  const [deleted, setDeleted] = useState<string | undefined>();

  const typeLabels: Record<string, string> = {
    Organization: t('businesses.type.Organization'),
    LocalBusiness: t('businesses.type.LocalBusiness'),
    ProfessionalService: t('businesses.type.ProfessionalService')
  };
  const newButton = (className: string) => (
    <Link to="/admin/businesses/new" className={className} id="business-new">{t('businesses.new')}</Link>
  );
  const businesses = list.data?.items ?? [];

  const confirm = () => {
    if (asked === null) {
      return;
    }
    const target = asked;
    setAsked(null);
    setDeleted(undefined);
    remove.mutate(target.id, { onSuccess: () => { setDeleted(t('businesses.deleted', target.name)); } });
  };

  return (
    <>
      <PageTitle>{t('businesses.title')}</PageTitle>
      <ProblemBanner messages={[...(flash === undefined ? [] : [t(flash)]), ...(deleted === undefined ? [] : [deleted])]}
                     error={list.error ?? remove.error} />
      <p className="subtitle">{t('businesses.subtitle')}</p>
      <div id="businesses-list-marker">
        {list.data === undefined ? null : businesses.length === 0 ? (
          <EmptyState title={t('businesses.empty')} body={t('businesses.empty.body')}>
            {newButton('btn btn-primary')}
          </EmptyState>
        ) : (
          <>
            <div className="list-actions">{newButton('btn btn-primary btn-sm')}</div>
            <div className="table-responsive">
              <table className="pressroomtable table table-striped" width="100%">
                <tbody>
                  <tr>
                    <th scope="col">{t('businesses.name')}</th>
                    <th scope="col">{t('businesses.type')}</th>
                    <th scope="col">{t('businesses.usedBy')}</th>
                    <th scope="col" className="row-actions"><span className="visually-hidden">{t('mainPage.actions')}</span></th>
                  </tr>
                  {businesses.map((b) => (
                    <tr key={b.id}>
                      <td>{b.name}</td>
                      <td>{typeLabels[b.businessType] ?? b.businessType}</td>
                      <td>{b.weblogCount}</td>
                      <td className="row-actions">
                        <Link to={`/admin/businesses/${encodeURIComponent(b.id)}`} className="row-action"
                              aria-label={`${t('generic.edit')}: ${b.name}`}>
                          <span className="bi bi-pencil-square" aria-hidden="true" />
                        </Link>
                        <button type="button" className="row-action row-action-danger"
                                aria-label={`${t('generic.delete')}: ${b.name}`}
                                onClick={() => { setAsked(b); }}>
                          <span className="bi bi-trash" aria-hidden="true" />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </div>
      {asked === null ? null : (
        <ConfirmDialog title={t('businesses.delete.confirm', asked.name)} confirmLabel={t('generic.delete')}
                       onConfirm={confirm} onCancel={() => { setAsked(null); }} />
      )}
    </>
  );
}
