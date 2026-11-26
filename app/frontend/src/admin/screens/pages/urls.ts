import type { components } from '../../api/schema';

type WeblogView = components['schemas']['WeblogView'];

/** The handle the site's own pages live under (Weblog.SITE_SPACE_HANDLE): its screens say "Site pages". */
export const SITE_SPACE_HANDLE = 'site-space';

export const isSiteSpace = (handle: string): boolean => handle === SITE_SPACE_HANDLE;

/**
 * The weblog's public address with a trailing slash: its own domain, or the site address
 * (the site space is served at the root, a blog under its handle). Undefined until the
 * site address is known.
 */
export function weblogBase(handle: string, weblog: WeblogView | undefined, siteUrl: string | undefined): string | undefined {
  const domain = weblog?.customDomain;
  if (domain !== undefined && domain !== '') {
    return `https://${domain}/`;
  }
  if (siteUrl === undefined) {
    return undefined;
  }
  return isSiteSpace(handle) ? `${siteUrl}/` : `${siteUrl}/${encodeURIComponent(handle)}/`;
}

/** A published page's public address; the page shown at the blog's root is the blog's address itself. */
export function pageUrl(base: string | undefined, slug: string, isHome: boolean): string | undefined {
  if (base === undefined) {
    return undefined;
  }
  return isHome ? base : `${base}${encodeURIComponent(slug)}`;
}
