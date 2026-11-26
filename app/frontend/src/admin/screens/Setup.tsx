import { useState, type FormEvent } from 'react';
import { useAccountCount, useAllWeblogs, useConfig, useSaveSettings } from '../api/siteQueries';
import { ProblemBanner } from '../components/ProblemBanner';
import { RichMessage } from '../components/RichMessage';
import { useFieldErrors } from '../forms/useFieldErrors';
import { PageTitle } from '../frame/PageTitle';
import { useBoot } from '../context';
import { useT } from '../i18n/messages';

const BLOG = 'site.frontpage.weblog.handle';
const AGGREGATED = 'site.frontpage.weblog.aggregated';

/** Setup.jsp: the install checklist (a user, a weblog) and the front page weblog choice. */
export function Setup() {
  const t = useT();
  const boot = useBoot();
  const accounts = useAccountCount();
  const weblogs = useAllWeblogs();
  const config = useConfig();
  const save = useSaveSettings();
  const fields = useFieldErrors(save.error, (field) => (field === BLOG ? 'frontpageBlog' : 'frontpageAggregated'));
  const [blogEdit, setBlog] = useState<string | null>(null);
  const [aggregatedEdit, setAggregated] = useState<boolean | null>(null);
  const [saved, setSaved] = useState(false);

  // What the person chose, else what is stored.
  const stored = (name: string) => config.data?.find((e) => e.name === name)?.value ?? '';
  const blog = blogEdit ?? stored(BLOG);
  const aggregated = aggregatedEdit ?? stored(AGGREGATED) === 'true';

  const userCount = accounts.data ?? 0;
  const blogs = weblogs.data?.items ?? [];
  const blogCount = blogs.length;
  const loaded = accounts.isSuccess && (weblogs.isSuccess || weblogs.isError);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (blog === '') {
      return;
    }
    setSaved(false);
    save.mutate({ [BLOG]: blog, [AGGREGATED]: String(aggregated) }, { onSuccess: () => { setSaved(true); } });
  };
  const link = (id: string, path: string, text: string) => (
    <a id={id} href={`${boot.contextPath}${path}`}>{text}</a>
  );

  return (
    <>
      <PageTitle>{t('index.heading')}</PageTitle>
      <ProblemBanner messages={saved ? [t('frontpageConfig.values.saved')] : []}
                     errors={weblogs.isError ? [t('frontpageConfig.weblogs.error')] : []}
                     error={accounts.error ?? config.error ?? save.error} />
      <p className="pagetip">{t('index.prompt')}</p>
      {!loaded ? null : (
        <>
          <div className="card">
            <div className="card-header">
              <h2 className="setup-section-title">
                {t('index.createUser')}
                {userCount > 0 ? ` - ${t('index.createUserDone', userCount)}` : null}
              </h2>
            </div>
            <div className="card-body">
              <p><RichMessage text={t('index.createUserHelp')} /></p>
              <p>
                {userCount === 0 ? (
                  <>{t('index.createUserBy')}{link('a_createUser', '/pressroom-ui/admin/createUser.rol', t('index.createUserPage'))}.</>
                ) : null}
              </p>
            </div>
          </div>

          <div className="card">
            <div className="card-header">
              <h2 className="setup-section-title">
                {t('index.createWeblog')}
                {blogCount > 0 ? ` - ${t('index.createWeblogDone', blogCount)}` : null}
              </h2>
            </div>
            <div className="card-body">
              <p><RichMessage text={t('index.createWeblogHelp')} /></p>
              {userCount > 0 && blogCount === 0 ? (
                <>{t('index.createWeblogBy')}{link('a_createBlog', '/pressroom-ui/createWeblog.rol', t('index.createWeblogPage'))}.</>
              ) : null}
            </div>
          </div>

          <div className="card">
            <div className="card-header">
              <h2 className="setup-section-title">{t('index.setFrontpage')}</h2>
            </div>
            <div className="card-body">
              <p><RichMessage text={t('index.setFrontpageHelp')} /></p>
              {blogCount === 0 ? null : (
                <form onSubmit={submit} className="form-stacked">
                  <div className="row mb-3">
                    <label className="col-sm-3 col-form-label" htmlFor="frontpageBlog">{t('frontpageConfig.frontpageBlogName')}</label>
                    <div className="col-sm-9">
                      <select name="frontpageBlog" {...fields.props(BLOG, 'form-select')} value={blog}
                              onChange={(event) => { setBlog(event.target.value); }}>
                        <option value="" disabled>{t('frontpageConfig.select.placeholder')}</option>
                        {blogs.map((w) => <option key={w.handle} value={w.handle}>{w.name}</option>)}
                      </select>
                    </div>
                  </div>
                  <div className="row mb-3">
                    <label className="col-sm-3 col-form-label" htmlFor="frontpageAggregated">{t('frontpageConfig.frontpageAggregated')}</label>
                    <div className="col-sm-9">
                      <input type="checkbox" id="frontpageAggregated" className="form-check-input" name="aggregated" value="true"
                             checked={aggregated} onChange={(event) => { setAggregated(event.target.checked); }} />
                    </div>
                  </div>
                  <button type="submit" className="btn btn-primary" disabled={save.isPending}>{t('generic.save')}</button>
                </form>
              )}
            </div>
          </div>
        </>
      )}
    </>
  );
}
