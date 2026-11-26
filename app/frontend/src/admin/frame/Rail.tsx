import { useBoot } from '../context';
import { plainText, useT } from '../i18n/messages';
import { entryTarget, CHROME_LINKS } from '../routes/railTable';
import type { RailEntry } from '../routes/railTable';
import type { VisibleGroup } from '../routes/visibility';
import { AppLink } from './AppLink';

export interface RailWeblog {
  handle: string;
  name: string;
  visible: boolean;
  url: string | undefined;
}

/** The rail's context header (userStatus.jsp): the weblog in play, else the signed-in user. */
export function UserStatus({ weblog }: { weblog: RailWeblog | undefined }) {
  const t = useT();
  const { contextPath, user } = useBoot();
  if (weblog !== undefined) {
    return (
      <>
        <span className="visually-hidden">{t('mainPage.currentWebsite')}:</span>
        <div className="rail-context-name">
          {weblog.url === undefined ? weblog.name : <a href={weblog.url}>{weblog.name}</a>}
        </div>
        <div className="rail-context-handle">
          {weblog.visible ? <span className="rail-status-dot" aria-hidden="true" /> : null}
          {weblog.handle}
        </div>
      </>
    );
  }
  return (
    <>
      <span className="visually-hidden">{t('mainPage.loggedInAs')}:</span>
      <div className="rail-context-name">
        {CHROME_LINKS.profile.path === undefined
          ? <a href={contextPath + CHROME_LINKS.profile.legacy}>{user.userName}</a>
          : <AppLink target={{ kind: 'react', to: CHROME_LINKS.profile.path }}>{user.userName}</AppLink>}
      </div>
    </>
  );
}

/** The rail (`nav#adminRail`): context, then each tool group under its caps-label. */
export function Rail({ weblog, groups, active }: {
  weblog: RailWeblog | undefined;
  groups: readonly VisibleGroup[];
  active: RailEntry | undefined;
}) {
  const t = useT();
  const { contextPath } = useBoot();
  return (
    <nav className="rail" id="adminRail">
      <div className="rail-context">
        <UserStatus weblog={weblog} />
      </div>
      {groups.map((group) => (
        <div className="rail-group" key={group.key}>
          <div className="rail-group-label">{plainText(t(group.key))}</div>
          {group.entries.map((entry) => {
            const selected = entry.id === active?.id;
            return (
              <AppLink key={entry.id} className={`rail-link${selected ? ' rail-active' : ''}`} current={selected}
                       target={entryTarget(entry, contextPath, weblog?.handle)}>
                {t(entry.messageKey)}
              </AppLink>
            );
          })}
        </div>
      ))}
    </nav>
  );
}
