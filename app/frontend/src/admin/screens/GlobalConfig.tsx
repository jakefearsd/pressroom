import { useMemo, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { useAllWeblogs, useConfig, useConfigDefinitions, useSaveConfig, useSitePages, type ConfigPropertyDefinition } from '../api/siteQueries';
import { ProblemBanner } from '../components/ProblemBanner';
import { Markup } from '../components/Markup';
import { useFieldErrors } from '../forms/useFieldErrors';
import { PageTitle } from '../frame/PageTitle';
import { useT } from '../i18n/messages';

const FRONT_BLOG = 'site.frontpage.weblog.handle';
const FRONT_PAGE = 'site.frontpage.page';
const REFRESH = 'site.discovery.refreshSeconds';
const PAGE_CHOICE = 'page:';
const SITE_SPACE = 'site-space';

const idOf = (name: string) => `globalConfig_${name.replaceAll('.', '_')}`;
/** A refused front-door page is shown on the one select that carries both settings. */
const controlFor = (field: string) => idOf(field === FRONT_PAGE ? FRONT_BLOG : field);

/** Whole minutes in a stored number of seconds, as DiscoverySettings.refreshMinutesFrom shows it. */
function minutesFrom(seconds: string | undefined): string {
  const parsed = Number.parseInt(seconds ?? '', 10);
  return Number.isNaN(parsed) ? '' : String(Math.max(1, Math.round(parsed / 60)));
}

/** True for a number field whose text is not a number (or, for an integer, not whole). */
function badNumber(type: string, text: string): boolean {
  const value = text.trim() === '' ? Number.NaN : Number(text);
  return Number.isNaN(value) || (type === 'integer' && !Number.isInteger(value));
}

type Values = Record<string, string>;

/** The form's starting values from the stored ones: the front door as one choice, the interval in minutes. */
function initial(stored: Values): Values {
  const page = stored[FRONT_PAGE] ?? '';
  return { ...stored, [FRONT_BLOG]: page === '' ? stored[FRONT_BLOG] ?? '' : `${PAGE_CHOICE}${page}`, [REFRESH]: minutesFrom(stored[REFRESH]) };
}

/** What changed, as the API takes it: the front door as both settings, minutes as seconds, booleans as text. */
function changes(form: Values, start: Values, types: Record<string, string>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [name, value] of Object.entries(form)) {
    if (value === start[name]) {
      continue;
    }
    if (name === FRONT_BLOG) {
      const page = value.startsWith(PAGE_CHOICE);
      out[FRONT_BLOG] = page ? '' : value;
      out[FRONT_PAGE] = page ? value.slice(PAGE_CHOICE.length) : '';
    } else if (name === REFRESH) {
      out[REFRESH] = String(Number.parseInt(value, 10) * 60);
    } else {
      out[name] = types[name] === 'boolean' ? String(value === 'true') : value;
    }
  }
  return out;
}

/** GlobalConfig.jsp: every runtime property of the definitions, grouped, with a section index and Save in the rail. */
export function GlobalConfig() {
  const t = useT();
  const definitions = useConfigDefinitions();
  const config = useConfig();
  const weblogs = useAllWeblogs();
  const pages = useSitePages();
  const save = useSaveConfig((total) => total === undefined ? t('configForm.revisionsNextSweepUnknown')
    : total > 0 ? t('configForm.revisionsNextSweep', String(total)) : undefined);
  const fields = useFieldErrors(save.error, controlFor);

  const stored = useMemo<Values>(() => Object.fromEntries((config.data ?? []).map((e) => [e.name ?? '', e.value ?? ''])), [config.data]);
  const start = useMemo(() => initial(stored), [stored]);
  // What the person typed over what is stored; saved edits are dropped once the stored values reload.
  const [edits, setEdits] = useState<Values>({});
  const form = useMemo(() => ({ ...start, ...edits }), [start, edits]);
  const [saved, setSaved] = useState<string[]>([]);

  const groups = useMemo(() => definitions.data ?? [], [definitions.data]);
  const types = useMemo(() => Object.fromEntries(groups.flatMap((g) => g.properties.map((p) => [p.name, p.type ?? 'string']))), [groups]);
  const publishedPages = (pages.data ?? []).filter((p) => p.status === 'PUBLISHED');
  const blogs = (weblogs.data?.items ?? []).filter((w) => w.handle !== SITE_SPACE);
  const storedPage = stored[FRONT_PAGE] ?? '';
  const stalePage = storedPage !== '' && pages.data !== undefined && !publishedPages.some((p) => p.slug === storedPage) ? storedPage : '';

  const numbersBad = groups.some((g) => g.properties.some((p) =>
    (p.type === 'integer' || p.type === 'float') && badNumber(p.type, form[p.name] ?? '')));
  const set = (name: string, value: string) => { setEdits((current) => ({ ...current, [name]: value })); };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setSaved([]);
    save.mutate(changes(form, start, types), { onSuccess: (extra) => { setEdits({}); setSaved([t('generic.changes.saved'), ...extra]); } });
  };

  const invalidClass = (name: string, base: string, bad = false) => {
    const refused = fields.invalid(name) || (name === FRONT_BLOG && fields.invalid(FRONT_PAGE));
    return [base, refused ? 'is-invalid' : '', bad ? 'field-invalid-highlight' : ''].filter((c) => c !== '').join(' ');
  };
  const refusedProps = (name: string) => (fields.invalid(name) || (name === FRONT_BLOG && fields.invalid(FRONT_PAGE))
    ? { 'aria-invalid': true as const } : {});

  const label = (p: ConfigPropertyDefinition) => <label className="col-sm-3 col-form-label" htmlFor={idOf(p.name)}>{t(p.key)}</label>;
  const row = (p: ConfigPropertyDefinition, control: React.ReactNode) => (
    <div className="row mb-3" key={p.name}>
      {label(p)}
      <div className="col-sm-9">{control}</div>
    </div>
  );

  const frontDoor = (p: ConfigPropertyDefinition) => row(p, (
    <>
      <select name={p.name} id={idOf(p.name)} className={invalidClass(p.name, 'form-select')} {...refusedProps(p.name)}
              value={form[p.name] ?? ''} onChange={(event) => { set(p.name, event.target.value); }}>
        <option value="">{t('configForm.frontpageWeblogHandle.none')}</option>
        {stalePage === '' ? null : (
          <option value={`${PAGE_CHOICE}${stalePage}`}>{t('configForm.frontpageStalePage', stalePage)}</option>
        )}
        {publishedPages.length === 0 ? null : (
          <optgroup label={t('configForm.frontpageSitePages')}>
            {publishedPages.map((page) => (
              <option key={page.slug} value={`${PAGE_CHOICE}${page.slug}`}>{page.title} (/{page.slug})</option>
            ))}
          </optgroup>
        )}
        {blogs.map((w) => <option key={w.handle} value={w.handle}>{w.handle}</option>)}
      </select>
      {publishedPages.length > 0 ? null : (
        <div className="form-text">
          {t('configForm.frontpageNoSitePages')}{' '}
          <Link to={`/weblogs/${SITE_SPACE}/pages`}>{t('configForm.frontpageSitePagesLink')}</Link>
        </div>
      )}
    </>
  ));

  const field = (p: ConfigPropertyDefinition) => {
    const value = form[p.name] ?? '';
    const onChange = (event: { target: { value: string } }) => { set(p.name, event.target.value); };
    if (p.name === FRONT_BLOG) {
      return frontDoor(p);
    }
    if (p.name === FRONT_PAGE) {
      return null;
    }
    if (p.name === REFRESH) {
      return row(p, (
        <input type="number" name="discoveryRefreshMinutes" min="1" max="1440" value={value} id={idOf(p.name)}
               className={invalidClass(p.name, 'form-control integer', badNumber('integer', value))}
               {...refusedProps(p.name)} onChange={onChange} />
      ));
    }
    switch (p.type) {
      case 'text':
        return row(p, <textarea name={p.name} rows={p.rows} cols={p.cols} id={idOf(p.name)} value={value}
                                className={invalidClass(p.name, 'form-control')} {...refusedProps(p.name)} onChange={onChange} />);
      case 'boolean':
        return (
          <div className="row mb-3" key={p.name}>
            <div className="form-check">
              <input type="checkbox" name={p.name} value="true" id={idOf(p.name)} checked={value === 'true'}
                     className={invalidClass(p.name, 'form-check-input boolean')} {...refusedProps(p.name)}
                     onChange={(event) => { set(p.name, String(event.target.checked)); }} />
              <label className="form-check-label" htmlFor={idOf(p.name)}>{t(p.key)}</label>
            </div>
          </div>
        );
      case 'integer':
      case 'float':
        return row(p, <input type="number" name={p.name} value={value} id={idOf(p.name)}
                             className={invalidClass(p.name, `form-control ${p.type}`, badNumber(p.type, value))}
                             {...refusedProps(p.name)} onChange={onChange} />);
      default:
        return row(p, <input type="text" name={p.name} size={35} value={value} id={idOf(p.name)}
                             className={invalidClass(p.name, 'form-control')} {...refusedProps(p.name)} onChange={onChange} />);
    }
  };

  return (
    <>
      <PageTitle>{t('configForm.title')}</PageTitle>
      <ProblemBanner messages={saved} error={definitions.error ?? config.error ?? save.error} />
      <p className="subtitle">{t('configForm.subtitle')}</p>
      <Markup text={t('configForm.prompt')} />
      {definitions.data === undefined || config.data === undefined ? null : (
        <form onSubmit={submit} className="settings-grid form-stacked">
          <div>
            {groups.map((g) => (
              <section key={g.name}>
                <h2 className="section-head" id={`cfg-${g.key}`}>{t(g.key)}</h2>
                {g.properties.map(field)}
              </section>
            ))}
          </div>
          <aside className="settings-rail">
            <div className="rail-box settings-rail-index">
              <h2 className="section-head">{t('websiteSettings.sections')}</h2>
              <nav className="section-index" id="globalConfigSectionIndex" aria-label={t('websiteSettings.sections')}>
                {groups.map((g, index) => (
                  <a key={g.name} href={`#cfg-${g.key}`} className={index === 0 ? 'is-current' : undefined}>{t(g.key)}</a>
                ))}
              </nav>
            </div>
            <div className="rail-box">
              <button id="saveButton" className="btn btn-primary w-100" type="submit" disabled={numbersBad || save.isPending}>
                {t('generic.save')}
              </button>
            </div>
          </aside>
        </form>
      )}
    </>
  );
}
