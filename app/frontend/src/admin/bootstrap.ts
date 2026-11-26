/** What AppShellController puts in the page's one JSON bootstrap block. */
export interface Bootstrap {
  contextPath: string;
  version: string;
  csrf: { headerName: string; token: string };
  user: { userName: string; screenName: string; locale: string; globalPermission: 'admin' | 'editor' };
  uiLocale: string;
  messagesUrl: string;
  /** The brand (name), the tab-title prefix (shortName) and the site space's handle, which the API never serves. */
  site: { name: string; shortName: string; siteSpaceHandle: string };
  /** The footer's facts; a fact the server does not know is absent. */
  colophon: {
    copyrightHolder?: string;
    copyrightYear: string;
    privacyUrl?: string;
    shortRevision?: string;
    releaseDate?: string;
  };
}

export const BOOTSTRAP_ID = 'pressroom-bootstrap';

/** Reads and checks the shell's bootstrap block; throws when it is absent or malformed. */
export function readBootstrap(doc: Document): Bootstrap {
  const element = doc.getElementById(BOOTSTRAP_ID);
  if (element === null) {
    throw new Error(`Missing #${BOOTSTRAP_ID} block`);
  }
  const value: unknown = JSON.parse(element.textContent ?? '');
  if (!isBootstrap(value)) {
    throw new Error(`Malformed #${BOOTSTRAP_ID} block`);
  }
  return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function allStrings(record: Record<string, unknown>, keys: string[]): boolean {
  return keys.every((key) => typeof record[key] === 'string');
}

function isBootstrap(value: unknown): value is Bootstrap {
  if (!isRecord(value) || !allStrings(value, ['contextPath', 'version', 'uiLocale', 'messagesUrl'])) {
    return false;
  }
  const { csrf, user, site, colophon } = value;
  return (
    isRecord(site) && allStrings(site, ['name', 'shortName', 'siteSpaceHandle']) &&
    isRecord(colophon) && typeof colophon['copyrightYear'] === 'string' &&
    ['copyrightHolder', 'privacyUrl', 'shortRevision', 'releaseDate']
      .every((key) => colophon[key] === undefined || typeof colophon[key] === 'string') &&
    isRecord(csrf) && allStrings(csrf, ['headerName', 'token']) &&
    isRecord(user) && allStrings(user, ['userName', 'screenName', 'locale']) &&
    (user['globalPermission'] === 'admin' || user['globalPermission'] === 'editor')
  );
}
