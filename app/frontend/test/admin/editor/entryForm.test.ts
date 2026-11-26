import {
  buildWrite, emptyForm, formFromEntry, instantToLocal, localToInstant, publishedSoon, splitTags, type WriteContext
} from '../../../src/admin/editor/entryForm';

const TZ = 'Europe/Berlin';
const base: WriteContext = { isNew: false, intent: 'publish', canPublish: true, siteAdmin: false, tz: TZ, now: Date.parse('2026-03-01T10:00:00Z') };

describe('time zones', () => {
  it('shows an instant as the weblog clock reads it, in datetime-local form', () => {
    expect(instantToLocal('2026-03-01T08:30:00Z', TZ)).toBe('2026-03-01T09:30');
    expect(instantToLocal('2026-07-01T08:30:00Z', TZ)).toBe('2026-07-01T10:30');
    expect(instantToLocal('2026-03-01T08:30:00Z', undefined)).toBe('2026-03-01T08:30');
    expect(instantToLocal('nonsense', TZ)).toBe('');
  });

  it('reads a wall-clock time in the weblog zone back to an instant, across a DST change', () => {
    expect(localToInstant('2026-03-01T09:30', TZ)).toBe(Date.parse('2026-03-01T08:30:00Z'));
    expect(localToInstant('2026-07-01T10:30', TZ)).toBe(Date.parse('2026-07-01T08:30:00Z'));
    expect(localToInstant('2026-03-29T03:30', TZ)).toBe(Date.parse('2026-03-29T01:30:00Z'));
    expect(localToInstant('', TZ)).toBeNull();
    expect(localToInstant('garbage', TZ)).toBeNull();
  });

  it('calls a time more than a minute ahead scheduled, as SaveIntent does', () => {
    const now = Date.parse('2026-03-01T10:00:00Z');
    expect(publishedSoon('2026-03-01T11:00', TZ, now)).toBe(false);
    expect(publishedSoon('2026-03-01T11:02', TZ, now)).toBe(true);
    expect(publishedSoon('', TZ, now)).toBe(false);
  });
});

describe('tags', () => {
  it('splits on spaces and commas and drops blanks', () => {
    expect(splitTags(' travel,  food   rome ,')).toEqual(['travel', 'food', 'rome']);
    expect(splitTags('')).toEqual([]);
  });
});

describe('formFromEntry', () => {
  it('maps a view onto the form, in the weblog zone, with blanks for absent fields', () => {
    const form = formFromEntry({
      id: 'e1', title: 'T', text: 'body', tags: ['a', 'b'], category: 'Trips', pubTime: '2026-03-01T08:30:00Z',
      geoLatitude: 48.5, eventStart: '2026-08-02T17:30:00Z', pinned: true, strand: 's1', locale: 'de_DE', noindex: true
    }, TZ);
    expect(form).toMatchObject({
      title: 'T', text: 'body', tags: 'a b', category: 'Trips', pubTime: '2026-03-01T09:30', geoLatitude: '48.5',
      geoLongitude: '', eventStart: '2026-08-02T19:30', pinned: true, strand: 's1', locale: 'de_DE', noindex: true,
      summary: '', jsonLdType: 'BLOG_POSTING', commentsClosed: false, pinnedToMain: false
    });
  });

  it('starts a new entry empty, in the weblog locale', () => {
    expect(emptyForm('en_US', 'Trips', 'preset')).toMatchObject({ title: '', text: 'preset', locale: 'en_US', category: 'Trips' });
  });
});

describe('buildWrite', () => {
  const form = { ...emptyForm('en_US', 'Trips', 'hello'), title: 'Tom & Jerry', tags: 'a, b' };

  it('creates a published entry: no clear, no content hash, external images left linked', () => {
    const body = buildWrite(form, { ...base, isNew: true });
    expect(body).toMatchObject({
      title: 'Tom & Jerry', text: 'hello', status: 'PUBLISHED', category: 'Trips', tags: ['a', 'b'], locale: 'en_US',
      noindex: false, commentsClosed: false, jsonLdType: 'BLOG_POSTING', importExternalImages: false, pinned: false
    });
    expect(body.clear).toBeUndefined();
    expect(body.expectedContentHash).toBeUndefined();
    expect(body.pubTime).toBeUndefined();
  });

  it('saves a draft, and schedules a future time', () => {
    expect(buildWrite(form, { ...base, intent: 'draft' }).status).toBe('DRAFT');
    expect(buildWrite({ ...form, pubTime: '2026-03-02T09:00' }, base)).toMatchObject({ status: 'SCHEDULED', pubTime: '2026-03-02T09:00' });
    expect(buildWrite({ ...form, pubTime: '2026-03-01T09:00' }, base).status).toBe('PUBLISHED');
  });

  it('on update sends the content hash and clears a field the author emptied, not one that was always empty', () => {
    const original = { id: 'e1', contentHash: 'abc', summary: 'old', metaTitle: 'mt', geoLatitude: 1, geoLongitude: 2,
      eventStart: '2026-08-02T17:30:00Z', featuredImageId: 'm1', strand: 's1' };
    const body = buildWrite({ ...form, summary: '', metaTitle: '', geoLatitude: '', geoLongitude: '', eventStart: '',
      featuredImageId: '', strand: '' }, { ...base, original });
    expect(body.expectedContentHash).toBe('abc');
    expect([...(body.clear ?? [])].sort()).toEqual(
      ['eventStart', 'featuredImageId', 'geoLatitude', 'geoLongitude', 'metaTitle', 'strand', 'summary']);
    expect(body.summary).toBeUndefined();
    expect(body.strand).toBeUndefined();
  });

  it('joins a strand only when it changed (re-sending the same one would move the post to the end)', () => {
    const original = { id: 'e1', contentHash: 'h', strand: 's1' };
    expect(buildWrite({ ...form, strand: 's1' }, { ...base, original }).strand).toBeUndefined();
    expect(buildWrite({ ...form, strand: 's2' }, { ...base, original }).strand).toBe('s2');
  });

  it('sends the publisher-only fields to publishers only, and pinnedToMain to a site admin only', () => {
    const rich = { ...form, pinned: true, strand: 's9', pinnedToMain: true };
    const contributor = buildWrite(rich, { ...base, canPublish: false });
    expect(contributor).not.toHaveProperty('pinned');
    expect(contributor).not.toHaveProperty('strand');
    expect(contributor).not.toHaveProperty('pinnedToMain');
    const author = buildWrite(rich, base);
    expect(author).toMatchObject({ pinned: true, strand: 's9' });
    expect(author).not.toHaveProperty('pinnedToMain');
    expect(buildWrite(rich, { ...base, siteAdmin: true })).toMatchObject({ pinnedToMain: true });
  });

  it('sends coordinates as numbers and leaves out a category it has none of', () => {
    const body = buildWrite({ ...form, category: '', geoLatitude: '48.5', geoLongitude: '2.25' }, base);
    expect(body).toMatchObject({ geoLatitude: 48.5, geoLongitude: 2.25 });
    expect(body).not.toHaveProperty('category');
  });
});
