import { RAIL_ENTRIES, RAIL_GROUPS, type Feature, type Menu, type RailEntry, type Requirement, type WeblogLevel } from './railTable';

const RANK: Record<WeblogLevel, number> = { edit_draft: 1, post: 2, admin: 3 };

/** What decides a rail's contents; all of it is on /api (me, meta). */
export interface VisibilityInput {
  globalAdmin: boolean;
  /** The member's permission on the action weblog (ADMIN/POST/EDIT_DRAFT from /v1/me), or null. */
  membership: string | null | undefined;
  features: Readonly<Partial<Record<Feature, boolean>>>;
}

/** A weblog permission the user holds: a site admin holds every one; a member their level and below. */
export function weblogLevel(input: Pick<VisibilityInput, 'globalAdmin' | 'membership'>): number {
  if (input.globalAdmin) {
    return RANK.admin;
  }
  const held = input.membership?.toLowerCase();
  return held !== undefined && held in RANK ? RANK[held as WeblogLevel] : 0;
}

function allowed(requires: Requirement, input: VisibilityInput): boolean {
  return 'global' in requires ? input.globalAdmin : weblogLevel(input) >= RANK[requires.weblog];
}

export interface VisibleGroup {
  key: string;
  entries: RailEntry[];
}

/**
 * The tool groups and entries MenuHelper.buildMenu would show for this user: a tab
 * gated by its own requirement, an item by its feature switch and its requirement. A tab
 * stays even with no item left (the JSP keeps it, except on the site space; see report).
 */
export function visibleGroups(menu: Menu, input: VisibilityInput): VisibleGroup[] {
  return RAIL_GROUPS.filter((group) => group.menu === menu && allowed(group.requires, input)).map((group) => ({
    key: group.key,
    entries: RAIL_ENTRIES.filter((entry) =>
      entry.group === group.key
      && (entry.feature === undefined || input.features[entry.feature] === true)
      && allowed(entry.requires, input))
  }));
}
