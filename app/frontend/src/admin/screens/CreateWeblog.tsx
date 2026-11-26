import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { call } from '../api/client';
import { ApiError } from '../api/problem';
import { useMe, useMeta, useWeblogOptions } from '../api/queries';
import { useProfile } from './people/queries';
import { ProblemBanner } from '../components/ProblemBanner';
import { useApi, useBoot } from '../context';
import { FieldFeedback, FormProblem } from '../forms/FormProblem';
import { useFieldErrors } from '../forms/useFieldErrors';
import { PageTitle } from '../frame/PageTitle';
import { useT } from '../i18n/messages';
import { flashState } from '../session/flash';

/** The create-weblog screen: CreateWeblog.jsp on POST /v1/weblogs; lands on the app home like the JSP did. */
export function CreateWeblog() {
  const t = useT();
  const api = useApi();
  const boot = useBoot();
  const navigate = useNavigate();
  const queries = useQueryClient();
  const me = useMe();
  const meta = useMeta();
  const options = useWeblogOptions();
  const profile = useProfile().data;
  const [name, setName] = useState('');
  const [handle, setHandle] = useState('');
  const [email, setEmail] = useState<string | null>(null);
  const [locale, setLocale] = useState<string | null>(null);
  const [timeZone, setTimeZone] = useState<string | null>(null);
  const [theme, setTheme] = useState<string | null>(null);
  const [local, setLocal] = useState<ApiError | null>(null);

  const create = useMutation({
    mutationFn: () => call(api.POST('/v1/weblogs', {
      body: {
        handle: handle.trim(), name: name.trim(), theme: chosenTheme,
        ...(chosenEmail.trim() === '' ? {} : { emailAddress: chosenEmail.trim() }),
        ...(chosenLocale === '' ? {} : { locale: chosenLocale }),
        ...(chosenZone === '' ? {} : { timeZone: chosenZone })
      }
    })),
    onSuccess: (view) => {
      void queries.invalidateQueries({ queryKey: ['me'] });
      void queries.invalidateQueries({ queryKey: ['weblogs'] });
      void navigate('/', { state: flashState(t('createWebsite.created', view.handle ?? handle)) });
    }
  });

  const error: unknown = local ?? create.error;
  const fields = useFieldErrors(error);
  const features = meta.data?.features;
  const choices = options.data;
  const themes = choices?.themes ?? [];
  // The form preselects the site's default theme, else the first offered (the JSP's picker, journal first).
  const chosenTheme = theme ?? choices?.defaultTheme ?? themes[0]?.id ?? '';
  // The form starts from the signed-in account's own email, locale and zone, as the JSP's bean did; what the
  // account lacks, or the form does not offer, is left to the API's defaults.
  const own = profile?.locale ?? '';
  const ownLocale = own === '' ? '' : choices?.locales.find((l) => l.id === own || l.id.startsWith(`${own}_`))?.id ?? '';
  const ownZone = choices?.timeZones.includes(profile?.timeZone ?? '') === true ? profile?.timeZone ?? '' : '';
  const chosenEmail = email ?? profile?.emailAddress ?? '';
  const chosenLocale = locale ?? ownLocale;
  const chosenZone = timeZone ?? ownZone;
  const themeChoice = themes.find((th) => th.id === chosenTheme);

  // Who may create is the server's rule too (WeblogCreationRules.mayCreate); saying so up front is the JSP's refusal page.
  const refusal = meta.data === undefined || me.data === undefined ? null
    : !features?.userWeblogCreation ? t('createWebsite.disabled')
      : !features.groupBlogging && me.data.weblogs.length > 0 ? t('createWebsite.oneBlogLimit') : null;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setLocal(null);
    const missing: { field: string; message: string }[] = [];
    if (name.trim() === '') {
      missing.push({ field: 'name', message: t('CreateWeblog.error.nameNull') });
    }
    if (handle.trim() === '') {
      missing.push({ field: 'handle', message: t('CreateWeblog.error.handleNull') });
    }
    if (missing.length > 0) {
      setLocal(new ApiError(400, 'field.required', '', missing));
      return;
    }
    create.mutate();
  };

  const siteUrl = meta.data?.siteUrl ?? '';
  return (
    <>
      <PageTitle>{t('createWebsite.title')}</PageTitle>
      {refusal !== null ? <ProblemBanner errors={[refusal]} /> : (
        <>
          <p className="subtitle">{t('createWebsite.prompt')}</p>
          <FormProblem error={error} controls={(f) => ['name', 'handle', 'emailAddress', 'locale', 'timeZone', 'theme'].includes(f)} />
          <form className="validate-form form-stacked" onSubmit={submit} noValidate>
            <div className="row mb-3">
              <label className="col-sm-3 col-form-label" htmlFor="name">{t('generic.name')}</label>
              <div className="col-sm-9">
                <input type="text" autoFocus size={30} maxLength={30} required {...fields.props('name', 'form-control')}
                       value={name} onChange={(e) => { setName(e.target.value); }} />
                <FieldFeedback message={fields.message('name')} />
              </div>
            </div>
            <div className="row mb-3">
              <label className="col-sm-3 col-form-label" htmlFor="handle">{t('createWebsite.handle')}</label>
              <div className="col-sm-9">
                <input type="text" size={30} maxLength={30} required {...fields.props('handle', 'form-control')}
                       value={handle} onChange={(e) => { setHandle(e.target.value); }} />
                <FieldFeedback message={fields.message('handle')} />
              </div>
            </div>
            <p className="form-text mb-3">
              {t('createWebsite.weblogUrl')}: {siteUrl}/<span id="handlePreview" className="handle-preview">{handle}</span>
            </p>
            <div className="row mb-3">
              <label className="col-sm-3 col-form-label" htmlFor="emailAddress">{t('createWebsite.emailAddress')}</label>
              <div className="col-sm-9">
                <input type="email" size={40} maxLength={255} {...fields.props('emailAddress', 'form-control validate-email')}
                       value={chosenEmail} onChange={(e) => { setEmail(e.target.value); }} />
                <FieldFeedback message={fields.message('emailAddress')} />
              </div>
            </div>
            <div className="row mb-3">
              <label className="col-sm-3 col-form-label" htmlFor="locale">{t('createWebsite.locale')}</label>
              <div className="col-sm-9">
                <select {...fields.props('locale', 'form-select')} value={chosenLocale}
                        onChange={(e) => { setLocale(e.target.value); }}>
                  {chosenLocale === '' ? <option value="" /> : null}
                  {(choices?.locales ?? []).map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
                </select>
                <FieldFeedback message={fields.message('locale')} />
              </div>
            </div>
            <div className="row mb-3">
              <label className="col-sm-3 col-form-label" htmlFor="timeZone">{t('createWebsite.timezone')}</label>
              <div className="col-sm-9">
                <select {...fields.props('timeZone', 'form-select')} value={chosenZone}
                        onChange={(e) => { setTimeZone(e.target.value); }}>
                  {chosenZone === '' ? <option value="" /> : null}
                  {(choices?.timeZones ?? []).map((z) => <option key={z} value={z}>{z}</option>)}
                </select>
                <FieldFeedback message={fields.message('timeZone')} />
              </div>
            </div>
            <div className="row mb-3">
              <label className="col-sm-3 col-form-label" htmlFor="theme">{t('createWebsite.theme')}</label>
              <div className="col-sm-9">
                <select {...fields.props('theme', 'form-select')} value={chosenTheme}
                        onChange={(e) => { setTheme(e.target.value); }}>
                  {themes.map((th) => <option key={th.id} value={th.id}>{th.name}</option>)}
                </select>
                <FieldFeedback message={fields.message('theme')} />
                <p id="themedescription">{themeChoice?.description ?? ''}</p>
                <p>
                  {themeChoice?.previewPath === undefined ? null : (
                    <img id="themeThumbnail" src={`${boot.contextPath}${themeChoice.previewPath}`} alt=""
                         className="img-fluid img-thumbnail theme-thumb" />
                  )}
                </p>
              </div>
            </div>
            <button type="submit" className="btn btn-primary" disabled={create.isPending}>{t('createWebsite.button.save')}</button>
            {' '}
            <Link className="btn btn-secondary" to="/">{t('generic.cancel')}</Link>
          </form>
        </>
      )}
    </>
  );
}
