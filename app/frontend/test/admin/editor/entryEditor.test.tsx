import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { APP_DRAFT_PREFIX } from '../../../src/admin/session/drafts';
import { editorBoot } from '../support';
import { adminBoot, API, cm, lastRequest, open, problem, resetState, server, state, titleField, typeInEditor } from './harness';

beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => { resetState(); window.localStorage.clear(); });

const click = (name: string | RegExp) => userEvent.click(screen.getByRole('button', { name }));
const bodyOf = (method: string) => lastRequest(method)?.body as Record<string, unknown>;
const loaded = () => screen.findByDisplayValue('Rome trip');

describe('the entry editor: loading', () => {
  it('shows an entry in the writing surface and publish rail, in the weblog zone', async () => {
    open('/weblogs/alpha/entries/e1');
    await loaded();
    expect(screen.getByRole('heading', { level: 1, name: 'Edit entry' })).toHaveClass('pressroom-page-title');
    expect(document.querySelector('.subtitle')).toHaveTextContent('Editing an entry in alpha');
    expect(document.querySelector('.subtitle span')).toHaveTextContent('alpha');
    expect(document.title).toBe('Pressroom: Rome trip — Edit entry — alpha');
    expect(cm().state.doc.toString()).toBe('Hello **Rome**');
    expect(document.querySelector('.editor-grid > form#entry.editor-form .editor-main')).not.toBeNull();
    expect(document.querySelector('.editor-rail .editor-box .status-pill.status-draft')).toHaveTextContent('Draft');
    expect(screen.getByLabelText('Publish time')).toHaveValue('2026-03-01T09:30');
    expect(document.querySelector('.editor-tz')).toHaveTextContent('Europe/Berlin');
    expect(screen.getByLabelText('Category')).toHaveValue('Trips');
    expect(screen.getByLabelText('Tags')).toHaveValue('travel food');
    expect(screen.getByRole('button', { name: 'Publish' })).toHaveClass('btn-primary');
    expect(screen.getByRole('button', { name: 'Save draft' })).toHaveClass('btn-secondary');
    expect(screen.getByRole('button', { name: 'Full preview' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Delete entry/ })).toHaveClass('delete-link');
  });

  it('offers the permalink as a link and a copy control only once published, as the JSP does', async () => {
    state.entry['status'] = 'PUBLISHED';
    const clipboard = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: clipboard }, configurable: true });
    open('/weblogs/alpha/entries/e1');
    await loaded();
    expect(screen.getByRole('link', { name: state.entry['permalink'] as string })).toHaveAttribute('href', state.entry['permalink']);
    await userEvent.click(screen.getByRole('button', { name: 'Copy' }));
    expect(clipboard).toHaveBeenCalledWith(state.entry['permalink']);
    expect(await screen.findByRole('button', { name: 'Copied' })).toHaveClass('copied');
    // On a published entry the pair reads Update / Unpublish.
    expect(screen.getByRole('button', { name: 'Update' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Unpublish' })).toBeInTheDocument();
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
  });

  it('selects the permalink text when there is no clipboard (a plain-http origin)', async () => {
    state.entry['status'] = 'PUBLISHED';
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await userEvent.click(screen.getByRole('button', { name: 'Copy' }));
    expect(window.getSelection()?.toString()).toBe(state.entry['permalink']);
  });

  it('opens the summary drawer when a summary was written, and keeps the drawers closed otherwise', async () => {
    state.entry['summary'] = 'A short one.';
    open('/weblogs/alpha/entries/e1');
    await loaded();
    expect(screen.getByRole('button', { name: 'Summary (optional)' })).toHaveAttribute('aria-expanded', 'true');
    expect(document.getElementById('collapseSummaryEditor')).toHaveClass('show');
    expect(screen.getByLabelText('Summary (optional)')).toHaveValue('A short one.');
    expect(document.getElementById('collapseSeo')).not.toHaveClass('show');
    await userEvent.click(screen.getByRole('button', { name: 'SEO and sharing' }));
    expect(document.getElementById('collapseSeo')).toHaveClass('show');
  });

  it('sends a missing entry to the app home and a trashed one to the Trash', async () => {
    server.use(http.get(`${API}/weblogs/:handle/entries/:id`, () => problem(404, 'entry.notFound', 'No such entry.')));
    const { router } = open('/weblogs/alpha/entries/gone');
    await waitFor(() => { expect(router.state.location.pathname).toBe('/'); });
  });

  it('takes a new entry from the weblog locale and the first category, empty and not saved', async () => {
    open('/weblogs/alpha/entries/new');
    await screen.findByLabelText('Category');
    expect(screen.getByRole('heading', { level: 1, name: 'New entry' })).toBeInTheDocument();
    expect(document.querySelector('.subtitle')).toHaveTextContent('Create a new entry in weblog alpha');
    expect(titleField()).toHaveValue('');
    expect(screen.getByLabelText('Category')).toHaveValue('Trips');
    expect(document.querySelector('.status-pill.status-unsaved')).toHaveTextContent('Not saved');
    expect(document.getElementById('editorStatusSave')).toHaveTextContent('Not saved');
    expect(screen.queryByRole('button', { name: 'Full preview' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Delete entry/ })).toBeNull();
    expect(document.querySelector('.editor-permalink')).toBeNull();
    expect(document.getElementById('entryRevisionsCard')).toBeNull();
  });

  it('preselects the last-used category for a new entry (a per-install convenience)', async () => {
    window.localStorage.setItem('pressroom.appLastCategory.v1:http://pressroom.test/pressroom:alpha', 'Food');
    open('/weblogs/alpha/entries/new');
    await screen.findByLabelText('Category');
    expect(screen.getByLabelText('Category')).toHaveValue('Food');
  });
});

describe('the entry editor: saving', () => {
  it('saves a draft: the whole form as one update guarded by the content hash', async () => {
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await userEvent.clear(titleField());
    await userEvent.type(titleField(), 'Rome, again');
    await typeInEditor('\nMore.');
    await click('Save draft');
    expect(await screen.findByText('Draft saved')).toBeInTheDocument();
    expect(lastRequest('PATCH')?.path).toBe('/weblogs/alpha/entries/e1');
    expect(bodyOf('PATCH')).toMatchObject({
      title: 'Rome, again', text: 'Hello **Rome**\nMore.', status: 'DRAFT', expectedContentHash: 'h1', category: 'Trips',
      tags: ['travel', 'food'], pubTime: '2026-03-01T09:30', noindex: false, commentsClosed: false,
      jsonLdType: 'BLOG_POSTING', locale: 'en_US', importExternalImages: false
    });
    expect(document.getElementById('messages')).toHaveClass('alert-success');
    // The next save is guarded by the hash the last one returned.
    await click('Save draft');
    await waitFor(() => { expect(bodyOf('PATCH')['expectedContentHash']).toBe('h2'); });
  });

  it('publishes, and offers a link to the live entry', async () => {
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await click('Publish');
    const link = await screen.findByRole('link', { name: 'View it' });
    expect(link).toHaveAttribute('href', state.entry['permalink']);
    expect(document.getElementById('messages')).toHaveTextContent('Entry published. View it');
    expect(bodyOf('PATCH')['status']).toBe('PUBLISHED');
    expect(document.querySelector('.status-pill')).toHaveTextContent('Published');
  });

  it('schedules a time in the future and says when', async () => {
    open('/weblogs/alpha/entries/e1');
    await loaded();
    server.use(http.patch(`${API}/weblogs/:handle/entries/:id`, async ({ request }) => {
      const body = await request.json() as Record<string, unknown>;
      state.requests.push({ method: 'PATCH', path: '/weblogs/alpha/entries/e1', body, search: '' });
      return HttpResponse.json({ ...state.entry, ...body, pubTime: '2099-01-02T07:00:00Z' });
    }));
    fireEvent.change(screen.getByLabelText('Publish time'), { target: { value: '2099-01-02T08:00' } });
    await click('Publish');
    expect(await screen.findByText('Entry scheduled for 2099-01-02 08:00.')).toBeInTheDocument();
    expect(bodyOf('PATCH')).toMatchObject({ status: 'SCHEDULED', pubTime: '2099-01-02T08:00' });
  });

  it('unpublishes a live entry with the draft button, and Ctrl+S updates it instead', async () => {
    state.entry['status'] = 'PUBLISHED';
    open('/weblogs/alpha/entries/e1');
    await loaded();
    titleField().focus();
    await userEvent.keyboard('{Control>}s{/Control}');
    await screen.findByText(/Entry published\./);
    expect(bodyOf('PATCH')['status']).toBe('PUBLISHED');
    await click('Unpublish');
    await screen.findByText('Draft saved');
    expect(bodyOf('PATCH')['status']).toBe('DRAFT');
  });

  it('saves a draft on Ctrl+S from the title and publishes on Ctrl+Enter, once each', async () => {
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await userEvent.click(titleField());
    await userEvent.keyboard('{Control>}s{/Control}');
    await waitFor(() => { expect(state.requests.filter((r) => r.method === 'PATCH')).toHaveLength(1); });
    expect(bodyOf('PATCH')['status']).toBe('DRAFT');
    await userEvent.keyboard('{Control>}{Enter}{/Control}');
    await waitFor(() => { expect(state.requests.filter((r) => r.method === 'PATCH')).toHaveLength(2); });
    expect(bodyOf('PATCH')['status']).toBe('PUBLISHED');
  });

  it('answers Ctrl+S inside the editor with a save, not a second one from the page', async () => {
    open('/weblogs/alpha/entries/e1');
    await loaded();
    const content = document.querySelector('.cm-content') as HTMLElement;
    await userEvent.click(content);
    await userEvent.keyboard('{Control>}s{/Control}');
    await screen.findByText('Draft saved');
    expect(state.requests.filter((r) => r.method === 'PATCH')).toHaveLength(1);
  });

  it('creates a new entry, then lands on its editor with the success message', async () => {
    state.entry = { ...state.entry, id: 'new1', status: 'PUBLISHED' };
    const { router } = open('/weblogs/alpha/entries/new');
    await screen.findByLabelText('Category');
    await userEvent.type(titleField(), 'Tom & Jerry');
    await typeInEditor('Body');
    await userEvent.type(screen.getByLabelText('Tags'), 'a, b');
    await click('Publish');
    await waitFor(() => { expect(router.state.location.pathname).toBe('/weblogs/alpha/entries/new1'); });
    expect(lastRequest('POST')?.path).toBe('/weblogs/alpha/entries');
    expect(bodyOf('POST')).toMatchObject({ title: 'Tom & Jerry', text: 'Body', status: 'PUBLISHED', category: 'Trips', tags: ['a', 'b'], locale: 'en_US' });
    expect(bodyOf('POST')).not.toHaveProperty('clear');
    expect(bodyOf('POST')).not.toHaveProperty('expectedContentHash');
    expect(await screen.findByText(/Entry published\./)).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Edit entry' })).toBeInTheDocument();
    expect(window.localStorage.getItem('pressroom.appLastCategory.v1:http://pressroom.test/pressroom:alpha')).toBe('Trips');
  });

  it('empties a field by naming it in clear, and only if it had a value', async () => {
    state.entry['summary'] = 'Old summary';
    state.entry['metaTitle'] = 'Old meta';
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await userEvent.clear(screen.getByLabelText('Summary (optional)'));
    await userEvent.click(screen.getByRole('button', { name: 'SEO and sharing' }));
    await userEvent.clear(screen.getByLabelText('Meta title'));
    await click('Save draft');
    await screen.findByText('Draft saved');
    expect([...(bodyOf('PATCH')['clear'] as string[])].sort()).toEqual(['metaTitle', 'summary']);
    expect(bodyOf('PATCH')).not.toHaveProperty('summary');
  });
});

describe('the entry editor: roles', () => {
  it('gives a contributor Submit for review and none of the publisher fields', async () => {
    (state.me as { weblogs: object[] }).weblogs = [{ handle: 'alpha', name: 'Alpha Blog', permission: 'EDIT_DRAFT' }];
    state.entry['pinned'] = true;
    open('/weblogs/alpha/entries/e1');
    await loaded();
    expect(screen.getByRole('button', { name: 'Submit for review' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Publish' })).toBeNull();
    expect(screen.queryByLabelText(/Pin/)).toBeNull();
    expect(screen.queryByLabelText('Strand')).toBeNull();
    expect(screen.queryByLabelText('Pinned to main')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Send as newsletter' })).toBeNull();
    await click('Submit for review');
    await waitFor(() => { expect(lastRequest('PATCH')).toBeDefined(); });
    expect(bodyOf('PATCH')).not.toHaveProperty('pinned');
    expect(bodyOf('PATCH')).not.toHaveProperty('strand');
    expect(bodyOf('PATCH')).not.toHaveProperty('pinnedToMain');
  });

  it('shows an author the pin and strand controls (with a trashed current strand named) and pinned-to-main to nobody', async () => {
    state.entry['strand'] = 's9';
    state.trashedPages = [{ id: 's9', title: 'Old series' }];
    open('/weblogs/alpha/entries/e1');
    await loaded();
    const strand = await screen.findByLabelText('Strand');
    await waitFor(() => { expect(within(strand).getByRole('option', { name: 'Old series (in the trash)' })).toBeInTheDocument(); });
    expect(within(strand).getByRole('option', { name: 'Italy' })).toBeInTheDocument();
    expect(strand).toHaveValue('s9');
    expect(screen.queryByLabelText('Pinned to main')).toBeNull();
    await userEvent.selectOptions(strand, 's1');
    await userEvent.click(screen.getByRole('checkbox', { name: /Pin to the top/ }));
    await click('Save draft');
    await screen.findByText('Draft saved');
    expect(bodyOf('PATCH')).toMatchObject({ strand: 's1', pinned: true });
    expect(bodyOf('PATCH')).not.toHaveProperty('pinnedToMain');
  });

  it('leaves a strand with clear, and leaves one untouched without re-sending it', async () => {
    state.entry['strand'] = 's1';
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await click('Save draft');
    await screen.findByText('Draft saved');
    expect(bodyOf('PATCH')).not.toHaveProperty('strand');
    await userEvent.selectOptions(await screen.findByLabelText('Strand'), '');
    await click('Save draft');
    await waitFor(() => { expect(bodyOf('PATCH')['clear']).toEqual(['strand']); });
  });

  it('shows pinned-to-main to a site admin only, and sends it', async () => {
    (state.me as { globalAdmin: boolean }).globalAdmin = true;
    open('/weblogs/alpha/entries/e1', adminBoot);
    await loaded();
    await userEvent.click(await screen.findByRole('checkbox', { name: 'Pinned to main' }));
    await click('Save draft');
    await screen.findByText('Draft saved');
    expect(bodyOf('PATCH')['pinnedToMain']).toBe(true);
  });
});

describe('the entry editor: refusals', () => {
  it('shows a refused field on the field, opens the drawer that holds it and focuses it', async () => {
    server.use(http.patch(`${API}/weblogs/:handle/entries/:id`, () => problem(400, 'request.invalidFields', 'Some fields are wrong.', [
      { field: 'canonicalUrl', message: 'Canonical URL must be a full http:// or https:// address.', code: 'x' },
      { field: 'pubTime', message: 'Publish date/time is invalid.' }
    ])));
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await click('Save draft');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Canonical URL must be a full http:// or https:// address.');
    expect(alert).toHaveTextContent('Publish date/time is invalid.');
    const canonical = screen.getByLabelText('Canonical URL');
    expect(canonical).toHaveAttribute('aria-invalid', 'true');
    expect(canonical).toHaveClass('is-invalid');
    expect(document.getElementById('collapseSeo')).toHaveClass('show');
    expect(screen.getByLabelText('Publish time')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.queryByText('Draft saved')).toBeNull();
    // The author's text is untouched.
    expect(titleField()).toHaveValue('Rome trip');
  });

  it('shows a stale edit (409) in place and keeps what was typed', async () => {
    server.use(http.patch(`${API}/weblogs/:handle/entries/:id`, () =>
      problem(409, 'entry.changed', "The entry's text changed after it was read; get the entry again and redo the change.")));
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await typeInEditor(' typed');
    await click('Save draft');
    expect(await screen.findByRole('alert')).toHaveTextContent("The entry's text changed after it was read");
    expect(cm().state.doc.toString()).toBe('Hello **Rome** typed');
    expect(screen.queryByText('Draft saved')).toBeNull();
    // A refusal clears when the next attempt is made.
    server.resetHandlers();
    await click('Save draft');
    await screen.findByText('Draft saved');
    expect(document.getElementById('errors')).toBeNull();
  });

  it('refuses a pin the blog has no room for, on the pin', async () => {
    server.use(http.patch(`${API}/weblogs/:handle/entries/:id`, () => problem(400, 'request.invalidFields', 'Too many pinned.', [
      { field: 'pinned', message: 'At most 3 posts can be pinned.' }])));
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await userEvent.click(await screen.findByRole('checkbox', { name: /Pin to the top/ }));
    await click('Save draft');
    await waitFor(() => { expect(screen.getByRole('checkbox', { name: /Pin to the top/ })).toHaveAttribute('aria-invalid', 'true'); });
  });
});

describe('the entry editor: autosave and a lost session', () => {
  const key = `${APP_DRAFT_PREFIX}jake:alpha:e1`;

  it('keeps unsaved text locally after a pause, says so, and drops it on a save', async () => {
    open('/weblogs/alpha/entries/e1');
    await loaded();
    expect(document.getElementById('editorStatusSave')).toHaveTextContent('Saved');
    await userEvent.type(titleField(), '!');
    expect(document.getElementById('editorStatusSave')).toHaveTextContent('Unsaved changes');
    await waitFor(() => { expect(window.localStorage.getItem(key)).not.toBeNull(); }, { timeout: 4000 });
    expect(JSON.parse(window.localStorage.getItem(key) ?? '{}').text).toContain('Rome trip!');
    await waitFor(() => { expect(document.getElementById('editorStatusSave')).toHaveTextContent('Draft saved locally'); });
    await click('Save draft');
    await screen.findByText('Draft saved');
    await waitFor(() => { expect(window.localStorage.getItem(key)).toBeNull(); });
    expect(document.getElementById('editorStatusSave')).toHaveTextContent('Saved');
  });

  it('offers unsaved text from an earlier visit and puts all of it back', async () => {
    const snapshot = { title: 'Rome, drafted', text: 'Drafted body', summary: 'S', category: 'Food', tags: 'x', pubTime: '', pinned: false,
      strand: '', pinnedToMain: false, metaTitle: '', searchDescription: '', canonicalUrl: '', noindex: false, commentsClosed: false,
      jsonLdType: 'BLOG_POSTING', geoLatitude: '', geoLongitude: '', eventStart: '', eventEnd: '', eventLocation: '',
      featuredImageId: '', ogImageId: '', locale: 'en_US' };
    window.localStorage.setItem(key, JSON.stringify({ text: JSON.stringify(snapshot), at: Date.now() }));
    open('/weblogs/alpha/entries/e1');
    await loaded();
    const bar = document.getElementById('draftRecoveryBar') as HTMLElement;
    expect(bar).toHaveTextContent(/Unsaved changes from/);
    await userEvent.click(within(bar).getByRole('button', { name: 'Restore' }));
    expect(titleField()).toHaveValue('Rome, drafted');
    expect(cm().state.doc.toString()).toBe('Drafted body');
    expect(screen.getByLabelText('Category')).toHaveValue('Food');
    expect(document.getElementById('draftRecoveryBar')).toBeNull();
  });

  it('discards an offered draft on request', async () => {
    window.localStorage.setItem(key, JSON.stringify({ text: JSON.stringify({ title: 'Other' }), at: Date.now() }));
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await userEvent.click(within(document.getElementById('draftRecoveryBar') as HTMLElement).getByRole('button', { name: 'Discard' }));
    expect(window.localStorage.getItem(key)).toBeNull();
    expect(titleField()).toHaveValue('Rome trip');
  });

  it('survives a 401 on save: shows the sign-in treatment, writes the text at once, and offers it back', async () => {
    server.use(http.patch(`${API}/weblogs/:handle/entries/:id`, () => problem(401, 'auth.required', 'Sign in.')));
    const first = open('/weblogs/alpha/entries/e1');
    await loaded();
    await userEvent.type(titleField(), ' (typed just now)');
    await click('Save draft');
    expect(await screen.findByText(/You have been signed out/)).toBeInTheDocument();
    expect(JSON.parse(window.localStorage.getItem(key) ?? '{}').text).toContain('typed just now');
    first.unmount();

    server.resetHandlers();
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await userEvent.click(within(document.getElementById('draftRecoveryBar') as HTMLElement).getByRole('button', { name: 'Restore' }));
    expect(titleField()).toHaveValue('Rome trip (typed just now)');
  });

  it('warns before leaving with unsaved edits, and only then', async () => {
    open('/weblogs/alpha/entries/e1');
    await loaded();
    const quiet = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(quiet);
    expect(quiet.defaultPrevented).toBe(false);
    await userEvent.type(titleField(), 'x');
    const warned = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(warned);
    expect(warned.defaultPrevented).toBe(true);
  });

  it('keeps the title to one line: Enter moves to the body, a pasted line break becomes a space', async () => {
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await userEvent.click(titleField());
    fireEvent.change(titleField(), { target: { value: 'Two\nlines' } });
    expect(titleField()).toHaveValue('Two lines');
    await userEvent.keyboard('{Enter}');
    expect(titleField()).toHaveValue('Two lines');
    expect(document.activeElement).toBe(cm().contentDOM);
  });
});

describe('the entry editor: SEO and structured data', () => {
  it('counts characters the way themes print the title, and warns in words', async () => {
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await userEvent.click(screen.getByRole('button', { name: 'SEO and sharing' }));
    const count = document.getElementById('seo_metaTitle_count') as HTMLElement;
    // "Rome trip" + " — " + "Alpha Blog": the entry title, a dash and the weblog name.
    expect(count.querySelector('.seo-count-n')).toHaveTextContent('22');
    await userEvent.type(screen.getByLabelText('Meta title'), 'x'.repeat(61));
    expect(count.querySelector('.seo-count-n')).toHaveTextContent('61');
    expect(count).toHaveTextContent('Search results will cut this off.');
    const description = document.getElementById('seo_metaDescription_count') as HTMLElement;
    expect(description).not.toHaveTextContent('Short;');
    await userEvent.type(screen.getByLabelText('Meta description'), 'too short');
    expect(description).toHaveTextContent('Short; search engines may use text from the page instead.');
    expect(document.getElementById('seo_snippet_title')).toHaveTextContent('x'.repeat(61));
    expect(document.getElementById('seo_snippet_description')).toHaveTextContent('too short');
    expect(document.getElementById('seo_snippet_url')).toHaveTextContent('https://blog.example.com/alpha/entry/rome-trip');
  });

  it('shows the event rows only for the Event type, and keeps what was typed when the type changes back', async () => {
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await userEvent.click(screen.getByRole('button', { name: 'SEO and sharing' }));
    const rows = document.getElementById('seo_row_event') as HTMLElement;
    expect(rows).toHaveStyle({ display: 'none' });
    await userEvent.selectOptions(screen.getByLabelText('Structured data type'), 'EVENT');
    expect(rows).not.toHaveStyle({ display: 'none' });
    await userEvent.type(screen.getByLabelText('Venue name'), 'Colosseum');
    fireEvent.change(screen.getByLabelText('Event starts'), { target: { value: '2026-08-02T19:30' } });
    await userEvent.selectOptions(screen.getByLabelText('Structured data type'), 'BLOG_POSTING');
    expect(rows).toHaveStyle({ display: 'none' });
    expect(screen.getByLabelText('Venue name', { selector: 'input' })).toHaveValue('Colosseum');
    await userEvent.selectOptions(screen.getByLabelText('Structured data type'), 'EVENT');
    await click('Save draft');
    await screen.findByText('Draft saved');
    expect(bodyOf('PATCH')).toMatchObject({ jsonLdType: 'EVENT', eventStart: '2026-08-02T19:30', eventLocation: 'Colosseum' });
  });

  it('sends place coordinates as numbers and flags noindex and closed comments', async () => {
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await userEvent.click(screen.getByRole('button', { name: 'SEO and sharing' }));
    await userEvent.type(screen.getByLabelText('Latitude'), '48.8584');
    await userEvent.type(screen.getByLabelText('Longitude'), '2.2945');
    await userEvent.click(screen.getByRole('checkbox', { name: /noindex/ }));
    await userEvent.click(screen.getByRole('checkbox', { name: 'Comments closed' }));
    await click('Save draft');
    await screen.findByText('Draft saved');
    expect(bodyOf('PATCH')).toMatchObject({ geoLatitude: 48.8584, geoLongitude: 2.2945, noindex: true, commentsClosed: true });
  });

  it('links the comment counts to moderation for a publisher on a weblog with comments on', async () => {
    state.comments = [
      { id: 'c1', status: 'PUBLISHED' }, { id: 'c2', status: 'PUBLISHED', replyTo: 'c1' }, { id: 'c3', status: 'PENDING' }
    ];
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await userEvent.click(screen.getByRole('button', { name: 'SEO and sharing' }));
    const link = await screen.findByRole('link', { name: '1 published · 1 awaiting approval' });
    expect(link).toHaveAttribute('href', 'http://pressroom.test/pressroom/pressroom-ui/authoring/comments.rol?weblog=alpha&entry=e1');
  });

  it('hides the comment counts when comments are off for the weblog', async () => {
    (state.weblog as { commentsEnabled: boolean }).commentsEnabled = false;
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await userEvent.click(screen.getByRole('button', { name: 'SEO and sharing' }));
    expect(document.getElementById('entryCommentCounts')).toBeNull();
  });
});

describe('the entry editor: images', () => {
  it('previews the featured image, picks one through the chooser (images only) and clears it', async () => {
    state.entry['featuredImageId'] = 'img1';
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await userEvent.click(screen.getByRole('button', { name: 'SEO and sharing' }));
    await waitFor(() => { expect(document.getElementById('seo_featuredImage_preview')).toHaveAttribute('src', 'https://x/m/img1?t=true'); });

    await userEvent.click(screen.getAllByRole('button', { name: 'Choose image' })[1] as HTMLElement);
    const frame = document.getElementById('mediaFileEditor') as HTMLElement;
    expect(frame).toHaveAttribute('src', expect.stringContaining('/pressroom-ui/authoring/overlay/mediaFileImageChooser.rol?weblog=alpha&imagesOnly=true'));
    // A file that is not an image is refused in place; the chooser stays open.
    act(() => { window.onSelectMediaFile?.('guide.pdf', 'https://x/guide.pdf', 'false', 'doc1'); });
    expect(document.getElementById('mediaChooserRefusal')).toHaveTextContent('Choose an image: this picker takes image files only.');
    act(() => { window.onSelectMediaFile?.('b.jpg', 'https://x/b.jpg', 'true', 'img2'); });
    expect(document.getElementById('mediaFileEditor')).toBeNull();
    expect(document.getElementById('seo_ogImage_preview')).toHaveAttribute('src', 'https://x/b.jpg?t=true');
    await click('Save draft');
    await screen.findByText('Draft saved');
    expect(bodyOf('PATCH')).toMatchObject({ featuredImageId: 'img1', ogImageId: 'img2' });

    await userEvent.click(document.getElementById('seo_featuredImage_clear') as HTMLElement);
    await click('Save draft');
    await waitFor(() => { expect(bodyOf('PATCH')['clear']).toEqual(['featuredImageId']); });
  });
});

describe('role fixture sanity', () => {
  it('uses the editor bootstrap for the signed-in person', () => {
    expect(editorBoot.user.userName).toBe('jake');
  });
});
