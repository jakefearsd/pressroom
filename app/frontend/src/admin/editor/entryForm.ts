import type { components } from '../api/schema';

export type EntryView = components['schemas']['EntryView'];
export type EntryWrite = components['schemas']['EntryWrite'];

/** Every field of the entry editor's form (EntryBean), as the strings and booleans the controls hold. */
export interface EntryForm {
  title: string;
  text: string;
  summary: string;
  /** The category's name (the API takes the name, not an id). */
  category: string;
  /** Space or comma separated, as the JSP's tags field is. */
  tags: string;
  /** `datetime-local` value in the weblog's own zone; '' for none. */
  pubTime: string;
  pinned: boolean;
  /** A strand page id; '' for none. */
  strand: string;
  pinnedToMain: boolean;
  metaTitle: string;
  searchDescription: string;
  canonicalUrl: string;
  noindex: boolean;
  commentsClosed: boolean;
  jsonLdType: string;
  geoLatitude: string;
  geoLongitude: string;
  eventStart: string;
  eventEnd: string;
  eventLocation: string;
  featuredImageId: string;
  ogImageId: string;
  locale: string;
}

export const ONE_MINUTE_MS = 60_000;

export function emptyForm(locale: string, category: string, text = ''): EntryForm {
  return {
    title: '', text, summary: '', category, tags: '', pubTime: '', pinned: false, strand: '', pinnedToMain: false,
    metaTitle: '', searchDescription: '', canonicalUrl: '', noindex: false, commentsClosed: false,
    jsonLdType: 'BLOG_POSTING', geoLatitude: '', geoLongitude: '', eventStart: '', eventEnd: '', eventLocation: '',
    featuredImageId: '', ogImageId: '', locale
  };
}

function parts(ms: number, timeZone: string | undefined): Record<string, number> {
  const formatted = new Intl.DateTimeFormat('en-US', {
    ...(timeZone === undefined ? { timeZone: 'UTC' } : { timeZone }),
    year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', hourCycle: 'h23'
  }).formatToParts(new Date(ms));
  return Object.fromEntries(formatted.map((p) => [p.type, Number(p.value)]));
}

const pad = (n: number | undefined, width = 2) => String(n ?? 0).padStart(width, '0');

/** An instant as the weblog's clock reads it: `yyyy-MM-ddTHH:mm`, what a datetime-local control holds. */
export function instantToLocal(instant: string, timeZone: string | undefined): string {
  const ms = Date.parse(instant);
  if (Number.isNaN(ms)) {
    return '';
  }
  const p = parts(ms, timeZone);
  return `${pad(p['year'], 4)}-${pad(p['month'])}-${pad(p['day'])}T${pad(p['hour'])}:${pad(p['minute'])}`;
}

const LOCAL = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/;

/** The instant a wall-clock time means in the weblog's zone (null when it is blank or not a time). */
export function localToInstant(local: string, timeZone: string | undefined): number | null {
  const m = LOCAL.exec(local);
  if (m === null) {
    return null;
  }
  const asUtc = Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]), Number(m[4]), Number(m[5]));
  const offsetAt = (ms: number) => {
    const p = parts(ms, timeZone);
    return Date.UTC(p['year'] ?? 0, (p['month'] ?? 1) - 1, p['day'] ?? 1, p['hour'] ?? 0, p['minute'] ?? 0) - ms;
  };
  // Two passes settle a time near a daylight-saving change.
  const first = asUtc - offsetAt(asUtc);
  return asUtc - offsetAt(first);
}

/** SaveIntent's rule: a time more than a minute ahead is a schedule, anything else is "now". */
export function publishedSoon(pubTime: string, timeZone: string | undefined, now: number): boolean {
  const at = localToInstant(pubTime, timeZone);
  return at !== null && at > now + ONE_MINUTE_MS;
}

export function splitTags(value: string): string[] {
  return value.split(/[\s,]+/).filter((tag) => tag !== '');
}

export function formFromEntry(entry: EntryView, timeZone: string | undefined): EntryForm {
  const when = (instant: string | undefined) => (instant === undefined ? '' : instantToLocal(instant, timeZone));
  const number = (value: number | undefined) => (value === undefined ? '' : String(value));
  return {
    title: entry.title ?? '',
    text: entry.text ?? '',
    summary: entry.summary ?? '',
    category: entry.category ?? '',
    tags: (entry.tags ?? []).join(' '),
    pubTime: when(entry.pubTime),
    pinned: entry.pinned ?? false,
    strand: entry.strand ?? '',
    pinnedToMain: entry.pinnedToMain ?? false,
    metaTitle: entry.metaTitle ?? '',
    searchDescription: entry.searchDescription ?? '',
    canonicalUrl: entry.canonicalUrl ?? '',
    noindex: entry.noindex ?? false,
    commentsClosed: entry.commentsClosed ?? false,
    jsonLdType: entry.jsonLdType === undefined || entry.jsonLdType === '' ? 'BLOG_POSTING' : entry.jsonLdType,
    geoLatitude: number(entry.geoLatitude),
    geoLongitude: number(entry.geoLongitude),
    eventStart: when(entry.eventStart),
    eventEnd: when(entry.eventEnd),
    eventLocation: entry.eventLocation ?? '',
    featuredImageId: entry.featuredImageId ?? '',
    ogImageId: entry.ogImageId ?? '',
    locale: entry.locale ?? ''
  };
}

export interface WriteContext {
  isNew: boolean;
  /** 'draft' is the Save draft / Unpublish button; 'publish' is Publish, Update and Submit for review. */
  intent: 'draft' | 'publish';
  /** Publish rights on the weblog (the JSP's userAnAuthor). */
  canPublish: boolean;
  /** A site administrator (the JSP's isGlobalAdmin). */
  siteAdmin: boolean;
  tz: string | undefined;
  now: number;
  /** The entry as last loaded or saved; absent for a new one. */
  original?: EntryView;
}

/**
 * The body of the create or update call for the form, the work EntryBean.copyTo and
 * EntryEditController.applyForm did. On update a field the author emptied goes in `clear`
 * (the API's null never removes anything); publisher-only fields ride only for publishers
 * and `pinnedToMain` only for a site admin, because the API refuses a write that would
 * change them and the JSP kept the stored value when the control was absent.
 */
export function buildWrite(form: EntryForm, ctx: WriteContext): EntryWrite {
  const original = ctx.isNew ? undefined : ctx.original;
  const clear: string[] = [];
  const body: EntryWrite = {
    title: form.title,
    text: form.text,
    tags: splitTags(form.tags),
    noindex: form.noindex,
    commentsClosed: form.commentsClosed,
    jsonLdType: form.jsonLdType,
    // The JSP never imported images; the API's default (import, best effort) would be new behaviour.
    importExternalImages: false
  };
  body.status = ctx.intent === 'draft' ? 'DRAFT'
    : publishedSoon(form.pubTime, ctx.tz, ctx.now) ? 'SCHEDULED' : 'PUBLISHED';
  if (form.pubTime !== '') {
    body.pubTime = form.pubTime;
  } else if (original?.pubTime !== undefined) {
    // An emptied box: a draft goes back to having none, a publish means now (the API's clear.pubTime).
    clear.push('pubTime');
  }
  if (form.category !== '') {
    body.category = form.category;
  }
  if (form.locale !== '') {
    body.locale = form.locale;
  }

  const text = (key: 'summary' | 'metaTitle' | 'searchDescription' | 'canonicalUrl' | 'eventLocation' |
  'eventStart' | 'eventEnd' | 'featuredImageId' | 'ogImageId') => {
    const value = form[key];
    if (value.trim() !== '') {
      body[key] = value;
    } else if (original !== undefined && (original[key] ?? '') !== '') {
      clear.push(key);
    }
  };
  (['summary', 'metaTitle', 'searchDescription', 'canonicalUrl', 'eventLocation', 'eventStart', 'eventEnd',
    'featuredImageId', 'ogImageId'] as const).forEach(text);

  const lat = form.geoLatitude.trim();
  const lng = form.geoLongitude.trim();
  if (lat !== '') {
    body.geoLatitude = Number(lat);
  }
  if (lng !== '') {
    body.geoLongitude = Number(lng);
  }
  if (lat === '' && lng === '' && original !== undefined
    && (original.geoLatitude !== undefined || original.geoLongitude !== undefined)) {
    // The two clear together: a place is both numbers or neither.
    clear.push('geoLatitude', 'geoLongitude');
  }

  if (ctx.canPublish) {
    body.pinned = form.pinned;
    const had = original?.strand ?? '';
    if (form.strand !== had) {
      if (form.strand === '') {
        clear.push('strand');
      } else {
        body.strand = form.strand;
      }
    }
  }
  if (ctx.siteAdmin) {
    body.pinnedToMain = form.pinnedToMain;
  }
  if (original !== undefined) {
    if (original.contentHash !== undefined) {
      body.expectedContentHash = original.contentHash;
    }
    if (clear.length > 0) {
      body.clear = clear;
    }
  }
  return body;
}
