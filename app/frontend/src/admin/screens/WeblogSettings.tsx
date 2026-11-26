import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import { call } from '../api/client';
import { ApiError } from '../api/problem';
import { useMeta, useWeblog, useWeblogBusinesses, useWeblogOptions, useWeblogPages } from '../api/queries';
import type { components } from '../api/schema';
import { ProblemBanner } from '../components/ProblemBanner';
import { Rich, escapeArg } from '../components/Rich';
import { useApi, useBoot } from '../context';
import { FieldFeedback, FormProblem } from '../forms/FormProblem';
import { useFieldErrors } from '../forms/useFieldErrors';
import { PageTitle } from '../frame/PageTitle';
import { useT } from '../i18n/messages';
import { useCanAdminister } from '../routes/access';

/*
 * Message keys built at run time, listed whole for MessageKeyTest:
 * websiteSettings.subscribeForm.on, websiteSettings.subscribeForm.offNoList,
 * websiteSettings.subscribeForm.offNotConfigured, websiteSettings.placeLat.invalid,
 * websiteSettings.placeLng.invalid.
 */
type WeblogView = components['schemas']['WeblogView'];
type WeblogPatch = components['schemas']['WeblogPatch'];

/** The form's values: everything as the controls hold it (text), so "blank" is one idea. */
interface Values {
  name: string; tagline: string; copyright: string; icon: string; about: string; emailAddress: string;
  entryDisplayCount: string; active: boolean; commentsEnabled: boolean; commentNotify: boolean;
  showMoreFromSite: boolean; shareRowEnabled: boolean; entryOrder: string; pageWidth: string;
  frontPage: string; locale: string; timeZone: string; newsletterListUuid: string; businessId: string;
  placeType: string; placeLocality: string; placeRegion: string; placeCountry: string;
  placeLat: string; placeLng: string; bookingUrl: string; customDomain: string;
}

const text = (v: string | undefined): string => v ?? '';
const num = (v: number | undefined): string => (v === undefined ? '' : String(v));

export function valuesOf(w: WeblogView): Values {
  return {
    name: text(w.name), tagline: text(w.tagline), copyright: text(w.copyright), icon: text(w.icon),
    about: text(w.about), emailAddress: text(w.emailAddress), entryDisplayCount: num(w.entryDisplayCount),
    active: w.active ?? false, commentsEnabled: w.commentsEnabled ?? false, commentNotify: w.commentNotify ?? false,
    showMoreFromSite: w.showMoreFromSite ?? true, shareRowEnabled: w.shareRowEnabled ?? true,
    entryOrder: w.entryOrder === 'OLDEST' ? 'OLDEST' : 'NEWEST', pageWidth: w.pageWidth === 'STANDARD' ? 'STANDARD' : 'WIDE',
    frontPage: text(w.frontPage), locale: text(w.locale), timeZone: text(w.timeZone),
    newsletterListUuid: text(w.newsletterListUuid), businessId: text(w.businessId), placeType: text(w.placeType),
    placeLocality: text(w.placeLocality), placeRegion: text(w.placeRegion), placeCountry: text(w.placeCountry),
    placeLat: num(w.placeLat), placeLng: num(w.placeLng), bookingUrl: text(w.bookingUrl),
    customDomain: text(w.customDomain)
  };
}

/** Fields the API can remove: a changed value that is now blank goes in `clear`, never as "". */
const CLEARABLE = ['tagline', 'copyright', 'icon', 'about', 'newsletterListUuid', 'customDomain', 'placeLocality',
  'placeRegion', 'placeCountry', 'bookingUrl', 'businessId', 'frontPage', 'placeType'] as const;
const PLAIN_TEXT = ['name', 'emailAddress', 'locale', 'timeZone', 'entryOrder', 'pageWidth'] as const;
const FLAGS = ['active', 'commentsEnabled', 'commentNotify', 'showMoreFromSite', 'shareRowEnabled'] as const;

/**
 * The PATCH for what changed, or null when nothing did. A blank that replaces a value is a
 * `clear` (the API's removal), so the form can empty a field as the JSP's POST did. Latitude and
 * longitude clear together; one without the other is sent as it stands and the API refuses it.
 */
export function buildPatch(before: Values, now: Values): WeblogPatch | null {
  const patch: Record<string, unknown> = {};
  const clear: string[] = [];
  for (const key of PLAIN_TEXT) {
    if (now[key] !== before[key]) {
      patch[key] = now[key];
    }
  }
  for (const key of FLAGS) {
    if (now[key] !== before[key]) {
      patch[key] = now[key];
    }
  }
  for (const key of CLEARABLE) {
    if (now[key] !== before[key]) {
      if (now[key].trim() === '') {
        clear.push(key);
      } else {
        patch[key] = now[key];
      }
    }
  }
  if (now.entryDisplayCount !== before.entryDisplayCount) {
    patch['entryDisplayCount'] = Number(now.entryDisplayCount);
  }
  if (now.placeLat !== before.placeLat || now.placeLng !== before.placeLng) {
    if (now.placeLat.trim() === '' && now.placeLng.trim() === '') {
      clear.push('placeLat', 'placeLng');
    } else {
      patch['placeLat'] = Number(now.placeLat);
      patch['placeLng'] = Number(now.placeLng);
    }
  }
  if (clear.length > 0) {
    patch['clear'] = clear;
  }
  return Object.keys(patch).length === 0 ? null : (patch as WeblogPatch);
}

const idFor = (field: string): string => `weblog_bean_${field === 'frontPage' ? 'frontPageId' : field}`;
const SECTIONS = [['settings-general', 'websiteSettings.generalSettings'],
  ['settings-language', 'websiteSettings.languageSettings'],
  ['settings-newsletter', 'websiteSettings.newsletterSettings'],
  ['settings-business', 'websiteSettings.businessSettings'],
  ['settings-customdomain', 'websiteSettings.customDomainSettings']] as const;

function Row({ id, label, children, help }: { id: string; label: string; children: ReactNode; help?: ReactNode }) {
  return (
    <div className="row mb-3">
      <label className="col-sm-3 col-form-label" htmlFor={id}>{label}</label>
      <div className="col-sm-9">{children}{help}</div>
    </div>
  );
}

function Check({ checked, onChange, label, help, name }: {
  checked: boolean; onChange: (v: boolean) => void; label: string; help?: string; name: string;
}) {
  return (
    <div className="row mb-3">
      <div className="offset-sm-3 col-sm-9">
        <div className="form-check">
          <label className="form-check-label">
            <input type="checkbox" className="form-check-input" name={name} checked={checked}
                   onChange={(e) => { onChange(e.target.checked); }} /> {label}
          </label>
        </div>
        {help === undefined ? null : <div className="form-text">{help}</div>}
      </div>
    </div>
  );
}

/** Highlights the section whose heading is in the top 30% of the viewport (an IntersectionObserver, if there is one). */
function useCurrentSection(): string {
  const [current, setCurrent] = useState<string>(SECTIONS[0][0]);
  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined') {
      return undefined;
    }
    const inBand = new Map<string, boolean>();
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        inBand.set(entry.target.id, entry.isIntersecting);
      }
      const first = SECTIONS.find(([id]) => inBand.get(id) === true);
      if (first !== undefined) {
        setCurrent(first[0]);
      }
    }, { rootMargin: '0px 0px -70% 0px' });
    for (const [id] of SECTIONS) {
      const head = document.getElementById(id);
      if (head !== null) {
        observer.observe(head);
      }
    }
    return () => { observer.disconnect(); };
  }, []);
  return current;
}

/** The website settings screen: WeblogConfig.jsp on /api/v1 (PATCH sends only what changed). */
export function WeblogSettings() {
  const t = useT();
  const handle = useParams()['handle'];
  const allowed = useCanAdminister(handle);
  const weblog = useWeblog(handle);
  return (
    <>
      <PageTitle>{t('websiteSettings.title')}</PageTitle>
      {allowed === false ? <ProblemBanner errors={[t('api.problem.auth.forbidden')]} /> : null}
      {allowed === true && weblog.error !== null ? <ProblemBanner error={weblog.error} /> : null}
      {allowed === true && weblog.data !== undefined && handle !== undefined
        ? <SettingsForm key={handle} handle={handle} weblog={weblog.data} /> : null}
    </>
  );
}

function SettingsForm({ handle, weblog }: { handle: string; weblog: WeblogView }) {
  const t = useT();
  const api = useApi();
  const boot = useBoot();
  const { contextPath } = boot;
  const queries = useQueryClient();
  const meta = useMeta();
  const options = useWeblogOptions();
  const pages = useWeblogPages(handle === boot.site.siteSpaceHandle ? undefined : handle);
  const businesses = useWeblogBusinesses(handle);
  const [saved, setSaved] = useState<WeblogView>(weblog);
  const [values, setValues] = useState<Values>(() => valuesOf(weblog));
  const [message, setMessage] = useState<string[]>([]);
  const [local, setLocal] = useState<ApiError | null>(null);
  const [copied, setCopied] = useState(false);
  const section = useCurrentSection();
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => { clearTimeout(timer.current); }, []);

  const save = useMutation({
    mutationFn: (patch: WeblogPatch) => call(api.PATCH('/v1/weblogs/{handle}', {
      params: { path: { handle } }, body: patch
    })),
    onSuccess: (view) => {
      setSaved(view);
      setValues(valuesOf(view));
      setMessage([t('websiteSettings.savedChanges')]);
      queries.setQueryData(['weblog', handle], view);
      void queries.invalidateQueries({ queryKey: ['me'] });
    },
    onError: () => { setMessage([]); }
  });

  const error: unknown = local ?? save.error;
  const fields = useFieldErrors(error, idFor);
  const set = <K extends keyof Values>(key: K) => (value: Values[K]) => { setValues((v) => ({ ...v, [key]: value })); };
  const bind = (key: keyof Values & string, baseClass = 'form-control') => ({
    ...fields.props(key, baseClass),
    name: `bean.${key}`,
    value: values[key] as string,
    onChange: (e: { target: { value: string } }) => { set(key)(e.target.value as never); }
  });
  const field = (key: string) => <FieldFeedback message={fields.message(key)} />;

  const businessList = businesses.data?.items;
  const businessesUnavailable = businesses.isError;
  const frontPages = (pages.data ?? []).filter((p) => p.status === 'PUBLISHED');
  const frontPagesUnavailable = pages.isError;
  // The address readers reach the weblog at is the server's to say (custom domain, virtual host, site url).
  const absolute = saved.absoluteUrl;
  // The site space is the hidden weblog for the site's own pages: it has no front door to choose.
  const siteSpace = handle === boot.site.siteSpaceHandle;
  const maxEntries = meta.data?.maxEntriesPerPage;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setLocal(null);
    const lat = values.placeLat.trim();
    const lng = values.placeLng.trim();
    // Not a number is a refusal of its own, caught here: Number('x') would reach the API as null.
    for (const [key, raw] of [['placeLat', lat], ['placeLng', lng]] as const) {
      if (raw !== '' && Number.isNaN(Number(raw))) {
        setLocal(new ApiError(400, `${key}.invalid`, '', [{ field: key, message: t(`websiteSettings.${key}.invalid`) }]));
        return;
      }
    }
    const patch = buildPatch(valuesOf(saved), values);
    if (patch === null) {
      // Nothing to send, and nothing refused now: an earlier refusal no longer describes the form.
      save.reset();
      setMessage([t('websiteSettings.savedChanges')]);
      return;
    }
    save.mutate(patch);
  };

  // What readers get is about the stored blog, never about what is typed but not yet saved.
  const subscribeNote = t(`websiteSettings.subscribeForm.${saved.subscribeForm ?? 'offNoList'}`);

  return (
    <>
      <p className="subtitle"><Rich text={t('websiteSettings.subtitle', escapeArg(handle))} /></p>
      {message.length > 0 ? <ProblemBanner messages={message} /> : null}
      <FormProblem error={error} controls={(f) => document.getElementById(idFor(f)) !== null} />
      {businessesUnavailable ? <ProblemBanner errors={[t('websiteSettings.business.listUnavailable')]} /> : null}
      {!siteSpace && frontPagesUnavailable ? <ProblemBanner errors={[t('weblogSettings.frontDoor.unavailable')]} /> : null}
      <form className="settings-grid form-stacked" onSubmit={submit} noValidate>
        <div>
          <h2 className="section-head" id="settings-general">{t('websiteSettings.generalSettings')}</h2>

          {absolute === undefined ? null : (
          <div className="row mb-3">
            <span className="col-sm-3 col-form-label">{t('websiteSettings.weblogUrl')}</span>
            <div className="col-sm-9">
              <a id="weblogAbsoluteUrl" href={absolute} target="_blank" rel="noopener noreferrer">{absolute}</a>
              <button className={`clipbutton${copied ? ' copied' : ''}`} type="button"
                      aria-label={t('generic.copyToClipboard')}
                      onClick={() => {
                        void navigator.clipboard.writeText(absolute).then(() => {
                          setCopied(true);
                          timer.current = setTimeout(() => { setCopied(false); }, 1500);
                        });
                      }}>
                <img src={`${contextPath}/pressroom-ui/images/clippy.svg`} alt="" style={{ width: '0.9em', height: '0.9em' }} />
              </button>
            </div>
          </div>
          )}

          <Row id={idFor('name')} label={t('websiteSettings.websiteTitle')}>
            <input type="text" size={30} maxLength={255} {...bind('name')} />{field('name')}
          </Row>
          <Row id={idFor('tagline')} label={t('generic.tagline')}>
            <input type="text" size={30} maxLength={255} {...bind('tagline')} />{field('tagline')}
          </Row>
          <Row id={idFor('copyright')} label={t('websiteSettings.copyright')}
               help={<div className="form-text">{t('websiteSettings.copyright.tip')}</div>}>
            <input type="text" size={30} maxLength={255} {...bind('copyright')} />{field('copyright')}
          </Row>
          <Row id={idFor('icon')} label={t('websiteSettings.icon')}>
            <input type="text" size={30} maxLength={255} {...bind('icon')} />{field('icon')}
          </Row>
          <Row id={idFor('about')} label={t('websiteSettings.about')}>
            <textarea rows={3} cols={40} {...bind('about')} />{field('about')}
          </Row>
          <Row id={idFor('emailAddress')} label={t('websiteSettings.emailAddress')}>
            <input type="email" size={30} maxLength={255} {...bind('emailAddress')} />{field('emailAddress')}
          </Row>
          <Row id={idFor('entryDisplayCount')} label={t('websiteSettings.entryDisplayCount')}
               help={maxEntries === undefined ? undefined
                 : <div className="form-text">{t('websiteSettings.entryDisplayCount.tip', maxEntries)}</div>}>
            <input type="number" min={1} max={maxEntries} size={4} {...bind('entryDisplayCount')} />{field('entryDisplayCount')}
          </Row>

          <Check name="bean.active" checked={values.active} onChange={set('active')} label={t('websiteSettings.active')} />
          <Check name="bean.commentsEnabled" checked={values.commentsEnabled} onChange={set('commentsEnabled')}
                 label={t('weblogSettings.commentsEnabled')} />
          <Check name="bean.commentNotify" checked={values.commentNotify} onChange={set('commentNotify')}
                 label={t('weblogSettings.commentNotify')} />
          <Check name="bean.showMoreFromSite" checked={values.showMoreFromSite} onChange={set('showMoreFromSite')}
                 label={t('weblogSettings.showMoreFromSite')} help={t('weblogSettings.showMoreFromSite.help')} />
          <Check name="bean.shareRowEnabled" checked={values.shareRowEnabled} onChange={set('shareRowEnabled')}
                 label={t('weblogSettings.shareRowEnabled')} help={t('weblogSettings.shareRowEnabled.help')} />

          <Row id={idFor('entryOrder')} label={t('weblogSettings.entryOrder')}
               help={<div className="form-text">{t('weblogSettings.entryOrder.help')}</div>}>
            <select {...bind('entryOrder', 'form-select')}>
              <option value="NEWEST">{t('weblogSettings.entryOrder.newest')}</option>
              <option value="OLDEST">{t('weblogSettings.entryOrder.oldest')}</option>
            </select>{field('entryOrder')}
          </Row>
          <Row id={idFor('pageWidth')} label={t('weblogSettings.pageWidth')}
               help={<div className="form-text">{t('weblogSettings.pageWidth.help')}</div>}>
            <select {...bind('pageWidth', 'form-select')}>
              <option value="WIDE">{t('weblogSettings.pageWidth.wide')}</option>
              <option value="STANDARD">{t('weblogSettings.pageWidth.standard')}</option>
            </select>{field('pageWidth')}
          </Row>

          {siteSpace || frontPagesUnavailable ? null : (
            <Row id={idFor('frontPage')} label={t('weblogSettings.frontDoor')}
                 help={<div className="form-text">{t('weblogSettings.frontDoor.help')}</div>}>
              <select {...bind('frontPage', 'form-select')}>
                <option value="">{t('weblogSettings.frontDoor.postList')}</option>
                {frontPages.map((p) => <option key={p.id} value={p.id}>{p.title}</option>)}
              </select>{field('frontPage')}
            </Row>
          )}

          <h2 className="section-head" id="settings-language">{t('websiteSettings.languageSettings')}</h2>
          <Row id={idFor('locale')} label={t('createWebsite.locale')}>
            <select {...bind('locale', 'form-select')}>
              {(options.data?.locales ?? []).map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
            </select>{field('locale')}
          </Row>
          <Row id={idFor('timeZone')} label={t('createWebsite.timezone')}>
            <select {...bind('timeZone', 'form-select')}>
              {(options.data?.timeZones ?? []).map((z) => <option key={z} value={z}>{z}</option>)}
            </select>{field('timeZone')}
          </Row>

          <h2 className="section-head" id="settings-newsletter">{t('websiteSettings.newsletterSettings')}</h2>
          <Row id={idFor('newsletterListUuid')} label={t('websiteSettings.newsletterListUuid')}
               help={(
                 <>
                   <div className="form-text">{t('websiteSettings.newsletterListUuid.tip')}</div>
                   <div className="form-text" id="subscribe-form-status">{subscribeNote}</div>
                 </>
               )}>
            <input type="text" size={40} maxLength={64} {...bind('newsletterListUuid')} />{field('newsletterListUuid')}
          </Row>

          <h2 className="section-head" id="settings-business">{t('websiteSettings.businessSettings')}</h2>
          <Row id={idFor('businessId')} label={t('websiteSettings.business')}
               help={<div className="form-text">{t('websiteSettings.business.tip')}</div>}>
            {businessesUnavailable ? (
              <select id={idFor('businessId')} className="form-select" disabled>
                <option>{values.businessId === '' ? t('websiteSettings.business.none') : t('websiteSettings.business.kept')}</option>
              </select>
            ) : (
              <select {...bind('businessId', 'form-select')}>
                <option value="">{t('websiteSettings.business.none')}</option>
                {(businessList ?? []).map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
              </select>
            )}{field('businessId')}
          </Row>
          <Row id={idFor('placeType')} label={t('websiteSettings.placeType')}
               help={<div className="form-text">{t('websiteSettings.placeType.tip')}</div>}>
            <select {...bind('placeType', 'form-select')}>
              <option value="">{t('websiteSettings.placeType.none')}</option>
              <option value="LodgingBusiness">{t('websiteSettings.placeType.lodging')}</option>
            </select>{field('placeType')}
          </Row>
          <Row id={idFor('placeLocality')} label={t('websiteSettings.placeLocality')}>
            <input type="text" size={40} maxLength={128} {...bind('placeLocality')} />{field('placeLocality')}
          </Row>
          <Row id={idFor('placeRegion')} label={t('websiteSettings.placeRegion')}>
            <input type="text" size={40} maxLength={128} {...bind('placeRegion')} />{field('placeRegion')}
          </Row>
          <Row id={idFor('placeCountry')} label={t('websiteSettings.placeCountry')}
               help={<div className="form-text">{t('websiteSettings.placeCountry.tip')}</div>}>
            <input type="text" size={4} maxLength={2} {...bind('placeCountry')} />{field('placeCountry')}
          </Row>
          <Row id={idFor('placeLat')} label={t('websiteSettings.placeLat')}>
            <input type="text" size={40} maxLength={16} {...bind('placeLat')} />{field('placeLat')}
          </Row>
          <Row id={idFor('placeLng')} label={t('websiteSettings.placeLng')}>
            <input type="text" size={40} maxLength={16} {...bind('placeLng')} />{field('placeLng')}
          </Row>
          <Row id={idFor('bookingUrl')} label={t('websiteSettings.bookingUrl')}
               help={<div className="form-text">{t('websiteSettings.bookingUrl.tip')}</div>}>
            <input type="text" size={40} maxLength={255} {...bind('bookingUrl')} />{field('bookingUrl')}
          </Row>

          <h2 className="section-head" id="settings-customdomain">{t('websiteSettings.customDomainSettings')}</h2>
          <Row id={idFor('customDomain')} label={t('websiteSettings.customDomain')}
               help={(
                 <>
                   <div className="form-text">{t('websiteSettings.customDomain.tip')}</div>
                   {(saved.customDomain ?? '') === '' ? null : (
                     <div className="form-text">
                       <a href={`https://${saved.customDomain ?? ''}/`} target="_blank" rel="noopener noreferrer">
                         {`https://${saved.customDomain ?? ''}/`}
                       </a>
                     </div>
                   )}
                   {saved.customDomainOutsideCertZone === true
                     ? <div className="form-text text-warning">{t('websiteSettings.customDomain.outsideZone')}</div> : null}
                 </>
               )}>
            <input type="text" size={40} maxLength={255} {...bind('customDomain')} />{field('customDomain')}
          </Row>
        </div>

        <aside className="settings-rail">
          <div className="rail-box settings-rail-index">
            <h2 className="section-head">{t('websiteSettings.sections')}</h2>
            <nav className="section-index" id="settingsSectionIndex" aria-label={t('websiteSettings.sections')}>
              {SECTIONS.map(([id, key]) => (
                <a key={id} href={`#${id}`} className={section === id ? 'is-current' : undefined}>{t(key)}</a>
              ))}
            </nav>
          </div>
          <div className="rail-box">
            <button type="submit" className="btn btn-primary w-100" disabled={save.isPending}>
              {t('websiteSettings.button.update')}
            </button>
          </div>
          <div className="rail-box">
            <Link className="delete-link" to={`/weblogs/${encodeURIComponent(handle)}/remove`}>
              {t('websiteSettings.button.remove')}
            </Link>
          </div>
        </aside>
      </form>
    </>
  );
}
