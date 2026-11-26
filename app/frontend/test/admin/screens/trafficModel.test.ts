import {
  DASH, changeClass, changeText, clockText, figure, noticeKey, periodOf, posts, rows, siteTrafficView, sortOf,
  tabOf, trafficView, type BlogTrafficIn
} from '../../../src/admin/screens/traffic/model';

const NOW = new Date('2026-11-08T12:00:00Z');
const summary = { visitors: 40, visits: 50, views: 120, bounces: 10, totalTimeSeconds: 3600 };

describe('request values', () => {
  it('keeps an offered value and falls back to the default for anything else', () => {
    expect(periodOf('7d')).toBe('7d');
    expect(periodOf(null)).toBe('30d');
    expect(periodOf('1y')).toBe('30d');
    expect(tabOf('speed')).toBe('speed');
    expect(tabOf("'; drop")).toBe('posts');
    expect(sortOf('trend')).toBe('trend');
    expect(sortOf('pubTime')).toBe('views');
    expect(sortOf(undefined)).toBe('views');
  });
});

describe('change and notices', () => {
  it('shows a change that is not a number as a dash with no trend', () => {
    for (const bad of [Number.NaN, Number.POSITIVE_INFINITY, null, undefined]) {
      expect(changeText(bad)).toBe(DASH);
      expect(changeClass(bad)).toBe('');
    }
    expect(changeText(0.25)).toBe('+25%');
    expect(changeText(-0.1)).toBe('-10%');
    expect(changeText(0)).toBe('0%');
    expect(changeClass(0.25)).toBe('up');
    expect(changeClass(-0.25)).toBe('dn');
    expect(changeClass(0.001)).toBe('');
  });

  it('gives each state its notice and an all-zero OK the zero text', () => {
    expect(noticeKey('NOT_CONFIGURED', true, 'z')).toBe('traffic.state.notConfigured');
    expect(noticeKey('NO_DATA', false, 'z')).toBe('traffic.state.noData');
    expect(noticeKey('STALE', true, 'z')).toBe('traffic.state.stale');
    expect(noticeKey('OK', true, 'z')).toBe('z');
    expect(noticeKey('OK', false, 'z')).toBe('');
  });

  it('writes the snapshot time in the traffic zone, UTC without one, and nothing without a time', () => {
    expect(clockText('2026-11-08T10:15:00Z', 'Europe/Berlin')).toBe('11:15');
    expect(clockText('2026-11-08T10:15:00Z', undefined)).toBe('10:15');
    expect(clockText('2026-11-08T00:05:00Z', 'UTC')).toBe('00:05');
    expect(clockText(null, 'UTC')).toBeNull();
    expect(clockText(undefined, 'UTC')).toBeNull();
  });

  it('formats counts in the reader locale', () => {
    expect(figure(1234567, 'en')).toBe('1,234,567');
    expect(figure(1234567, 'de')).toBe('1.234.567');
  });
});

describe('trafficView headline tiles', () => {
  const view = (traffic: BlogTrafficIn) => trafficView(traffic, 'posts', 'views', 'en', NOW);

  it('compares each count with the period before and the bounce and time against theirs', () => {
    const tiles = view({ state: 'OK', current: summary, previous: { visitors: 20, visits: 25, views: 60, bounces: 5, totalTimeSeconds: 1800 } }).tiles;
    expect(tiles.map((t) => [t.labelKey, t.value, t.change, t.trend])).toEqual([
      ['traffic.tile.visitors', '40', '+100%', 'up'],
      ['traffic.tile.visits', '50', '+100%', 'up'],
      ['traffic.tile.views', '120', '+100%', 'up'],
      ['traffic.tile.bounce', '20%', '0 pts', ''],
      ['traffic.tile.time', '1:12', '0s', '']
    ]);
  });

  it('marks a falling count down, a rising bounce rate bad and a longer visit good', () => {
    const tiles = view({ state: 'OK', current: summary, previous: { visitors: 80, visits: 50, views: 120, bounces: 5, totalTimeSeconds: 1000 } }).tiles;
    expect(tiles[0]).toMatchObject({ change: '-50%', trend: 'dn' });
    expect(tiles[3]).toMatchObject({ change: '+10 pts', trend: 'dn' });
    expect(tiles[4]).toMatchObject({ change: '+52s', trend: 'up' });
  });

  it('shows dashes with no earlier period or no visits, and pads the seconds', () => {
    expect(view({ state: 'OK', current: summary }).tiles.map((t) => t.change)).toEqual([DASH, DASH, DASH, DASH, DASH]);
    const none = view({ state: 'OK', current: { visitors: 0, visits: 0, views: 0, bounces: 0, totalTimeSeconds: 0 },
      previous: { visitors: 0, visits: 0, views: 0, bounces: 0, totalTimeSeconds: 0 } }).tiles;
    expect(none[3]).toMatchObject({ value: DASH, change: DASH, trend: '' });
    expect(none[4]).toMatchObject({ value: DASH, change: DASH });
    expect(none[0]).toMatchObject({ value: '0', change: DASH });
    const padded = view({ state: 'OK', current: { visitors: 1, visits: 1, views: 1, bounces: 0, totalTimeSeconds: 65 } });
    expect(padded.tiles[4]?.value).toBe('1:05');
  });

  it('treats an earlier period with no visits as having no bounce or time to compare', () => {
    const tiles = view({ state: 'OK', current: summary, previous: { visitors: 1, visits: 0, views: 1, bounces: 0, totalTimeSeconds: 0 } }).tiles;
    expect(tiles[3]).toMatchObject({ change: DASH, trend: '' });
  });
});

describe('trafficView state', () => {
  const view = (traffic: BlogTrafficIn) => trafficView(traffic, 'posts', 'views', 'en', NOW);

  it('has no body without numbers or while nothing has been read', () => {
    expect(view({ state: 'NOT_CONFIGURED' })).toMatchObject({ showBody: false, noticeKey: 'traffic.state.notConfigured', tiles: [] });
    const noData = view({ state: 'NO_DATA', current: summary });
    expect(noData.showBody).toBe(false);
    expect(noData.chart.bars).toEqual([]);
    expect(view({})).toMatchObject({ noticeKey: 'traffic.state.noData' });
  });

  it('says how stale a snapshot is, never less than zero', () => {
    expect(view({ state: 'STALE', asOf: '2026-11-08T10:15:30Z', current: summary }))
      .toMatchObject({ ageMinutes: 104, noticeKey: 'traffic.state.stale', showBody: true });
    expect(view({ state: 'STALE', asOf: '2026-11-08T12:30:00Z', current: summary }).ageMinutes).toBe(0);
    expect(view({ state: 'STALE', current: summary }).ageMinutes).toBe(0);
    expect(view({ state: 'OK', asOf: '2026-11-08T10:15:30Z', current: summary }).ageMinutes).toBe(0);
  });

  it('uses the zero text only when every headline is zero', () => {
    const zero = { visitors: 0, visits: 0, views: 0, bounces: 0, totalTimeSeconds: 0 };
    expect(view({ state: 'OK', current: zero }).noticeKey).toBe('traffic.state.zero');
    expect(view({ state: 'OK', current: summary }).noticeKey).toBe('');
  });

  it('explains a failed registration instead of the no-data notice, carrying the dashboard link', () => {
    const failed = view({ state: 'NO_DATA', registrationError: 'HTTP 401', umamiUrl: 'https://u/websites/w' });
    expect(failed).toMatchObject({ noticeKey: '', notRegisteredError: 'HTTP 401', umamiUrl: 'https://u/websites/w', pathsCappedAt: 0 });
    expect(view({ state: 'OK', current: summary, pathsCappedAt: 1000 }).pathsCappedAt).toBe(1000);
  });
});

describe('the chart', () => {
  const chartOf = (series: BlogTrafficIn['series']) => trafficView({ state: 'OK', current: summary, series }, 'posts', 'views', 'en', NOW).chart;

  it('draws one bar per day, tallest at the plot height, labelled in the reader locale', () => {
    const chart = chartOf([{ day: '2026-11-07', views: 15 }, { day: '2026-11-08', views: 30 }, { day: '2026-11-09', views: 0 }]);
    expect(chart).toMatchObject({ width: 600, height: 140, plotHeight: 110 });
    expect(chart.bars).toEqual([
      { x: 20, y: 55, width: 160, height: 55, title: 'Nov 7: 15', label: 'Nov 7' },
      { x: 220, y: 0, width: 160, height: 110, title: 'Nov 8: 30', label: 'Nov 8' },
      { x: 420, y: 110, width: 160, height: 0, title: 'Nov 9: 0', label: 'Nov 9' }
    ]);
  });

  it('labels hours as two digits and thins the labels to about eight', () => {
    const series = Array.from({ length: 24 }, (_unused, hour) => ({ day: '2026-11-08', hour, views: hour }));
    const bars = chartOf(series).bars;
    expect(bars[0]).toMatchObject({ title: '00:00: 0', label: '00' });
    expect(bars[1]?.label).toBeNull();
    expect(bars[3]).toMatchObject({ title: '03:00: 3', label: '03' });
    expect(bars.filter((b) => b.label !== null)).toHaveLength(8);
  });

  it('draws flat bars when nothing was viewed, and no bars for an empty series', () => {
    expect(chartOf([{ day: '2026-11-07', views: 0 }]).bars[0]).toMatchObject({ height: 0, y: 110 });
    expect(chartOf([]).bars).toEqual([]);
    expect(chartOf(undefined).bars).toEqual([]);
    expect(chartOf([{}]).bars[0]?.title).toBe('Jan 1: 0');
  });

  it('keeps slots at least one wide for a very long series', () => {
    const bars = chartOf(Array.from({ length: 1000 }, () => ({ day: '2026-11-07', views: 1 }))).bars;
    expect(bars[1]).toMatchObject({ x: 1, width: 1 });
  });
});

describe('posts', () => {
  const rowsIn = [
    { entryId: 'e1', title: 'Tom', path: '/b/tom', views: 10, visitors: 5, trend: 0.5 },
    { entryId: null, title: null, path: '/b/tags/x', views: 30, visitors: null, trend: null },
    { entryId: 'e1', title: 'Tom', path: '/b/tom/', views: 40, visitors: 3, trend: -0.2 },
    { entryId: 'e2', title: 'Other', path: '/b/other', views: 40, visitors: 9, trend: 2 }
  ];

  it('merges the same entry under two paths: views add, visitors the larger, the better-viewed path stands', () => {
    const merged = posts(rowsIn, 'views');
    expect(merged.map((p) => [p.path, p.views, p.visitors])).toEqual([
      ['/b/tom/', 50, 5], ['/b/other', 40, 9], ['/b/tags/x', 30, -1]
    ]);
    expect(merged[0]).toMatchObject({ trendText: '-20%', trendClass: 'dn' });
  });

  it('sorts by views (then path), visitors or trend, a missing trend last', () => {
    expect(posts(rowsIn, 'views').map((p) => p.path)).toEqual(['/b/tom/', '/b/other', '/b/tags/x']);
    expect(posts(rowsIn, 'visitors').map((p) => p.path)).toEqual(['/b/other', '/b/tom/', '/b/tags/x']);
    expect(posts(rowsIn, 'trend').map((p) => p.path)).toEqual(['/b/other', '/b/tom/', '/b/tags/x']);
  });

  it('shows the trend as text and class, a dash for none, and carries the entry id and title', () => {
    const [first] = posts([{ entryId: 'e', title: 'T', path: '/p', views: 3, visitors: 2, trend: 0.1 }], 'views');
    expect(first).toEqual({ entryId: 'e', title: 'T', path: '/p', views: 3, visitors: 2, trendText: '+10%', trendClass: 'up' });
    expect(posts([{}], 'views')[0]).toEqual({ entryId: null, title: null, path: '', views: 0, visitors: -1, trendText: DASH, trendClass: '' });
  });

  it('breaks ties on trend and visitors by views, then path', () => {
    const tied = [
      { path: '/b', views: 5, visitors: 1, trend: 1 }, { path: '/a', views: 5, visitors: 1, trend: 1 },
      { path: '/c', views: 9, visitors: 1, trend: 1 }
    ];
    expect(posts(tied, 'trend').map((p) => p.path)).toEqual(['/c', '/a', '/b']);
    expect(posts(tied, 'visitors').map((p) => p.path)).toEqual(['/c', '/a', '/b']);
  });
});

describe('breakdown rows', () => {
  it('splits a group prefix off a key only on a tab that has that group', () => {
    expect(rows('sources', [{ key: 'channel:search', value: 1200 }, { key: 'google.com', value: 3 }, { key: 'x:y', value: 2 }], 'en'))
      .toEqual([
        { group: 'channel', label: 'search', value: '1,200' },
        { group: '', label: 'google.com', value: '3' },
        { group: '', label: 'x:y', value: '2' }
      ]);
    expect(rows('places', [{ key: 'channel:US', value: 1 }], 'en')[0]).toMatchObject({ group: '', label: 'channel:US' });
    expect(rows('places', [{}], 'en')).toEqual([{ group: '', label: '', value: '0' }]);
  });

  it('puts the devices tab in device, browser, os order', () => {
    expect(rows('devices', [{ key: 'os:Linux', value: 1 }, { key: 'device:desktop', value: 2 }, { key: 'browser:Firefox', value: 3 }], 'en')
      .map((r) => r.group)).toEqual(['device', 'browser', 'os']);
  });

  it('writes speed measures as the page did', () => {
    expect(rows('speed', [
      { key: 'cls', value: 123 }, { key: 'lcp', value: 2500 }, { key: 'lcp', value: 900 }, { key: 'cls2', value: 5000 }
    ], 'en').map((r) => r.value)).toEqual(['0.12', '2.5 s', '900 ms', '5000 ms']);
  });
});

describe('siteTrafficView', () => {
  const blogs = [
    { handle: 'a', name: 'Alpha', standing: 'READ', visitors: 40, views: 1200, visits: 50, change: 0.25, topPostTitle: 'Tom' },
    { handle: 'b', name: 'Beta', standing: 'READ', visitors: 5, views: 10, visits: 6, change: null },
    { handle: 'c', name: 'Gamma', standing: 'NO_DATA' },
    { handle: 'site-space', name: 'Site', siteSpace: true, standing: 'NOT_REGISTERED' }
  ];

  it('lists every blog, dashes for those without numbers, totals formatted', () => {
    const view = siteTrafficView({ state: 'OK', asOf: '2026-11-08T10:15:00Z', timeZone: 'Europe/Berlin', totalViews: 1210, totalVisits: 56, blogs,
      posts: [{ entryId: 'e1', title: 'Tom', path: '/a/tom', views: 9, visitors: 3 }], sources: [{ key: 'google.com', value: 12 }] }, 'en', NOW);
    expect(view).toMatchObject({ showBody: true, totalViews: '1,210', totalVisits: '56', asOfText: '11:15', noticeKey: '' });
    expect(view.blogs[0]).toMatchObject({ handle: 'a', views: '1,200', change: '+25%', changeClass: 'up', topPostTitle: 'Tom' });
    expect(view.blogs[1]).toMatchObject({ change: DASH, changeClass: '', topPostTitle: null });
    expect(view.blogs[2]).toMatchObject({ visitors: DASH, views: DASH, visits: DASH, change: '', standing: 'NO_DATA' });
    expect(view.blogs[3]).toMatchObject({ siteSpace: true, standing: 'NOT_REGISTERED' });
    expect(view.posts[0]).toMatchObject({ path: '/a/tom', views: 9 });
    expect(view.sources).toEqual([{ group: '', label: 'google.com', value: '12' }]);
  });

  it('shows no body when no analytics service is configured or there are no blogs', () => {
    expect(siteTrafficView({ state: 'NOT_CONFIGURED', blogs }, 'en', NOW))
      .toMatchObject({ showBody: false, noticeKey: 'traffic.state.notConfigured', blogs: [], posts: [], sources: [] });
    expect(siteTrafficView({ state: 'OK', blogs: [] }, 'en', NOW).showBody).toBe(false);
    expect(siteTrafficView({}, 'en', NOW)).toMatchObject({ showBody: false, noticeKey: 'traffic.state.noData' });
  });

  it('uses the site zero text and the stale age', () => {
    const zero = [{ handle: 'a', name: 'A', standing: 'READ', visitors: 0, views: 0, visits: 0, change: null }];
    expect(siteTrafficView({ state: 'OK', totalViews: 0, totalVisits: 0, blogs: zero }, 'en', NOW).noticeKey).toBe('traffic.state.siteZero');
    expect(siteTrafficView({ state: 'OK', totalViews: 1, totalVisits: 0, blogs: zero }, 'en', NOW).noticeKey).toBe('');
    expect(siteTrafficView({ state: 'STALE', asOf: '2026-11-08T11:00:00Z', blogs: zero }, 'en', NOW))
      .toMatchObject({ ageMinutes: 60, noticeKey: 'traffic.state.stale' });
  });
});
