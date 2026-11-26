import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';

export const API = 'http://pressroom.test/pressroom/api/v1';

export interface Seen {
  method: string;
  path: string;
  body: unknown;
  csrf: string | null;
}

/** The weblog the settings, theme and remove screens load, as GET /v1/weblogs/alpha answers. */
export const alpha = {
  id: 'w1', handle: 'alpha', name: 'Alpha Blog', tagline: 'Hello', copyright: '(c) me', emailAddress: 'a@example.com',
  locale: 'en_US', timeZone: 'Europe/Berlin', entryDisplayCount: 10, active: true, commentsEnabled: true,
  commentNotify: false, showMoreFromSite: true, shareRowEnabled: false, entryOrder: 'OLDEST', pageWidth: 'STANDARD',
  frontPage: 'p1', theme: 'journal', customDomain: 'blog.alpha.example', icon: '/i.png', about: 'About text',
  newsletterListUuid: '', businessId: 'b1', placeType: 'LodgingBusiness', placeLocality: 'Lisbon', placeRegion: 'LX',
  placeCountry: 'PT', placeLat: 38.72, placeLng: -9.14, bookingUrl: 'https://book.example/'
};

export const themeChoices = [
  { id: 'journal', name: 'Journal', description: 'Quiet and neutral.', previewPath: '/themes/journal/preview.png' },
  { id: 'gaia', name: 'Gaia', description: 'Leafy.' }
];

export const me = (permission = 'ADMIN', globalAdmin = false) => ({
  userName: 'jake', screenName: 'Jake', kind: 'PERSON', globalAdmin, tokenScope: {},
  weblogs: [{ handle: 'alpha', name: 'Alpha Blog', permission }]
});

export const meta = (features: Record<string, boolean> = {}) => ({
  version: '1.2.3', siteUrl: 'https://blog.example.com', limits: {},
  features: { customThemes: true, groupBlogging: true, userWeblogCreation: true, ...features }
});

export const options = {
  themes: themeChoices, defaultTheme: 'journal',
  locales: [{ id: 'en_US', name: 'English (United States)' }, { id: 'de_DE', name: 'German (Germany)' }],
  timeZones: ['Europe/Berlin', 'UTC']
};

export const problem = (code: string, detail: string, errors?: { field: string; message: string }[], status = 400) =>
  HttpResponse.json({ code, detail, ...(errors ? { errors } : {}) }, { status });

/** An MSW server over `handlers`, recording every unsafe request it serves. */
export function recordingServer(handlers: (seen: Seen[]) => ReturnType<typeof http.get>[]) {
  const seen: Seen[] = [];
  const server = setupServer(...handlers(seen));
  return { server, seen };
}

export async function record(seen: Seen[], request: Request): Promise<void> {
  const text = await request.text();
  seen.push({ method: request.method, path: new URL(request.url).pathname.replace('/pressroom/api/v1', ''),
    body: text === '' ? null : (JSON.parse(text) as unknown), csrf: request.headers.get('X-CSRF-TOKEN') });
}
