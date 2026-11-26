/**
 * The rail's single source: every tool group and entry, copied one-for-one from
 * editor-menu.xml and admin-menu.xml (a test parses the XML and compares). Migrating a
 * screen flips one field: give its entry a `path` and the rail links the React route.
 */
export type WeblogLevel = 'edit_draft' | 'post' | 'admin';
export type Requirement = { global: 'admin' } | { weblog: WeblogLevel };
export type Menu = 'weblog' | 'admin';
export type Feature = 'customThemes' | 'groupBlogging';

export interface RailGroup {
  /** Message key of the caps-label. */
  key: string;
  menu: Menu;
  requires: Requirement;
}

export interface RailEntry {
  /** The legacy action name: `/pressroom-ui/{authoring|admin}/<id>.rol`. */
  id: string;
  messageKey: string;
  /** Key of the RailGroup it sits under. */
  group: string;
  requires: Requirement;
  /** The meta feature switch an enabledProperty gated the item on. */
  feature?: Feature;
  /** React route pattern (under the app basename) once the screen has migrated; absent = legacy `.rol` link. */
  path?: string;
  /** In BaseController.SWITCHER_ACTIONS: the weblog switcher keeps this screen. */
  switchable?: boolean;
}

const weblog = (level: WeblogLevel): Requirement => ({ weblog: level });
const admin: Requirement = { global: 'admin' };

export const RAIL_GROUPS: readonly RailGroup[] = [
  { key: 'tabbedmenu.weblog', menu: 'weblog', requires: weblog('edit_draft') },
  { key: 'tabbedmenu.design', menu: 'weblog', requires: weblog('admin') },
  { key: 'tabbedmenu.website', menu: 'weblog', requires: weblog('admin') },
  { key: 'tabbedmenu.admin', menu: 'admin', requires: admin }
];

export const RAIL_ENTRIES: readonly RailEntry[] = [
  { id: 'entryAdd', messageKey: 'tabbedmenu.weblog.newEntry', group: 'tabbedmenu.weblog', requires: weblog('edit_draft') },
  { id: 'entries', messageKey: 'tabbedmenu.weblog.archives', group: 'tabbedmenu.weblog', requires: weblog('post'), switchable: true },
  { id: 'trash', messageKey: 'tabbedmenu.trash', group: 'tabbedmenu.weblog', requires: weblog('post'), switchable: true },
  { id: 'submissions', messageKey: 'tabbedmenu.submissions', group: 'tabbedmenu.weblog', requires: weblog('post'), switchable: true },
  { id: 'comments', messageKey: 'tabbedmenu.comments', group: 'tabbedmenu.weblog', requires: weblog('post'), switchable: true },
  { id: 'categories', messageKey: 'tabbedmenu.weblog.categories', group: 'tabbedmenu.weblog', requires: weblog('post'), switchable: true },
  { id: 'pages', messageKey: 'tabbedmenu.pages', group: 'tabbedmenu.weblog', requires: weblog('post'), switchable: true },
  { id: 'mediaFileView', messageKey: 'tabbedmenu.weblog.mediaFiles', group: 'tabbedmenu.weblog', requires: weblog('post'), switchable: true },
  { id: 'traffic', messageKey: 'tabbedmenu.weblog.traffic', group: 'tabbedmenu.weblog', requires: weblog('admin'), switchable: true },

  { id: 'themeEdit', messageKey: 'tabbedmenu.website.themes', group: 'tabbedmenu.design', requires: weblog('admin'), switchable: true },
  { id: 'stylesheetEdit', messageKey: 'tabbedmenu.design.stylesheet', group: 'tabbedmenu.design', requires: weblog('admin'), feature: 'customThemes' },
  { id: 'templates', messageKey: 'tabbedmenu.website.pages', group: 'tabbedmenu.design', requires: weblog('admin'), feature: 'customThemes' },

  { id: 'weblogConfig', messageKey: 'tabbedmenu.website.settings', group: 'tabbedmenu.website', requires: weblog('admin'), switchable: true },
  { id: 'members', messageKey: 'tabbedmenu.website.members', group: 'tabbedmenu.website', requires: weblog('admin'), feature: 'groupBlogging', switchable: true },

  { id: 'globalConfig', messageKey: 'tabbedmenu.admin.config', group: 'tabbedmenu.admin', requires: admin },
  { id: 'userAdmin', messageKey: 'tabbedmenu.admin.userAdmin', group: 'tabbedmenu.admin', requires: admin },
  { id: 'businesses', messageKey: 'tabbedmenu.admin.businesses', group: 'tabbedmenu.admin', requires: admin },
  { id: 'apiTokens', messageKey: 'tabbedmenu.admin.apiTokens', group: 'tabbedmenu.admin', requires: admin },
  { id: 'siteTraffic', messageKey: 'tabbedmenu.admin.siteTraffic', group: 'tabbedmenu.admin', requires: admin },
  { id: 'maintenance', messageKey: 'tabbedmenu.admin.maintenance', group: 'tabbedmenu.admin', requires: admin }
];

/** Chrome links outside the rail; `path` flips to a React route when the screen migrates. */
export const CHROME_LINKS = {
  mainMenu: { legacy: '/pressroom-ui/menu.rol', path: undefined as string | undefined },
  profile: { legacy: '/pressroom-ui/profile.rol', path: undefined as string | undefined },
  logout: { legacy: '/pressroom-ui/logout.rol', path: undefined as string | undefined },
  login: { legacy: '/pressroom-ui/login.rol', path: undefined as string | undefined }
} as const;

export interface LinkTarget {
  /** A React route (client-side navigation, under the basename) or a legacy URL (full page load). */
  kind: 'react' | 'legacy';
  to: string;
}

export function entryTarget(entry: RailEntry, contextPath: string, handle: string | undefined): LinkTarget {
  if (entry.path !== undefined) {
    return { kind: 'react', to: entry.path.replace(':handle', encodeURIComponent(handle ?? '')) };
  }
  const menu = RAIL_GROUPS.find((g) => g.key === entry.group)?.menu;
  if (menu === 'weblog') {
    return {
      kind: 'legacy',
      to: `${contextPath}/pressroom-ui/authoring/${entry.id}.rol?weblog=${encodeURIComponent(handle ?? '')}`
    };
  }
  return { kind: 'legacy', to: `${contextPath}/pressroom-ui/admin/${entry.id}.rol` };
}
