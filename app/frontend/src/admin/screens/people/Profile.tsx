import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { call } from '../../api/client';
import { ApiError } from '../../api/problem';
import { PasswordField } from '../../components/PasswordFields';
import { ProblemBanner } from '../../components/ProblemBanner';
import { useApi } from '../../context';
import { useFieldErrors } from '../../forms/useFieldErrors';
import { PageTitle } from '../../frame/PageTitle';
import { useT, type Translate } from '../../i18n/messages';
import { useProfile, useWeblogOptions } from './queries';
import { useAction } from './useAction';

/** Request field -> control id (the JSP's). */
const CONTROL: Record<string, string> = { newPassword: 'passwordText' };
const controlId = (field: string) => CONTROL[field] ?? field;

/** A wrong current password sits on its field in the JSP's words; the SSO refusal is the JSP's too. */
function profileProblem(error: unknown, t: Translate): unknown {
  if (!(error instanceof ApiError)) {
    return error;
  }
  if (error.code === 'profile.currentPasswordWrong') {
    return new ApiError(error.status, error.code, '', [{ field: 'currentPassword', message: t('yourProfile.error.currentPassword') }]);
  }
  return error.code === 'user.ssoBound'
    ? new ApiError(error.status, error.code, t('userSettings.error.ssoPassword'), error.fieldErrors) : error;
}

/** Your profile (Profile.jsp): name, email, password, locale and time zone of the signed-in account. */
export function Profile() {
  const t = useT();
  const profile = useProfile();
  const options = useWeblogOptions();
  return (
    <>
      <PageTitle>{t('yourProfile.title')}</PageTitle>
      {profile.data === undefined || options.data === undefined
        ? <ProblemBanner error={profile.error ?? options.error} />
        : <ProfileForm profile={profile.data} options={options.data} />}
    </>
  );
}

function ProfileForm({ profile, options }: {
  profile: NonNullable<ReturnType<typeof useProfile>['data']>;
  options: NonNullable<ReturnType<typeof useWeblogOptions>['data']>;
}) {
  const t = useT();
  const api = useApi();
  const queries = useQueryClient();
  const [form, setForm] = useState({
    screenName: profile.screenName ?? '', fullName: profile.fullName ?? '', emailAddress: profile.emailAddress ?? '',
    locale: profile.locale ?? '', timeZone: profile.timeZone ?? '', currentPassword: '', passwordText: '', passwordConfirm: ''
  });
  const [notices, setNotices] = useState<string[]>([]);
  const [mismatch, setMismatch] = useState<ApiError | undefined>(undefined);
  const save = useAction(async () => {
    await call(api.PATCH('/v1/me/profile', {
      body: { screenName: form.screenName, fullName: form.fullName, emailAddress: form.emailAddress,
        locale: form.locale, timeZone: form.timeZone }
    }));
    await queries.invalidateQueries({ queryKey: ['profile'] });
    await queries.invalidateQueries({ queryKey: ['me'] });
    if (form.passwordText !== '') {
      await call(api.PUT('/v1/me/password', { body: { currentPassword: form.currentPassword, newPassword: form.passwordText } }));
    }
  });
  const error = mismatch ?? profileProblem(save.error, t);
  const fields = useFieldErrors(error, controlId);
  const set = (key: keyof typeof form, value: string) => { setForm((f) => ({ ...f, [key]: value })); };

  const submit = async () => {
    setNotices([]);
    if (form.passwordText !== '' && form.passwordText !== form.passwordConfirm) {
      setMismatch(new ApiError(400, 'client.passwordsNotSame', '', [{ field: 'passwordConfirm', message: t('yourProfile.passwordsNotSame') }]));
      return;
    }
    setMismatch(undefined);
    if ((await save.run()).ok) {
      setForm((f) => ({ ...f, currentPassword: '', passwordText: '', passwordConfirm: '' }));
      setNotices([t('generic.changes.saved')]);
    }
  };

  const row = (field: 'screenName' | 'fullName' | 'emailAddress', label: string, extra: { autoFocus?: boolean; max: number }) => (
    <div className="row mb-3">
      <label className="col-sm-3 col-form-label" htmlFor={field}>{label}</label>
      <div className="col-sm-9">
        <input type="text" {...fields.props(field, 'form-control')} name={field} maxLength={extra.max} value={form[field]}
               {...(extra.autoFocus === true ? { autoFocus: true } : {})}
               onChange={(event) => { set(field, event.target.value); }} />
      </div>
    </div>
  );

  return (
    <>
      <ProblemBanner messages={notices} error={error} />
      <p className="subtitle">{t('yourProfile.subtitle')}</p>
      <form className="form-stacked" onSubmit={(event) => { event.preventDefault(); void submit(); }}>
        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="userName">{t('userSettings.username')}</label>
          <div className="col-sm-9">
            <input type="text" className="form-control" id="userName" name="userName" maxLength={30} readOnly
                   value={profile.userName} />
          </div>
        </div>
        {row('screenName', t('userSettings.screenname'), { autoFocus: true, max: 30 })}
        {row('fullName', t('userSettings.fullname'), { max: 255 })}
        {row('emailAddress', t('userSettings.email'), { max: 255 })}

        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="currentPassword">{t('yourProfile.currentPassword')}</label>
          <div className="col-sm-9">
            <PasswordField id="currentPassword" value={form.currentPassword} autoComplete="current-password"
                           invalid={fields.invalid('currentPassword')} onChange={(v) => { set('currentPassword', v); }} />
            <div className="form-text" id="currentPasswordTip">{t('yourProfile.currentPassword.tip')}</div>
          </div>
        </div>
        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="passwordText">{t('userSettings.password')}</label>
          <div className="col-sm-9">
            <PasswordField id="passwordText" value={form.passwordText} autoComplete="new-password"
                           invalid={fields.invalid('newPassword')} onChange={(v) => { set('passwordText', v); }} />
          </div>
        </div>
        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="passwordConfirm">{t('userSettings.passwordConfirm')}</label>
          <div className="col-sm-9">
            <PasswordField id="passwordConfirm" value={form.passwordConfirm} autoComplete="new-password"
                           matchWith={form.passwordText} invalid={fields.invalid('passwordConfirm')}
                           onChange={(v) => { set('passwordConfirm', v); }} />
          </div>
        </div>

        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="locale">{t('userSettings.locale')}</label>
          <div className="col-sm-9">
            <select {...fields.props('locale', 'form-select')} name="locale" value={form.locale}
                    onChange={(event) => { set('locale', event.target.value); }}>
              {options.locales.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
            </select>
          </div>
        </div>
        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="timeZone">{t('userSettings.timeZone')}</label>
          <div className="col-sm-9">
            <select {...fields.props('timeZone', 'form-select')} name="timeZone" value={form.timeZone}
                    onChange={(event) => { set('timeZone', event.target.value); }}>
              {options.timeZones.map((z) => <option key={z} value={z}>{z}</option>)}
            </select>
          </div>
        </div>

        <button type="submit" id="saveButton" className="btn btn-primary" disabled={save.pending}>{t('generic.save')}</button>
        {' '}<Link className="btn btn-secondary" to="/">{t('generic.cancel')}</Link>
      </form>

      <p className="mt-4">
        <Link to="/profile/tokens" id="profileTokensLink">{t('apiTokens.profile.link')}</Link>
      </p>
    </>
  );
}
