import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router';
import { call } from '../../api/client';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { PasswordField } from '../../components/PasswordFields';
import { ProblemBanner } from '../../components/ProblemBanner';
import { useFieldErrors } from '../../forms/useFieldErrors';
import { PageTitle } from '../../frame/PageTitle';
import { useApi, useBoot } from '../../context';
import { useT } from '../../i18n/messages';
import { inJspWords } from './problemText';
import { useUser, useWeblogOptions, type UserView } from './queries';
import { useAction } from './useAction';
import { UserWeblogs } from './UserWeblogs';

/** Request field -> control id (the JSP's bean_* ids). */
const CONTROL: Record<string, string> = {
  userName: 'bean_userName', screenName: 'bean_screenName', fullName: 'bean_fullName', password: 'bean_password',
  emailAddress: 'bean_email', locale: 'bean_locale', timeZone: 'bean_timeZone', enabled: 'bean_enabled',
  administrator: 'bean_administrator', writer: 'bean_writer'
};
const controlId = (field: string) => CONTROL[field] ?? field;

interface Form {
  userName: string;
  screenName: string;
  fullName: string;
  password: string;
  emailAddress: string;
  locale: string;
  timeZone: string;
  enabled: boolean;
  administrator: boolean;
  writer: boolean;
  passwordMode: 'link' | 'now';
}

/** Create (`/admin/users/new`) or edit (`/admin/users/:userName`) a person: UserEdit.jsp. */
export function UserEdit() {
  const t = useT();
  const userName = useParams()['userName'];
  const user = useUser(userName);
  const options = useWeblogOptions();

  if (userName === undefined) {
    return options.data === undefined ? <PageTitle>{t('userAdmin.title.createNewUser')}</PageTitle>
      : <UserForm options={options.data} />;
  }
  if (user.data === null) {
    return (
      <>
        <PageTitle>{t('userAdmin.title.editUser')}</PageTitle>
        <ProblemBanner errors={[t('userAdmin.error.userNotFound')]} />
      </>
    );
  }
  if (user.data?.kind === 'AGENT') {
    return <Navigate to={`/admin/agents/${encodeURIComponent(userName)}`} replace />;
  }
  if (user.data === undefined || options.data === undefined) {
    return (
      <>
        <PageTitle>{t('userAdmin.title.editUser')}</PageTitle>
        <ProblemBanner error={user.error ?? options.error} />
      </>
    );
  }
  return <UserForm key={userName} account={user.data} options={options.data} />;
}

type Options = NonNullable<ReturnType<typeof useWeblogOptions>['data']>;

function UserForm({ account, options }: { account?: UserView; options: Options }) {
  const t = useT();
  const api = useApi();
  const boot = useBoot();
  const navigate = useNavigate();
  const queries = useQueryClient();
  const creating = account === undefined;
  const sso = account?.sso === true;
  const localeIds = options.locales.map((l) => l.id);
  const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const [form, setForm] = useState<Form>({
    userName: account?.userName ?? '',
    screenName: account?.screenName ?? '',
    fullName: account?.fullName ?? '',
    password: '',
    emailAddress: account?.emailAddress ?? '',
    locale: account?.locale ?? (localeIds.includes(boot.uiLocale) ? boot.uiLocale : localeIds[0] ?? ''),
    timeZone: account?.timeZone ?? (options.timeZones.includes(browserZone) ? browserZone : options.timeZones[0] ?? 'UTC'),
    enabled: account?.enabled ?? true,
    administrator: account?.globalRoles?.includes('admin') === true,
    writer: account?.writer === true,
    passwordMode: 'link'
  });
  const [notices, setNotices] = useState<string[]>([]);
  const [confirmLink, setConfirmLink] = useState(false);
  const set = <K extends keyof Form>(key: K, value: Form[K]) => { setForm((f) => ({ ...f, [key]: value })); };

  const save = useAction(async () => {
    if (creating) {
      await call(api.POST('/v1/admin/users', {
        body: {
          userName: form.userName, screenName: form.screenName, fullName: form.fullName,
          emailAddress: form.emailAddress, locale: form.locale, timeZone: form.timeZone,
          administrator: form.administrator,
          ...(form.passwordMode === 'now' ? { password: form.password } : {})
        }
      }));
    } else {
      await call(api.PATCH('/v1/admin/users/{userName}', {
        params: { path: { userName: form.userName } },
        body: {
          screenName: form.screenName, fullName: form.fullName, emailAddress: form.emailAddress,
          locale: form.locale, timeZone: form.timeZone, enabled: form.enabled,
          ...(sso ? { writer: form.writer } : { administrator: form.administrator }),
          ...(!sso && form.password !== '' ? { password: form.password } : {})
        }
      }));
    }
    await queries.invalidateQueries({ queryKey: ['users'] });
    await queries.invalidateQueries({ queryKey: ['user', form.userName] });
  });
  const sendLink = useAction(async () => {
    await call(api.POST('/v1/admin/users/{userName}/password-link', { params: { path: { userName: form.userName } } }));
    return t('userAdmin.passwordLinkSent', account?.emailAddress ?? '');
  });

  const failure = inJspWords(save.error ?? sendLink.error, t);
  const fields = useFieldErrors(save.error, controlId);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setNotices([]);
    sendLink.reset();
    if ((await save.run()).ok) {
      const flash = creating
        ? (form.passwordMode === 'link' ? t('userAdmin.userCreatedLinkSent') : t('createUser.add.success', form.userName))
        : t('userAdmin.userSaved');
      void navigate('/admin/users', { state: { flash } });
    }
  };
  const confirmedLink = async () => {
    setConfirmLink(false);
    save.reset();
    const outcome = await sendLink.run();
    setNotices(outcome.ok ? [outcome.value] : []);
  };

  const text = (field: keyof Form & string, label: string, extra: { readOnly?: boolean; autoFocus?: boolean; max?: number; help?: string; type?: string } = {}) => (
    <div className="row mb-3">
      <label className="col-sm-3 col-form-label" htmlFor={controlId(field)}>{label}</label>
      <div className="col-sm-9">
        <input type="text" {...fields.props(field, 'form-control')} name={`bean.${field}`}
               value={String(form[field])} maxLength={extra.max ?? 255}
               {...(extra.readOnly === true ? { readOnly: true } : {})}
               {...(extra.autoFocus === true ? { autoFocus: true } : {})}
               {...(extra.help === undefined ? {} : { 'aria-describedby': `${controlId(field)}_help` })}
               onChange={(event) => { set(field, event.target.value as never); }} />
        {extra.help === undefined ? null : <div className="form-text" id={`${controlId(field)}_help`}>{extra.help}</div>}
      </div>
    </div>
  );

  return (
    <>
      <PageTitle>{t(creating ? 'userAdmin.title.createNewUser' : 'userAdmin.title.editUser')}</PageTitle>
      <ProblemBanner messages={notices} error={failure} />
      <p className="subtitle">
        {creating ? t('userAdmin.subtitle.createNewUser', form.userName) : t('userAdmin.subtitle.editUser', form.userName)}
      </p>
      {creating ? <p className="pagetip">{t('userAdmin.addInstructions')}</p> : null}
      {sso ? <p className="form-text" id="ssoBoundMarker">{t('userAdmin.ssoBound')}</p> : null}

      <form className="form-stacked" onSubmit={(event) => { void submit(event); }}>
        {creating
          ? text('userName', t('userSettings.username'), { autoFocus: true, max: 30, help: t('userAdmin.tip.userName') })
          : text('userName', t('userSettings.username'), { readOnly: true, max: 30, help: t('userSettings.tip.username') })}
        {text('screenName', t('userSettings.screenname'), { max: 30, ...(creating ? {} : { autoFocus: true }) })}
        {text('fullName', t('userSettings.fullname'), { max: 255 })}

        {sso ? null : (
          <>
            {creating ? (
              <fieldset className="row mb-3">
                <legend className="col-sm-3 col-form-label">{t('personForm.password')}</legend>
                <div className="col-sm-9">
                  <div className="form-check">
                    <input type="radio" className="form-check-input" id="bean_passwordModeLink" name="bean.passwordMode"
                           value="link" checked={form.passwordMode === 'link'} onChange={() => { set('passwordMode', 'link'); }} />
                    <label className="form-check-label" htmlFor="bean_passwordModeLink">{t('personForm.password.link')}</label>
                  </div>
                  <div className="form-check">
                    <input type="radio" className="form-check-input" id="bean_passwordModeNow" name="bean.passwordMode"
                           value="now" checked={form.passwordMode === 'now'} onChange={() => { set('passwordMode', 'now'); }} />
                    <label className="form-check-label" htmlFor="bean_passwordModeNow">{t('personForm.password.now')}</label>
                  </div>
                </div>
              </fieldset>
            ) : null}
            <div className="row mb-3">
              <label className="col-sm-3 col-form-label" htmlFor="bean_password">{t('userSettings.password')}</label>
              <div className="col-sm-9">
                <PasswordField id="bean_password" name="bean.password" value={form.password} autoComplete="new-password"
                               invalid={fields.invalid('password')} onChange={(v) => { set('password', v); }} />
                <div className="form-text">{creating ? t('personForm.password.tip') : t('userAdmin.tip.password')}</div>
              </div>
            </div>
          </>
        )}

        {text('emailAddress', t('userSettings.email'), { help: t('userAdmin.tip.email') })}

        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="bean_locale">{t('userSettings.locale')}</label>
          <div className="col-sm-9">
            <select {...fields.props('locale', 'form-select')} name="bean.locale" value={form.locale}
                    onChange={(event) => { set('locale', event.target.value); }}>
              {options.locales.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
            </select>
          </div>
        </div>

        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="bean_timeZone">{t('userSettings.timeZone')}</label>
          <div className="col-sm-9">
            <select {...fields.props('timeZone', 'form-select')} name="bean.timeZone" value={form.timeZone}
                    onChange={(event) => { set('timeZone', event.target.value); }}>
              {options.timeZones.map((z) => <option key={z} value={z}>{z}</option>)}
            </select>
          </div>
        </div>

        {creating ? null : (
          <div className="row mb-3">
            <label className="col-sm-3 col-form-label" htmlFor="bean_enabled">{t('userAdmin.enabled')}</label>
            <div className="col-sm-9">
              <input type="checkbox" {...fields.props('enabled', 'form-check-input')} name="bean.enabled" checked={form.enabled}
                     onChange={(event) => { set('enabled', event.target.checked); }} />
              <div className="form-text">{t('userAdmin.enabled.signsOutNote')}</div>
            </div>
          </div>
        )}

        {sso ? (
          <div className="row mb-3">
            <label className="col-sm-3 col-form-label" htmlFor="bean_writer">{t('userAdmin.canWrite')}</label>
            <div className="col-sm-9">
              <input type="checkbox" {...fields.props('writer', 'form-check-input')} name="bean.writer" checked={form.writer}
                     onChange={(event) => { set('writer', event.target.checked); }} />
            </div>
          </div>
        ) : creating ? (
          <fieldset className="row mb-3">
            <legend className="col-sm-3 col-form-label">{t('personForm.role')}</legend>
            <div className="col-sm-9">
              <div className="form-check">
                <input type="radio" className="form-check-input" id="bean_roleAuthor" name="bean.administrator" value="false"
                       checked={!form.administrator} onChange={() => { set('administrator', false); }} />
                <label className="form-check-label" htmlFor="bean_roleAuthor">{t('personForm.role.author')}</label>
              </div>
              <div className="form-check">
                <input type="radio" className="form-check-input" id="bean_roleAdmin" name="bean.administrator" value="true"
                       checked={form.administrator} onChange={() => { set('administrator', true); }} />
                <label className="form-check-label" htmlFor="bean_roleAdmin">{t('personForm.role.siteAdmin')}</label>
              </div>
            </div>
          </fieldset>
        ) : (
          <div className="row mb-3">
            <label className="col-sm-3 col-form-label" htmlFor="bean_administrator">{t('userAdmin.userAdmin')}</label>
            <div className="col-sm-9">
              <input type="checkbox" {...fields.props('administrator', 'form-check-input')} name="bean.administrator"
                     checked={form.administrator} onChange={(event) => { set('administrator', event.target.checked); }} />
              <div className="form-text">{t('userAdmin.tip.userAdmin')}</div>
            </div>
          </div>
        )}

        <div className="control mt-4">
          <button type="submit" className="btn btn-primary" id="save_button" disabled={save.pending}>{t('generic.save')}</button>
          <Link to="/admin/users" className="btn btn-secondary">{t('generic.cancel')}</Link>
        </div>
      </form>

      {creating || sso ? null : (
        <button type="button" className="btn btn-link p-0 mt-3" onClick={() => { setConfirmLink(true); }}>
          {t('userAdmin.sendPasswordLink')}
        </button>
      )}
      {confirmLink ? (
        <ConfirmDialog title={t('userAdmin.sendPasswordLink.confirm', form.userName, account?.emailAddress ?? '')}
                       confirmLabel={t('userAdmin.sendPasswordLink')} tone="primary"
                       onCancel={() => { setConfirmLink(false); }} onConfirm={() => { void confirmedLink(); }} />
      ) : null}

      {creating ? null : <UserWeblogs userName={form.userName} />}
    </>
  );
}
