/**
 * What the Traffic screens show, worked out from the API's numbers: a port of the server-side
 * TrafficView and SiteTrafficView the JSP pages printed, so the figures, the em dash rule, the
 * chart and the post merge read exactly as they did.
 *
 * Bundle keys composed at run time (the screens build them from a period, tab, column, group or
 * speed metric); each is written out whole here so a rename or a deletion finds it and
 * MessageKeyTest does not take it for an orphan:
 *   traffic.column.name.campaigns traffic.column.name.devices traffic.column.name.events
 *   traffic.column.name.places traffic.column.name.sources traffic.column.name.speed
 *   traffic.column.trend traffic.column.value.campaigns traffic.column.value.devices
 *   traffic.column.value.events traffic.column.value.places traffic.column.value.sources
 *   traffic.column.value.speed traffic.column.views traffic.column.visitors
 *   traffic.group.browser traffic.group.channel traffic.group.device traffic.group.os
 *   traffic.group.utm_campaign traffic.group.utm_content traffic.group.utm_medium
 *   traffic.group.utm_source traffic.group.utm_term
 *   traffic.period.24h traffic.period.30d traffic.period.7d traffic.period.90d
 *   traffic.speed.cls traffic.speed.fcp traffic.speed.inp traffic.speed.lcp traffic.speed.ttfb
 *   traffic.tab.campaigns traffic.tab.devices traffic.tab.events traffic.tab.places
 *   traffic.tab.posts traffic.tab.sources traffic.tab.speed
 *   traffic.state.notConfigured traffic.state.noData traffic.state.stale traffic.state.zero
 *   traffic.state.siteZero
 */

/** An em dash: "no figure", never NaN or an infinite change. */
export const DASH = '—';

/** The periods the switch offers. */
export const PERIODS = ['24h', '7d', '30d', '90d'] as const;
/** The tabs, in the order shown. */
export const TABS = ['posts', 'sources', 'places', 'devices', 'events', 'campaigns', 'speed'] as const;
/** The accepted sort columns; the first is the default. */
export const SORTS = ['views', 'visitors', 'trend'] as const;

export type Period = (typeof PERIODS)[number];
export type Tab = (typeof TABS)[number];
export type Sort = (typeof SORTS)[number];

/** The accepted period for a request value, else 30d. */
export function periodOf(requested: string | null | undefined): Period {
  return PERIODS.find((p) => p === requested) ?? '30d';
}

/** The accepted tab for a request value, else posts. */
export function tabOf(requested: string | null | undefined): Tab {
  return TABS.find((t) => t === requested) ?? 'posts';
}

/** The accepted sort for a request value, else views. */
export function sortOf(requested: string | null | undefined): Sort {
  return SORTS.find((s) => s === requested) ?? 'views';
}

// -------------------------------------------------------------------- API shapes (nulls tolerated)

export interface SummaryIn {
  visitors?: number | null; visits?: number | null; views?: number | null;
  bounces?: number | null; totalTimeSeconds?: number | null;
}
export interface PointIn { day?: string | null; hour?: number | null; views?: number | null }
export interface PostIn {
  entryId?: string | null; title?: string | null; path?: string | null;
  views?: number | null; visitors?: number | null; trend?: number | null;
}
export interface RowIn { key?: string | null; value?: number | null }

export interface BlogTrafficIn {
  state?: string | null;
  asOf?: string | null;
  timeZone?: string | null;
  current?: SummaryIn | null;
  previous?: SummaryIn | null;
  series?: readonly PointIn[] | null;
  posts?: readonly PostIn[] | null;
  breakdowns?: Readonly<Record<string, readonly RowIn[]>> | null;
  pathsCappedAt?: number | null;
  umamiUrl?: string | null;
  registrationError?: string | null;
}

export interface SiteBlogIn {
  handle?: string | null; name?: string | null; siteSpace?: boolean | null; standing?: string | null;
  visitors?: number | null; views?: number | null; visits?: number | null;
  change?: number | null; topPostTitle?: string | null;
}

export interface SiteTrafficIn {
  state?: string | null;
  asOf?: string | null;
  timeZone?: string | null;
  totalViews?: number | null;
  totalVisits?: number | null;
  blogs?: readonly SiteBlogIn[] | null;
  posts?: readonly PostIn[] | null;
  sources?: readonly RowIn[] | null;
}

// -------------------------------------------------------------------------------- view shapes

export interface Tile { labelKey: string; value: string; change: string; trend: string }
export interface Bar { x: number; y: number; width: number; height: number; title: string; label: string | null }
export interface Chart { width: number; height: number; plotHeight: number; bars: Bar[] }
export interface PostLine {
  entryId: string | null; title: string | null; path: string; views: number; visitors: number;
  trendText: string; trendClass: string;
}
export interface Row { group: string; label: string; value: string }

export interface TrafficView {
  noticeKey: string;
  ageMinutes: number;
  asOfText: string | null;
  showBody: boolean;
  tiles: Tile[];
  chart: Chart;
  posts: PostLine[];
  rows: Row[];
  umamiUrl: string | null;
  pathsCappedAt: number;
  notRegisteredError: string | null;
}

export interface BlogLine {
  handle: string; name: string; siteSpace: boolean; standing: string;
  visitors: string; views: string; visits: string; change: string; changeClass: string; topPostTitle: string | null;
}

export interface SiteTrafficView {
  noticeKey: string;
  ageMinutes: number;
  asOfText: string | null;
  showBody: boolean;
  totalViews: string;
  totalVisits: string;
  blogs: BlogLine[];
  posts: PostLine[];
  sources: Row[];
}

// ------------------------------------------------------------------------------------ helpers

const CHART_WIDTH = 600;
const CHART_HEIGHT = 140;
const PLOT_HEIGHT = 110;
const MAX_LABELS = 8;
const CLS_SCALE = 1000;
const GROUPS: Readonly<Record<string, readonly string[]>> = {
  sources: ['channel'],
  devices: ['device', 'browser', 'os'],
  campaigns: ['utm_source', 'utm_medium', 'utm_campaign', 'utm_content', 'utm_term']
};
const DEVICE_ORDER = ['device', 'browser', 'os'];
const MILLISECOND_METRICS = ['lcp', 'inp', 'fcp', 'ttfb'];

/** A count in the reader's locale (Figures.number). */
export function figure(value: number, locale: string): string {
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(value);
}

function signed(delta: number): string {
  return delta > 0 ? `+${String(delta)}` : String(delta === 0 ? 0 : delta);
}

function trend(delta: number): string {
  return delta === 0 ? '' : delta > 0 ? 'up' : 'dn';
}

const finite = (value: number | null | undefined): value is number =>
  value !== null && value !== undefined && Number.isFinite(value);

/** A fraction change (0.25 is a quarter more) as "+25%", or an em dash when it is not a number. */
export function changeText(fraction: number | null | undefined): string {
  return finite(fraction) ? `${signed(Math.round(fraction * 100))}%` : DASH;
}

/** "up", "dn" or empty for a fraction change; empty when it is not a number. */
export function changeClass(fraction: number | null | undefined): string {
  return finite(fraction) ? trend(Math.round(fraction * 100)) : '';
}

/** The state message's bundle key, or empty when the numbers speak for themselves. */
export function noticeKey(state: string, allZero: boolean, zeroKey: string): string {
  switch (state) {
    case 'NOT_CONFIGURED': return 'traffic.state.notConfigured';
    case 'NO_DATA': return 'traffic.state.noData';
    case 'STALE': return 'traffic.state.stale';
    default: return allZero ? zeroKey : '';
  }
}

/** HH:mm in the given zone (UTC when none), or null without a time. */
export function clockText(asOf: string | null | undefined, timeZone: string | null | undefined): string | null {
  if (asOf === null || asOf === undefined) {
    return null;
  }
  return new Intl.DateTimeFormat('en-GB', {
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: timeZone ?? 'UTC'
  }).format(new Date(asOf));
}

function staleMinutes(state: string, asOf: string | null | undefined, now: Date): number {
  if (state !== 'STALE' || asOf === null || asOf === undefined) {
    return 0;
  }
  return Math.max(0, Math.trunc((now.getTime() - new Date(asOf).getTime()) / 60_000));
}

const n = (value: number | null | undefined): number => value ?? 0;

// ---------------------------------------------------------------------------------- headlines

function countTile(key: string, now: number, before: number, hasBefore: boolean, locale: string): Tile {
  const value = figure(now, locale);
  if (!hasBefore || before <= 0) {
    return { labelKey: key, value, change: DASH, trend: '' };
  }
  const pct = Math.round(100 * (now - before) / before);
  return { labelKey: key, value, change: `${signed(pct)}%`, trend: trend(pct) };
}

function tiles(now: SummaryIn, before: SummaryIn | null | undefined, locale: string): Tile[] {
  const hasBefore = before !== null && before !== undefined;
  const prior = (pick: (s: SummaryIn) => number | null | undefined) => (hasBefore ? n(pick(before)) : 0);
  const visits = n(now.visits);
  const known = visits > 0;
  const change = known && hasBefore && n(before.visits) > 0;
  const bounce = known ? 100 * n(now.bounces) / visits : 0;
  const bounceDelta = change ? Math.round(bounce - 100 * n(before.bounces) / n(before.visits)) : 0;
  const seconds = known ? n(now.totalTimeSeconds) / visits : 0;
  const timeDelta = change ? Math.round(seconds - n(before.totalTimeSeconds) / n(before.visits)) : 0;
  const total = Math.round(seconds);
  return [
    countTile('traffic.tile.visitors', n(now.visitors), prior((s) => s.visitors), hasBefore, locale),
    countTile('traffic.tile.visits', visits, prior((s) => s.visits), hasBefore, locale),
    countTile('traffic.tile.views', n(now.views), prior((s) => s.views), hasBefore, locale),
    {
      labelKey: 'traffic.tile.bounce', value: known ? `${String(Math.round(bounce))}%` : DASH,
      change: change ? `${signed(bounceDelta)} pts` : DASH, trend: change ? trend(-bounceDelta) : ''
    },
    {
      labelKey: 'traffic.tile.time',
      value: known ? `${String(Math.trunc(total / 60))}:${total % 60 < 10 ? '0' : ''}${String(total % 60)}` : DASH,
      change: change ? `${signed(timeDelta)}s` : DASH, trend: change ? trend(timeDelta) : ''
    }
  ];
}

// ------------------------------------------------------------------------------------- chart

function emptyChart(): Chart {
  return { width: CHART_WIDTH, height: CHART_HEIGHT, plotHeight: PLOT_HEIGHT, bars: [] };
}

function dayLabel(day: string, locale: string): string {
  const [year, month, date] = day.split('-').map(Number);
  return new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric', timeZone: 'UTC' })
    .format(new Date(Date.UTC(year ?? 1970, (month ?? 1) - 1, date ?? 1)));
}

function chart(series: readonly PointIn[], locale: string): Chart {
  const count = series.length;
  if (count === 0) {
    return emptyChart();
  }
  const max = Math.max(...series.map((p) => n(p.views)));
  const slot = Math.max(1, Math.trunc(CHART_WIDTH / count));
  const width = Math.max(1, Math.trunc(slot * 8 / 10));
  const step = Math.trunc((count + MAX_LABELS - 1) / MAX_LABELS);
  const bars = series.map((point, i): Bar => {
    const views = n(point.views);
    const hourly = point.hour !== null && point.hour !== undefined;
    const label = hourly ? String(point.hour).padStart(2, '0') : dayLabel(point.day ?? '1970-01-01', locale);
    const title = hourly ? `${label}:00: ${String(views)}` : `${label}: ${String(views)}`;
    const height = max <= 0 ? 0 : Math.round(PLOT_HEIGHT * views / max);
    return {
      x: i * slot + Math.trunc((slot - width) / 2), y: PLOT_HEIGHT - height, width, height, title,
      label: i % step === 0 ? label : null
    };
  });
  return { width: CHART_WIDTH, height: CHART_HEIGHT, plotHeight: PLOT_HEIGHT, bars };
}

// -------------------------------------------------------------------------------------- posts

interface Merged { best: PostIn; views: number; visitors: number }

const trendKey = (value: number | null | undefined): number => (finite(value) ? value : Number.NEGATIVE_INFINITY);
const visitorsOf = (post: PostIn): number => post.visitors ?? -1;
const pathOf = (post: PostIn): string => post.path ?? '';

/** Rows merged by entry (views summed, visitors the larger) and sorted by `sort`. */
export function posts(rows: readonly PostIn[], sort: Sort): PostLine[] {
  const merged: Merged[] = [];
  const byEntry = new Map<string, number>();
  for (const row of rows) {
    const at = row.entryId === null || row.entryId === undefined ? undefined : byEntry.get(row.entryId);
    if (at === undefined) {
      if (row.entryId !== null && row.entryId !== undefined) {
        byEntry.set(row.entryId, merged.length);
      }
      merged.push({ best: row, views: n(row.views), visitors: visitorsOf(row) });
    } else {
      // The same entry under two spellings of its path: views add up, visitors may overlap so only
      // the larger is a safe figure, and the better-viewed spelling stands for the row.
      const old = merged[at] as Merged;
      merged[at] = {
        best: n(row.views) > n(old.best.views) ? row : old.best,
        views: old.views + n(row.views),
        visitors: Math.max(old.visitors, visitorsOf(row))
      };
    }
  }
  const byViews = (a: Merged, b: Merged) => b.views - a.views;
  const order = (a: Merged, b: Merged): number => {
    if (sort === 'visitors') {
      return b.visitors - a.visitors || byViews(a, b);
    }
    if (sort === 'trend') {
      const [ta, tb] = [trendKey(a.best.trend), trendKey(b.best.trend)];
      return ta === tb ? byViews(a, b) : tb > ta ? 1 : -1;
    }
    return byViews(a, b);
  };
  const byPath = (a: Merged, b: Merged) => {
    const [pa, pb] = [pathOf(a.best), pathOf(b.best)];
    return pa < pb ? -1 : pa > pb ? 1 : 0;
  };
  return merged.sort((a, b) => order(a, b) || byPath(a, b)).map((m) => ({
    entryId: m.best.entryId ?? null, title: m.best.title ?? null, path: pathOf(m.best), views: m.views,
    visitors: m.visitors, trendText: changeText(m.best.trend), trendClass: changeClass(m.best.trend)
  }));
}

// ------------------------------------------------------------------------------- breakdowns

function speed(metric: string, value: number): string {
  if (metric === 'cls') {
    return (value / CLS_SCALE).toFixed(2);
  }
  if (MILLISECOND_METRICS.includes(metric) && value >= 1000) {
    return `${(value / 1000).toFixed(1)} s`;
  }
  return `${String(value)} ms`;
}

/** One tab's breakdown as rows. */
export function rows(tab: string, breakdown: readonly RowIn[], locale: string): Row[] {
  const groups = GROUPS[tab] ?? [];
  const out = breakdown.map((kc): Row => {
    const key = kc.key ?? '';
    const colon = key.indexOf(':');
    const grouped = colon > 0 && groups.includes(key.slice(0, colon));
    const value = n(kc.value);
    return {
      group: grouped ? key.slice(0, colon) : '',
      label: grouped ? key.slice(colon + 1) : key,
      value: tab === 'speed' ? speed(key, value) : figure(value, locale)
    };
  });
  return tab === 'devices' ? out.sort((a, b) => DEVICE_ORDER.indexOf(a.group) - DEVICE_ORDER.indexOf(b.group)) : out;
}

// ------------------------------------------------------------------------------------ the views

/** One blog's traffic, ready to print. */
export function trafficView(
  traffic: BlogTrafficIn, tab: Tab, sort: Sort, locale: string, now: Date
): TrafficView {
  const state = traffic.state ?? 'NO_DATA';
  const current = traffic.current ?? null;
  const showBody = current !== null && state !== 'NO_DATA';
  const allZero = current !== null && n(current.visitors) === 0 && n(current.visits) === 0 && n(current.views) === 0;
  const notRegisteredError = traffic.registrationError ?? null;
  return {
    noticeKey: notRegisteredError === null ? noticeKey(state, allZero, 'traffic.state.zero') : '',
    ageMinutes: staleMinutes(state, traffic.asOf, now),
    asOfText: clockText(traffic.asOf, traffic.timeZone),
    showBody,
    tiles: showBody ? tiles(current, traffic.previous, locale) : [],
    chart: showBody ? chart(traffic.series ?? [], locale) : emptyChart(),
    posts: tab === 'posts' ? posts(traffic.posts ?? [], sort) : [],
    rows: tab === 'posts' ? [] : rows(tab, traffic.breakdowns?.[tab] ?? [], locale),
    umamiUrl: traffic.umamiUrl ?? null,
    pathsCappedAt: n(traffic.pathsCappedAt),
    notRegisteredError
  };
}

function blogLine(b: SiteBlogIn, locale: string): BlogLine {
  const base = { handle: b.handle ?? '', name: b.name ?? '', siteSpace: b.siteSpace === true, standing: b.standing ?? '' };
  if (b.standing !== 'READ') {
    return { ...base, visitors: DASH, views: DASH, visits: DASH, change: '', changeClass: '', topPostTitle: null };
  }
  return {
    ...base, visitors: figure(n(b.visitors), locale), views: figure(n(b.views), locale),
    visits: figure(n(b.visits), locale), change: changeText(b.change), changeClass: changeClass(b.change),
    topPostTitle: b.topPostTitle ?? null
  };
}

/** Every blog's traffic, ready to print. */
export function siteTrafficView(site: SiteTrafficIn, locale: string, now: Date): SiteTrafficView {
  const state = site.state ?? 'NO_DATA';
  const blogs = site.blogs ?? [];
  const showBody = state !== 'NOT_CONFIGURED' && blogs.length > 0;
  const allZero = n(site.totalViews) === 0 && n(site.totalVisits) === 0 && blogs.every((b) => n(b.visitors) === 0);
  return {
    noticeKey: noticeKey(state, allZero, 'traffic.state.siteZero'),
    ageMinutes: staleMinutes(state, site.asOf, now),
    asOfText: clockText(site.asOf, site.timeZone),
    showBody,
    totalViews: figure(n(site.totalViews), locale),
    totalVisits: figure(n(site.totalVisits), locale),
    blogs: showBody ? blogs.map((b) => blogLine(b, locale)) : [],
    posts: showBody ? posts(site.posts ?? [], 'views') : [],
    sources: showBody ? rows('sources', site.sources ?? [], locale) : []
  };
}
