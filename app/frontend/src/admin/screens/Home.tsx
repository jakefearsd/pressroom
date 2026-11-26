import { useMeta, useMyWeblogs } from '../api/queries';
import type { components } from '../api/schema';
import { ProblemBanner } from '../components/ProblemBanner';
import { AppLink } from '../frame/AppLink';
import { PageTitle } from '../frame/PageTitle';
import { useBoot } from '../context';
import { useT } from '../i18n/messages';
import { RAIL_ENTRIES, entryTarget, type LinkTarget } from '../routes/railTable';

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

function WeblogRow({ weblog, customThemes }: { weblog: MyWeblog; customThemes: boolean }) {
  const t = useT();
  const { contextPath } = useBoot();
  const { handle, name } = weblog;
  const admin = weblog.actions.includes('admin');
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
      <p>{t('yourWebsites.permission.summary', roleLabels(weblog, t))}</p>
      <div className="weblog-actions" role="group" aria-label={name}>
        <AppLink className="btn btn-primary btn-sm" target={authoring(contextPath, 'entryAdd', handle)}>
          {t('yourWebsites.newEntry')}
        </AppLink>
        {weblog.actions.includes('edit_draft') ? null : (
          <AppLink className="quiet-link" target={authoring(contextPath, 'entries', handle)}>
            {t('yourWebsites.editEntries')} <span className="data">{weblog.entryCount}</span>
          </AppLink>
        )}
        {weblog.canModerate && weblog.pendingComments > 0 ? (
          <AppLink className="status-pill status-pending" target={authoring(contextPath, 'comments', handle)}>
            {t('mainMenu.pendingComments', weblog.pendingComments)}
          </AppLink>
        ) : null}
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
          {weblogs.map((weblog) => (
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
      {creation ? group('bi-plus-lg', react('/weblogs/new'), t('yourWebsites.createWeblog'),
        t('yourWebsites.createWeblog.desc')) : null}
      {user.globalPermission === 'admin'
        ? group('bi-wrench', react('/admin/config'), t('yourWebsites.globalAdmin'), t('yourWebsites.globalAdmin.desc'))
        : null}
    </>
  );
}
