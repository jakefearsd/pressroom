import { useMeta, useMyWeblogs } from '../api/queries';
import type { components } from '../api/schema';
import { ProblemBanner } from '../components/ProblemBanner';
import { AppLink } from '../frame/AppLink';
import { PageTitle } from '../frame/PageTitle';
import { useBoot } from '../context';
import { useT } from '../i18n/messages';
import { RAIL_ENTRIES, entryTarget, type LinkTarget } from '../routes/railTable';
import { attentionOrder } from './homeOrder';

type MyWeblog = components['schemas']['MyWeblog'];

/**
 * An authoring screen of one weblog: its React route once it has migrated (the rail table says so),
 * else its legacy page (a full page load).
 */
function authoring(contextPath: string, action: string, handle: string): LinkTarget {
  const migrated = RAIL_ENTRIES.find((entry) => entry.id === action);
  if (migrated !== undefined) {
    return entryTarget(migrated, contextPath, handle);
  }
  return { kind: 'legacy', to: `${contextPath}/pressroom-ui/authoring/${action}.rol?weblog=${encodeURIComponent(handle)}` };
}

function react(to: string): LinkTarget {
  return { kind: 'react', to };
}

/** "Admin, Author": the permission actions the member's row holds, humanised (inviteMember.*). */
function roleLabels(weblog: MyWeblog, t: ReturnType<typeof useT>): string {
  const labels: string[] = [];
  for (const [action, key] of [['admin', 'inviteMember.administrator'], ['post', 'inviteMember.author'],
    ['edit_draft', 'inviteMember.limited']] as const) {
    if (weblog.actions.includes(action)) {
      labels.push(t(key));
    }
  }
  return labels.join(', ');
}

/** "3 days ago", "today", "2 months ago" in the signed-in user's language; the clock is the browser's. */
function ago(iso: string, locale: string): string {
  const days = Math.max(0, Math.floor((Date.now() - Date.parse(iso)) / 86_400_000));
  const format = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
  if (days < 30) {
    return format.format(-days, 'day');
  }
  return days < 365 ? format.format(-Math.floor(days / 30), 'month') : format.format(-Math.floor(days / 365), 'year');
}

function WeblogRow({ weblog, customThemes }: { weblog: MyWeblog; customThemes: boolean }) {
  const t = useT();
  const { contextPath, uiLocale } = useBoot();
  const { handle, name } = weblog;
  const admin = weblog.actions.includes('admin');
  const limited = weblog.actions.includes('edit_draft') && !weblog.actions.includes('post') && !admin;
  const enc = encodeURIComponent(handle);
  const themeTarget = weblog.theme === 'custom'
    ? (customThemes ? authoring(contextPath, 'templates', handle) : undefined)
    : authoring(contextPath, 'themeEdit', handle);
  return (
    <div className="weblog-row">
      <h2 className="mm_weblog_name section-head">
        <span className="bi bi-folder2-open" aria-hidden="true" />{' '}
        {weblog.absoluteUrl === undefined ? name : <a href={weblog.absoluteUrl}>{name}</a>}{' '}
        <span className="data">{handle}</span>
      </h2>
      <p>{weblog.about}</p>
      {admin ? null : <p><span className="role-label">{roleLabels(weblog, t)}</span></p>}
      <p className="weblog-counts">
        {weblog.canModerate && weblog.pendingComments > 0 ? (
          <AppLink className="status-pill status-pending" target={react(`/weblogs/${enc}/comments?status=PENDING`)}>
            {t('mainMenu.pendingComments', weblog.pendingComments)}
          </AppLink>
        ) : null}
        {weblog.canModerate && weblog.newInquiries > 0 ? (
          <AppLink className="status-pill status-pending" target={authoring(contextPath, 'submissions', handle)}>
            {t('mainMenu.newInquiries', weblog.newInquiries)}
          </AppLink>
        ) : null}
        {limited || weblog.drafts === 0 ? null : (
          <AppLink className="quiet-link" target={react(`/weblogs/${enc}/entries?status=DRAFT`)}>
            {t('mainMenu.drafts', weblog.drafts)}
          </AppLink>
        )}
        {limited || weblog.scheduled === 0 ? null : (
          <AppLink className="quiet-link" target={react(`/weblogs/${enc}/entries?status=SCHEDULED`)}>
            {t('mainMenu.scheduled', weblog.scheduled)}
          </AppLink>
        )}
      </p>
      <p className="weblog-published data">
        {weblog.lastPublished === undefined
          ? t('mainMenu.neverPublished')
          : t('mainMenu.lastPublished', ago(weblog.lastPublished, uiLocale))}
      </p>
      <div className="weblog-actions" role="group" aria-label={name}>
        <AppLink className="btn btn-primary btn-sm" target={authoring(contextPath, 'entryAdd', handle)}>
          {t('yourWebsites.newEntry')}
        </AppLink>
        {weblog.actions.includes('edit_draft') ? null : (
          <AppLink className="quiet-link" target={authoring(contextPath, 'entries', handle)}>
            {t('yourWebsites.editEntries')} <span className="data">{weblog.entryCount}</span>
          </AppLink>
        )}
        {weblog.absoluteUrl === undefined ? null : (
          <a className="quiet-link" href={weblog.absoluteUrl}>{t('mainMenu.viewBlog')}</a>
        )}
        {admin ? (
          <>
            {themeTarget === undefined ? null : (
              <AppLink className="quiet-link" target={themeTarget}>{t('yourWebsites.theme')}</AppLink>
            )}
            <AppLink className="quiet-link" target={authoring(contextPath, 'weblogConfig', handle)}>
              {t('yourWebsites.manage')}
            </AppLink>
          </>
        ) : null}
      </div>
    </div>
  );
}

/** A site admin's way into the site's own pages: the three screens the interceptor lets reach the site space. */
function SiteRow({ handle }: { handle: string }) {
  const t = useT();
  const { contextPath } = useBoot();
  const label = t('mainMenu.site.title');
  return (
    <div className="weblog-row site-row">
      <h2 className="mm_weblog_name section-head">
        <span className="bi bi-globe2" aria-hidden="true" /> {label}
      </h2>
      <p>{t('mainMenu.site.desc')}</p>
      <div className="weblog-actions" role="group" aria-label={label}>
        <AppLink className="quiet-link" target={authoring(contextPath, 'pages', handle)}>{t('mainMenu.site.pages')}</AppLink>
        <AppLink className="quiet-link" target={authoring(contextPath, 'mediaFileView', handle)}>{t('mainMenu.site.media')}</AppLink>
        <AppLink className="quiet-link" target={authoring(contextPath, 'themeEdit', handle)}>{t('mainMenu.site.theme')}</AppLink>
      </div>
    </div>
  );
}

/** "5 comments awaiting approval across 2 blogs · Review all": only when something waits. */
function AttentionSummary({ weblogs }: { weblogs: readonly MyWeblog[] }) {
  const t = useT();
  const waiting = weblogs.filter((w) => w.canModerate && w.pendingComments > 0);
  const total = waiting.reduce((sum, w) => sum + w.pendingComments, 0);
  if (total === 0) {
    return null;
  }
  return (
    <p className="attention-summary">
      {t('mainMenu.summary', total, waiting.length)} &middot;{' '}
      <AppLink target={react('/comments')}>{t('mainMenu.summary.review')}</AppLink>
    </p>
  );
}

/** The app home, the main menu's replacement: the weblogs the signed-in user can act on. */
export function Home() {
  const t = useT();
  const mine = useMyWeblogs();
  const meta = useMeta();
  const weblogs = mine.data?.weblogs;
  return (
    <>
      <PageTitle>{t('yourWebsites.title')}</PageTitle>
      <ProblemBanner error={mine.error} />
      {weblogs === undefined ? null : weblogs.length === 0 ? (
        <div className="empty-state">
          <p className="empty-state-title">{t('empty.weblogs.title')}</p>
          <p className="empty-state-body">{t('empty.weblogs.body')}</p>
          <AppLink id="createWeblogLink" className="btn btn-primary" target={react('/weblogs/new')}>
            {t('empty.weblogs.action')}
          </AppLink>
        </div>
      ) : (
        <>
          <p className="subtitle">{t('yourWebsites.prompt.hasBlog')}</p>
          <AttentionSummary weblogs={weblogs} />
          {[...weblogs].sort(attentionOrder).map((weblog) => (
            <WeblogRow key={weblog.handle} weblog={weblog} customThemes={meta.data?.features.customThemes === true} />
          ))}
        </>
      )}
      {mine.data?.siteSpace === undefined ? null : <SiteRow handle={mine.data.siteSpace} />}
    </>
  );
}

/** The page's sidebar (MainMenuSidebar.jsp): profile, create a weblog, and a site admin's settings. */
export function HomeSidebar() {
  const t = useT();
  const { user } = useBoot();
  const mine = useMyWeblogs();
  const meta = useMeta();
  const features = meta.data?.features;
  const creation = features?.userWeblogCreation === true
    && (features.groupBlogging || mine.data?.weblogs.length === 0);
  const group = (icon: string, target: LinkTarget, label: string, description: string) => (
    <div className="sidebar-group">
      <p className="sidebar-label">
        <span className={`bi ${icon}`} aria-hidden="true" /> <AppLink target={target}>{label}</AppLink>
      </p>
      <p>{description}</p>
    </div>
  );
  return (
    <>
      {group('bi-person', react('/profile'), t('yourWebsites.editProfile'), t('yourWebsites.editProfile.desc'))}
      {mine.data?.weblogs.some((w) => w.canModerate) === true
        ? group('bi-chat-left-text', react('/comments'), t('yourWebsites.comments'), t('yourWebsites.comments.desc'))
        : null}
      {creation ? group('bi-plus-lg', react('/weblogs/new'), t('yourWebsites.createWeblog'),
        t('yourWebsites.createWeblog.desc')) : null}
      {user.globalPermission === 'admin'
        ? group('bi-wrench', react('/admin/config'), t('yourWebsites.globalAdmin'), t('yourWebsites.globalAdmin.desc'))
        : null}
    </>
  );
}
