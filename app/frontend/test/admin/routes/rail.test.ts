import { readFileSync } from 'node:fs';
import { RAIL_ENTRIES, RAIL_GROUPS, entryTarget, type Feature, type Menu, type RailEntry } from '../../../src/admin/routes/railTable';
import { visibleGroups, weblogLevel } from '../../../src/admin/routes/visibility';

const MENU_DIR = '../src/main/resources/com/wikantik/pressroom/ui/menu/';
const RANK: Record<string, number> = { edit_draft: 1, post: 2, admin: 3 };
const FEATURE: Record<string, Feature> = {
  'themes.customtheme.allowed': 'customThemes',
  'groupblogging.enabled': 'groupBlogging'
};

interface XmlTab { name: string; perm: string | null; items: { action: string; name: string; perm: string | null; property: string | null }[] }

function menuXml(file: string): XmlTab[] {
  const doc = new DOMParser().parseFromString(readFileSync(MENU_DIR + file, 'utf8'), 'application/xml');
  return [...doc.querySelectorAll('menu-bar > menu')].map((tab) => ({
    name: tab.getAttribute('name') ?? '',
    perm: tab.getAttribute('weblogPerms'),
    items: [...tab.querySelectorAll(':scope > menu-item')].map((item) => ({
      action: item.getAttribute('action') ?? '',
      name: item.getAttribute('name') ?? '',
      perm: item.getAttribute('weblogPerms'),
      property: item.getAttribute('enabledProperty')
    }))
  }));
}

/** MenuHelper.buildMenu's rules, applied straight to the XML (the oracle the table is held to). */
function expected(tabs: XmlTab[], level: number, features: Partial<Record<Feature, boolean>>) {
  return tabs
    .filter((tab) => level >= (tab.perm === null ? 0 : RANK[tab.perm] ?? 99))
    .map((tab) => ({
      key: tab.name,
      entries: tab.items
        .filter((item) => (item.property === null || features[FEATURE[item.property] as Feature] === true)
          && level >= (item.perm === null ? 0 : RANK[item.perm] ?? 99))
        .map((item) => [item.action, item.name])
    }));
}

const FEATURE_SETS: Partial<Record<Feature, boolean>>[] = [
  { customThemes: false, groupBlogging: false }, { customThemes: true, groupBlogging: false },
  { customThemes: false, groupBlogging: true }, { customThemes: true, groupBlogging: true }
];

describe('the rail table against editor-menu.xml', () => {
  const tabs = menuXml('editor-menu.xml');

  it.each(['EDIT_DRAFT', 'POST', 'ADMIN'])('shows a %s member what the JSP menu shows', (permission) => {
    for (const features of FEATURE_SETS) {
      const actual = visibleGroups('weblog', { globalAdmin: false, membership: permission, features })
        .map((g) => ({ key: g.key, entries: g.entries.map((e) => [e.id, e.messageKey]) }));
      expect(actual).toEqual(expected(tabs, RANK[permission.toLowerCase()] ?? 0, features));
    }
  });

  it('shows a site admin who is no member the full menu (admin implies every weblog permission)', () => {
    for (const features of FEATURE_SETS) {
      const actual = visibleGroups('weblog', { globalAdmin: true, membership: null, features })
        .map((g) => ({ key: g.key, entries: g.entries.map((e) => [e.id, e.messageKey]) }));
      expect(actual).toEqual(expected(tabs, 3, features));
    }
  });

  it('shows nothing to someone with no permission on the weblog', () => {
    expect(visibleGroups('weblog', { globalAdmin: false, membership: null, features: {} })).toEqual([]);
    expect(visibleGroups('weblog', { globalAdmin: false, membership: 'SURPRISE', features: {} })).toEqual([]);
    expect(weblogLevel({ globalAdmin: false, membership: undefined })).toBe(0);
  });

  it('covers every XML tab and item, and nothing the XML lacks', () => {
    const fromXml = tabs.flatMap((t) => t.items.map((i) => `${t.name}/${i.action}/${i.name}`));
    const fromTable = RAIL_ENTRIES.filter((e) => RAIL_GROUPS.find((g) => g.key === e.group)?.menu === 'weblog')
      .map((e) => `${e.group}/${e.id}/${e.messageKey}`);
    expect(fromTable).toEqual(fromXml);
  });
});

describe('the rail table against admin-menu.xml', () => {
  const tabs = menuXml('admin-menu.xml');

  it('shows a site admin the JSP admin menu, in order', () => {
    const actual = visibleGroups('admin', { globalAdmin: true, membership: null, features: {} })
      .map((g) => ({ key: g.key, entries: g.entries.map((e) => [e.id, e.messageKey]) }));
    expect(actual).toEqual(expected(tabs, 0, {}));
  });

  it('shows an editor nothing', () => {
    expect(visibleGroups('admin', { globalAdmin: false, membership: 'ADMIN', features: {} })).toEqual([]);
  });

  it.each<Menu>(['weblog', 'admin'])('has no %s group the XML lacks', (menu) => {
    const names = menuXml(menu === 'weblog' ? 'editor-menu.xml' : 'admin-menu.xml').map((t) => t.name);
    expect(RAIL_GROUPS.filter((g) => g.menu === menu).map((g) => g.key)).toEqual(names);
  });
});

describe('the switcher set', () => {
  /** The screens the top-bar weblog switcher keeps when the reader picks another weblog (the rail's own tabs). */
  it('is the weblog-scoped rail tabs', () => {
    expect(RAIL_ENTRIES.filter((e) => e.switchable === true).map((e) => e.id).sort()).toEqual([
      'categories', 'comments', 'entries', 'mediaFileView', 'members', 'pages', 'submissions', 'themeEdit',
      'traffic', 'trash', 'weblogConfig'
    ]);
  });
});

describe('entryTarget', () => {
  const entries = RAIL_ENTRIES.find((e) => e.id === 'entries');
  const themes = RAIL_ENTRIES.find((e) => e.id === 'themeEdit');
  const config = RAIL_ENTRIES.find((e) => e.id === 'globalConfig');
  // Every Server admin screen has migrated, so an unmigrated admin entry is the same entry with no path.
  const legacyConfig = Object.fromEntries(
    Object.entries(config as NonNullable<typeof config>).filter(([key]) => key !== 'path')) as unknown as RailEntry;

  it('links an unmigrated weblog screen to its authoring .rol with the handle', () => {
    const unmigrated = Object.fromEntries(
      Object.entries(themes as NonNullable<typeof themes>).filter(([key]) => key !== 'path'));
    expect(entryTarget(unmigrated as never, '/pressroom', 'my blog')).toEqual({
      kind: 'legacy', to: '/pressroom/pressroom-ui/authoring/themeEdit.rol?weblog=my%20blog'
    });
  });

  it('links an unmigrated admin screen to its admin .rol', () => {
    expect(entryTarget(legacyConfig, '', undefined)).toEqual({ kind: 'legacy', to: '/pressroom-ui/admin/globalConfig.rol' });
  });

  it('links a migrated screen to its React route, handle filled in', () => {
    expect(entryTarget({ ...(entries as never as object), path: '/weblogs/:handle/entries' } as never, '/pressroom', 'a b'))
      .toEqual({ kind: 'react', to: '/weblogs/a%20b/entries' });
    expect(entryTarget({ ...legacyConfig, path: '/admin/config' }, '', undefined))
      .toEqual({ kind: 'react', to: '/admin/config' });
  });

  it('links the migrated screens to React routes and every other entry to its .rol', () => {
    const migrated = RAIL_ENTRIES.filter((e) => e.path !== undefined).map((e) => [e.id, e.path]);
    expect(migrated).toEqual(expect.arrayContaining([['entryAdd', '/weblogs/:handle/entries/new'], ['entries', '/weblogs/:handle/entries'], ['trash', '/weblogs/:handle/trash'], ['comments', '/weblogs/:handle/comments'], ['categories', '/weblogs/:handle/categories'], ['globalConfig', '/admin/config'], ['userAdmin', '/admin/users'], ['businesses', '/admin/businesses'], ['apiTokens', '/admin/tokens'], ['maintenance', '/admin/maintenance'], ['pages', '/weblogs/:handle/pages'], ['mediaFileView', '/weblogs/:handle/media'], ['traffic', '/weblogs/:handle/traffic'], ['siteTraffic', '/admin/traffic']]));
  });

  it('links the wave 3 screens (settings, theme, stylesheet, templates, members, inbox) to their React routes', () => {
    const migrated = Object.fromEntries(RAIL_ENTRIES.filter((e) => e.path !== undefined).map((e) => [e.id, e.path]));
    expect(migrated).toMatchObject({
      weblogConfig: '/weblogs/:handle/settings', themeEdit: '/weblogs/:handle/theme',
      stylesheetEdit: '/weblogs/:handle/stylesheet', templates: '/weblogs/:handle/templates',
      members: '/weblogs/:handle/members', submissions: '/weblogs/:handle/inbox'
    });
  });

  it('offers the site space only the screens the interceptor lets reach it, and drops a tab left empty', () => {
    const site = visibleGroups('weblog', { globalAdmin: true, membership: null, features: { customThemes: true, groupBlogging: true }, siteSpace: true });
    expect(site.map((g) => [g.key, g.entries.map((e) => e.id)])).toEqual([
      ['tabbedmenu.weblog', ['pages', 'mediaFileView']],
      ['tabbedmenu.design', ['themeEdit']]
    ]);
  });
});
