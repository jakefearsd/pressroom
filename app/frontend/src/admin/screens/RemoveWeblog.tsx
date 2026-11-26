import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router';
import { call } from '../api/client';
import { useWeblog } from '../api/queries';
import { ProblemBanner } from '../components/ProblemBanner';
import { Rich, escapeArg } from '../components/Rich';
import { useApi } from '../context';
import { PageTitle } from '../frame/PageTitle';
import { useT } from '../i18n/messages';
import { useCanAdminister } from '../routes/access';
import { flashState } from '../session/flash';

/** WeblogRemoveConfirm.jsp on DELETE /v1/weblogs/{handle}: the page is the confirmation; success lands on the app home. */
export function RemoveWeblog() {
  const t = useT();
  const api = useApi();
  const navigate = useNavigate();
  const queries = useQueryClient();
  const handle = useParams()['handle'];
  const allowed = useCanAdminister(handle);
  const weblog = useWeblog(handle);
  const name = weblog.data?.name ?? handle ?? '';

  const remove = useMutation({
    mutationFn: () => call(api.DELETE('/v1/weblogs/{handle}', { params: { path: { handle: handle ?? '' } } })),
    onSuccess: () => {
      queries.removeQueries({ queryKey: ['weblog', handle] });
      void queries.invalidateQueries({ queryKey: ['me'] });
      void queries.invalidateQueries({ queryKey: ['weblogs'] });
      void navigate('/', { state: flashState(t('websiteRemove.success', name)) });
    }
  });

  return (
    <>
      <PageTitle>{t('websiteRemove.title')}</PageTitle>
      {allowed === false ? <ProblemBanner errors={[t('api.problem.auth.forbidden')]} /> : null}
      <ProblemBanner error={remove.error} errors={remove.error === null ? [] : [t('websiteRemove.error', name)]} />
      {allowed === true && weblog.error !== null ? <ProblemBanner error={weblog.error} /> : null}
      {allowed === true && weblog.data !== undefined ? (
        <>
          <p className="subtitle">{t('websiteRemove.subtitle')}</p>
          <p style={{ marginBottom: '3em' }}>
            <Rich text={t('websiteRemove.youSure', escapeArg(name))} />
            <br /><br />
            <span className="warning">{t('websiteSettings.removeWebsiteWarning')}</span>
          </p>
          <div className="row">
            <div className="col-md-2">
              <button type="button" className="btn btn-danger" disabled={remove.isPending}
                      onClick={() => { remove.mutate(); }}>{t('websiteSettings.button.remove')}</button>
            </div>
            <div className="col-md-2">
              <Link className="btn btn-secondary" to={`/weblogs/${encodeURIComponent(handle ?? '')}/settings`}>
                {t('generic.cancel')}
              </Link>
            </div>
            <div className="col-md-8" />
          </div>
        </>
      ) : null}
    </>
  );
}
