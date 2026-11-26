import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { appRoutes } from '../../../src/admin/router';
import { writeOf, type FormState } from '../../../src/admin/screens/pages/PageEditor';
import { APP_DRAFT_PREFIX } from '../../../src/admin/session/drafts';
import { editorBoot, renderRoutes } from '../support';
import { API, makeServer, page, problem, writes, writing } from './pagesSupport';

const { server, start } = makeServer();
start();

const open = async (at: string) => {
  const view = renderRoutes(appRoutes, at, { boot: editorBoot });
  return { ...view, user: userEvent.setup() };
};
const withPage = (over: Record<string, unknown> = {}) =>
  server.use(http.get(`${API}/weblogs/:handle/pages/p2`, () => HttpResponse.json(page(over))));
const input = (id: string) => document.getElementById(id) as HTMLInputElement;
const put = (id: string, value: string) => { fireEvent.change(input(id), { target: { value } }); };

describe('PageEditor: a new page', () => {
  it('shows the empty form the JSP does, with no delete and no revisions', async () => {
    await open('/weblogs/alpha/pages/new');
    expect(await screen.findByRole('heading', { level: 1, name: 'New page' })).toBeInTheDocument();
    expect(input('page_bean_title')).toHaveClass('editor-title');
    await waitFor(() => { expect(document.querySelector('.editor-slug-prefix')).toHaveTextContent('https://blog.example.com/alpha/'); });
    expect(input('page_bean_status')).toHaveValue('DRAFT');
    expect(input('page_bean_navOrder')).toHaveValue(0);
    expect(document.getElementById('deletePageButton')).toBeNull();
    expect(document.getElementById('pageRevisionsCard')).toBeNull();
    expect(document.querySelector('.editor-preview-link')).toBeNull();
    expect(screen.getByText('Not saved')).toBeInTheDocument();
  });

  it('creates it, goes to its own address and says it was saved', async () => {
    server.use(
      writing('post', '/weblogs/alpha/pages', () => HttpResponse.json(page({ id: 'p9', slug: 'contact', title: 'Contact' }), { status: 201 })),
      http.get(`${API}/weblogs/:handle/pages/p9`, () => HttpResponse.json(page({ id: 'p9', slug: 'contact', title: 'Contact' }))));
    const { router, user } = await open('/weblogs/alpha/pages/new');
    await screen.findByLabelText('Title');
    await user.type(input('page_bean_title'), 'Contact');
    await user.selectOptions(input('page_bean_status'), 'PUBLISHED');
    await user.click(screen.getByLabelText('Show in navigation'));
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => { expect(router.state.location.pathname).toBe('/weblogs/alpha/pages/p9'); });
    expect(await screen.findByText('Page saved.')).toBeInTheDocument();
    expect(writes).toEqual([{ method: 'POST', url: '/weblogs/alpha/pages', body: {
      title: 'Contact', text: '', status: 'PUBLISHED', showInNav: true, noindex: false, navOrder: 0, strand: false
    } }]);
    expect(await screen.findByRole('heading', { level: 1, name: 'Edit page' })).toBeInTheDocument();
  });

  it('puts a refused field on the field, focused, with the message in the banner', async () => {
    server.use(writing('post', '/weblogs/alpha/pages', () => problem(400, 'invalid.request', 'The page was not saved.', [
      { field: 'slug', message: 'That address is reserved. Choose another slug.', code: 'slug.reserved' },
      { field: 'canonicalUrl', message: 'Must be an absolute http(s) URL.' }])));
    const { user } = await open('/weblogs/alpha/pages/new');
    await screen.findByLabelText('Title');
    await user.type(input('page_bean_title'), 'Admin');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    const banner = await screen.findByRole('alert');
    expect(banner).toHaveTextContent('That address is reserved. Choose another slug.');
    expect(input('page_bean_slug')).toHaveClass('is-invalid');
    expect(input('page_bean_slug')).toHaveAttribute('aria-invalid', 'true');
    expect(input('page_bean_slug')).toHaveFocus();
    expect(input('seo_canonicalUrl')).toHaveClass('is-invalid');
    expect(input('page_bean_title')).not.toHaveAttribute('aria-invalid');
    // Nothing was lost: the author's title is still there.
    expect(input('page_bean_title')).toHaveValue('Admin');
  });

  it('creates a strand with its parts in a second step, and hides the whole panel for the site space', async () => {
    server.use(
      http.get(`${API}/weblogs/alpha/entries`, () => HttpResponse.json({ hasMore: false, offset: 0, limit: 200, items: [
        { id: 'e1', title: 'First post', status: 'PUBLISHED' },
        { id: 'e2', title: 'Taken post', status: 'PUBLISHED', strand: 'other' },
        { id: 'e3', title: 'Gone post', status: 'TRASHED' }
      ] })),
      writing('post', '/weblogs/alpha/pages', () => HttpResponse.json(page({ id: 'p9', strand: true }), { status: 201 })),
      writing('put', '/weblogs/alpha/pages/p9/parts', () => HttpResponse.json(page({ id: 'p9', strand: true, parts: [{ id: 'e1', title: 'First post', status: 'PUBLISHED' }] }))));
    const { user } = await open('/weblogs/alpha/pages/new');
    await screen.findByLabelText('Title');
    expect(document.getElementById('page_strand_parts')).toBeNull();
    await user.type(input('page_bean_title'), 'Series');
    await user.click(screen.getByLabelText('This page is a strand'));
    const select = await screen.findByLabelText('Add posts');
    await waitFor(() => { expect(within(select).getAllByRole('option')).toHaveLength(2); });
    expect(within(select).getByRole('option', { name: 'First post' })).toBeInTheDocument();
    await user.selectOptions(select, 'e1');
    expect(document.querySelector('.strand-part-title')).toHaveTextContent('First post');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('Page saved.');
    expect(writes.map((w) => `${w.method} ${w.url}`)).toEqual(['POST /weblogs/alpha/pages', 'PUT /weblogs/alpha/pages/p9/parts']);
    expect(writes[1]?.body).toEqual({ entries: ['e1'] });
  });

  it('has no strand panel and sends no strand flag for the site space', async () => {
    server.use(writing('post', '/weblogs/site-space/pages', () => HttpResponse.json(page({ id: 'p9' }), { status: 201 })));
    const { user } = await open('/weblogs/site-space/pages/new');
    await screen.findByLabelText('Title');
    expect(screen.queryByLabelText('This page is a strand')).not.toBeInTheDocument();
    expect(document.querySelector('p.subtitle')).toHaveTextContent('Site pages');
    await user.type(input('page_bean_title'), 'Legal');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('Page saved.');
    expect(writes[0]?.body).not.toHaveProperty('strand');
  });

  it('offers unsaved text from an earlier visit and restores every field', async () => {
    const snapshot = { title: 'Lost title', slug: 'lost', text: 'lost body', status: 'PUBLISHED', navOrder: '4' };
    window.localStorage.setItem(`${APP_DRAFT_PREFIX}jake:alpha:pageEdit:new`,
      JSON.stringify({ text: JSON.stringify(snapshot), at: Date.parse('2026-11-25T10:15:00Z') }));
    const { user } = await open('/weblogs/alpha/pages/new');
    const bar = await screen.findByText(/Unsaved changes from/);
    expect(bar.closest('#draftRecoveryBar')).not.toBeNull();
    await user.click(screen.getByRole('button', { name: 'Restore' }));
    expect(input('page_bean_title')).toHaveValue('Lost title');
    expect(input('page_bean_slug')).toHaveValue('lost');
    expect(input('page_bean_status')).toHaveValue('PUBLISHED');
    expect(input('page_bean_navOrder')).toHaveValue(4);
    await waitFor(() => { expect(document.querySelector('.cm-content')).toHaveTextContent('lost body'); });
  });

  it('writes the unsaved form to storage after a pause, and clears it on save', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      server.use(writing('post', '/weblogs/alpha/pages', () => HttpResponse.json(page({ id: 'p9' }), { status: 201 })));
      const { user } = await open('/weblogs/alpha/pages/new');
      await screen.findByLabelText('Title');
      await user.type(input('page_bean_title'), 'Typing');
      await vi.advanceTimersByTimeAsync(2500);
      const key = `${APP_DRAFT_PREFIX}jake:alpha:pageEdit:new`;
      expect(JSON.parse(window.localStorage.getItem(key) ?? '{}')).toHaveProperty('text', expect.stringContaining('Typing'));
      await user.click(screen.getByRole('button', { name: 'Save' }));
      await screen.findByText('Page saved.');
      expect(window.localStorage.getItem(key)).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('warns before leaving with unsaved changes, and only then', async () => {
    const { user } = await open('/weblogs/alpha/pages/new');
    await screen.findByLabelText('Title');
    const leave = () => { const e = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(e); return e.defaultPrevented; };
    expect(leave()).toBe(false);
    await user.type(input('page_bean_title'), 'x');
    expect(leave()).toBe(true);
  });
});

describe('PageEditor: an existing page', () => {
  it('loads the page into the form, with the copy and full-preview links of a published page', async () => {
    withPage({ metaTitle: 'About', canonicalUrl: 'https://elsewhere.test/a', noindex: true, ogImageId: 'm1', navOrder: 3 });
    server.use(http.get(`${API}/weblogs/:handle/media/m1`, () => HttpResponse.json({ id: 'm1', url: 'https://cdn.test/m1.jpg' })));
    const { user } = await open('/weblogs/alpha/pages/p2');
    expect(await screen.findByDisplayValue('About us')).toBe(input('page_bean_title'));
    expect(screen.getByRole('heading', { level: 1, name: 'Edit page' })).toBeInTheDocument();
    expect(input('page_bean_slug')).toHaveValue('about');
    expect(input('page_bean_status')).toHaveValue('PUBLISHED');
    expect(input('page_bean_navOrder')).toHaveValue(3);
    expect(screen.getByLabelText('Show in navigation')).toBeChecked();
    await waitFor(() => { expect(document.querySelector('.cm-content')).toHaveTextContent('Hello **world**'); });
    expect(document.querySelector('.editor-preview-link')).toHaveAttribute('href', 'https://blog.example.com/alpha/about');
    // The SEO drawer is closed until asked for.
    expect(document.getElementById('collapseSeo')).not.toHaveClass('show');
    await user.click(screen.getByRole('button', { name: 'SEO and sharing' }));
    expect(document.getElementById('collapseSeo')).toHaveClass('show');
    expect(input('seo_metaTitle')).toHaveValue('About');
    expect(input('seo_noindex')).toBeChecked();
    await waitFor(() => { expect(document.getElementById('seo_ogImage_preview')).toHaveAttribute('src', 'https://cdn.test/m1.jpg'); });
    const writeText = vi.spyOn(navigator.clipboard, 'writeText');
    await user.click(screen.getByRole('button', { name: 'Copy to clipboard' }));
    expect(writeText).toHaveBeenCalledWith('https://blog.example.com/alpha/about');
    await waitFor(() => { expect(screen.getByRole('button', { name: 'Copy to clipboard' })).toHaveClass('copied'); });
  });

  it('shows "not found" for a page that is not there', async () => {
    server.use(http.get(`${API}/weblogs/:handle/pages/p2`, () => problem(404, 'page.notFound', 'No such page.')));
    await open('/weblogs/alpha/pages/p2');
    expect(await screen.findByText('Page not found.')).toBeInTheDocument();
    expect(document.getElementById('pageEditForm')).toBeNull();
  });

  it('saves with the hash it was loaded with, clearing a field the author emptied', async () => {
    withPage({ metaTitle: 'Old meta', status: 'DRAFT' });
    server.use(writing('patch', '/weblogs/alpha/pages/p2', () => HttpResponse.json(page({ title: 'About them', contentHash: 'h2', updateTime: '2026-11-25T11:00:00Z' }))));
    const { user } = await open('/weblogs/alpha/pages/p2');
    await screen.findByDisplayValue('About us');
    await user.click(screen.getByRole('button', { name: 'SEO and sharing' }));
    put('page_bean_title', 'About them');
    put('seo_metaTitle', '');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Page saved.')).toBeInTheDocument();
    expect(writes).toEqual([{ method: 'PATCH', url: '/weblogs/alpha/pages/p2', body: {
      title: 'About them', text: 'Hello **world**', status: 'DRAFT', showInNav: true, noindex: false, slug: 'about',
      navOrder: 1, strand: false, expectedContentHash: 'h1', clear: ['metaTitle']
    } }]);
    // The saved page is what the form shows now.
    expect(input('page_bean_title')).toHaveValue('About them');
    expect(screen.getByText('Saved')).toBeInTheDocument();
  });

  it('shows the page-changed conflict in place and keeps the author\'s text', async () => {
    withPage();
    server.use(writing('patch', '/weblogs/alpha/pages/p2', () => problem(409, 'page.changed',
      "The page's text changed after it was read; get the page again and redo the change.")));
    const { user } = await open('/weblogs/alpha/pages/p2');
    await screen.findByDisplayValue('About us');
    put('page_bean_title', 'Mine');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText(/text changed after it was read/)).toBeInTheDocument();
    expect(input('page_bean_title')).toHaveValue('Mine');
    expect(screen.queryByText('Page saved.')).not.toBeInTheDocument();
  });

  it('empties a strand and unticks it in the right order', async () => {
    withPage({ strand: true, parts: [{ id: 'e1', title: 'First post', status: 'PUBLISHED' }] });
    server.use(
      http.get(`${API}/weblogs/alpha/entries`, () => HttpResponse.json({ hasMore: false, offset: 0, limit: 200, items: [] })),
      writing('put', '/weblogs/alpha/pages/p2/parts', () => HttpResponse.json(page({ strand: true }))),
      writing('patch', '/weblogs/alpha/pages/p2', () => HttpResponse.json(page({ contentHash: 'h2' }))));
    const { user } = await open('/weblogs/alpha/pages/p2');
    await screen.findByText('First post');
    await user.click(screen.getByRole('button', { name: 'Remove from strand: First post' }));
    await user.click(screen.getByLabelText('This page is a strand'));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('Page saved.');
    expect(writes.map((w) => `${w.method} ${w.url}`)).toEqual(['PUT /weblogs/alpha/pages/p2/parts', 'PATCH /weblogs/alpha/pages/p2']);
    expect(writes[0]?.body).toEqual({ entries: [] });
    expect(writes[1]?.body).toHaveProperty('strand', false);
  });

  it('moves, removes and refuses parts: a refused list marks the panel', async () => {
    withPage({ strand: true, parts: [
      { id: 'e1', title: 'First post', status: 'PUBLISHED' }, { id: 'e2', title: 'Second post', status: 'DRAFT' }] });
    server.use(
      http.get(`${API}/weblogs/alpha/entries`, () => HttpResponse.json({ hasMore: false, offset: 0, limit: 200, items: [
        { id: 'e5', title: 'Spare post', status: 'DRAFT' }] })),
      writing('put', '/weblogs/alpha/pages/p2/parts', () => problem(400, 'strand.postInAnotherStrand', "Post 'e5' is already in another strand.",
        [{ field: 'entries', message: "Post 'e5' is already in another strand." }])));
    const { user } = await open('/weblogs/alpha/pages/p2');
    await screen.findByText('First post');
    const titles = () => [...document.querySelectorAll('.strand-part-title')].map((n) => n.textContent);
    await user.click(screen.getByRole('button', { name: 'Move down: First post' }));
    expect(titles()).toEqual(['Second post', 'First post']);
    await user.click(screen.getByRole('button', { name: 'Move up: First post' }));
    expect(titles()).toEqual(['First post', 'Second post']);
    await user.click(screen.getByRole('button', { name: 'Move up: First post' }));
    expect(titles()).toEqual(['First post', 'Second post']);
    await waitFor(() => { expect(within(screen.getByLabelText('Add posts')).getByRole('option', { name: 'Spare post' })).toBeInTheDocument(); });
    await user.selectOptions(screen.getByLabelText('Add posts'), 'e5');
    expect(titles()).toEqual(['First post', 'Second post', 'Spare post']);
    fireEvent.dragStart(document.querySelectorAll('.strand-part')[2] as Element);
    fireEvent.drop(document.querySelectorAll('.strand-part')[0] as Element);
    expect(titles()).toEqual(['Spare post', 'First post', 'Second post']);
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect((await screen.findAllByText("Post 'e5' is already in another strand.")).length).toBeGreaterThan(0);
    expect(document.getElementById('page_strand_parts')).toHaveClass('is-invalid');
    expect(writes.map((w) => w.method)).toEqual(['PUT']);
  });

  it('finds posts to add by searching, not by listing the first 200', async () => {
    const asked: URLSearchParams[] = [];
    server.use(http.get(`${API}/weblogs/alpha/entries`, ({ request }) => {
      const params = new URL(request.url).searchParams;
      asked.push(params);
      return HttpResponse.json({ hasMore: false, offset: 0, limit: 20, items: params.get('text') === 'bea'
        ? [{ id: 'e7', title: 'Beach day', status: 'PUBLISHED' }] : [{ id: 'e1', title: 'Newest post', status: 'PUBLISHED' }] });
    }));
    const { user } = await open('/weblogs/alpha/pages/new');
    await screen.findByLabelText('Title');
    await user.click(screen.getByLabelText('This page is a strand'));
    await waitFor(() => { expect(within(screen.getByLabelText('Add posts')).getByRole('option', { name: 'Newest post' })).toBeInTheDocument(); });
    expect(asked[0]?.get('limit')).toBe('20');
    expect(asked[0]?.has('text')).toBe(false);
    await user.type(screen.getByLabelText('Find posts'), 'bea');
    await waitFor(() => { expect(within(screen.getByLabelText('Add posts')).getByRole('option', { name: 'Beach day' })).toBeInTheDocument(); });
    expect(asked.at(-1)?.get('text')).toBe('bea');
    await user.selectOptions(screen.getByLabelText('Add posts'), 'e7');
    expect(document.querySelector('.strand-part-title')).toHaveTextContent('Beach day');
    // A part chosen from one search stays named after the next search replaces the choices.
    await user.clear(screen.getByLabelText('Find posts'));
    await waitFor(() => { expect(within(screen.getByLabelText('Add posts')).getByRole('option', { name: 'Newest post' })).toBeInTheDocument(); });
    expect(document.querySelector('.strand-part-title')).toHaveTextContent('Beach day');
  });

  it('shows the address from the page itself: the blog root for its front page, a custom domain as the prefix', async () => {
    server.use(http.get(`${API}/weblogs/:handle`, () => HttpResponse.json({
      handle: 'alpha', name: 'Alpha Blog', active: true, timeZone: 'UTC', frontPage: 'p2', absoluteUrl: 'https://maiia.example.org/' })));
    withPage({ absoluteUrl: 'https://maiia.example.org/' });
    const { user } = await open('/weblogs/alpha/pages/p2');
    await screen.findByDisplayValue('About us');
    expect(document.querySelector('.editor-slug-prefix')).toHaveTextContent('https://maiia.example.org/');
    expect(document.querySelector('.editor-preview-link')).toHaveAttribute('href', 'https://maiia.example.org/');
    const writeText = vi.spyOn(navigator.clipboard, 'writeText');
    await user.click(screen.getByRole('button', { name: 'Copy to clipboard' }));
    expect(writeText).toHaveBeenCalledWith('https://maiia.example.org/');
  });

  it('offers no copy or full-preview link for a draft', async () => {
    withPage({ status: 'DRAFT', absoluteUrl: 'https://blog.example.com/alpha/about' });
    await open('/weblogs/alpha/pages/p2');
    await screen.findByDisplayValue('About us');
    expect(document.querySelector('.editor-preview-link')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Copy to clipboard' })).toBeNull();
  });

  it('says the site page that is the front door is served at the site root', async () => {
    server.use(http.get(`${API}/weblogs/site-space/pages/p2`, () => HttpResponse.json(page({
      frontDoor: true, absoluteUrl: 'https://blog.example.com/' }))));
    await open('/weblogs/site-space/pages/p2');
    await screen.findByDisplayValue('About us');
    expect(document.querySelector('p.editor-permalink-note'))
      .toHaveTextContent('This page is the front door, so it is served at https://blog.example.com/ rather than at its slug.');
  });

  it('has no front-door note for an ordinary page', async () => {
    withPage();
    await open('/weblogs/alpha/pages/p2');
    await screen.findByDisplayValue('About us');
    expect(document.querySelector('p.editor-permalink-note')).toBeNull();
  });

  it('asks before moving to the trash, says what a strand keeps, and returns to the list with the news', async () => {
    withPage({ strand: true, parts: [{ id: 'e1', title: 'First post', status: 'PUBLISHED' }] });
    server.use(
      http.get(`${API}/weblogs/alpha/entries`, () => HttpResponse.json({ hasMore: false, offset: 0, limit: 200, items: [] })),
      writing('delete', '/weblogs/alpha/pages/p2', () => new HttpResponse(null, { status: 204 })));
    const { router, user } = await open('/weblogs/alpha/pages/p2');
    await screen.findByDisplayValue('About us');
    await user.click(screen.getByRole('button', { name: 'Move to the trash: About us' }));
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('Move to the trash: About us');
    expect(dialog).toHaveTextContent('1 post stays attached.');
    await user.click(within(dialog).getByRole('button', { name: 'Move to the trash' }));
    await waitFor(() => { expect(router.state.location.pathname).toBe('/weblogs/alpha/pages'); });
    expect(await screen.findByText('Page "about" moved to the trash.')).toBeInTheDocument();
  });

  it('shows a refused trash in place', async () => {
    withPage();
    server.use(writing('delete', '/weblogs/alpha/pages/p2', () => problem(409, 'page.alreadyTrashed', 'Page is already in the trash.')));
    const { user } = await open('/weblogs/alpha/pages/p2');
    await screen.findByDisplayValue('About us');
    await user.click(screen.getByRole('button', { name: 'Move to the trash: About us' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Move to the trash' }));
    expect(await screen.findByText('Page is already in the trash.')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('is refused for someone who may not edit pages', async () => {
    server.use(http.get(`${API}/weblogs/alpha/pages/p2`, () => problem(403, 'forbidden', 'You may not edit this weblog\'s pages.')));
    await open('/weblogs/alpha/pages/p2');
    expect(await screen.findByText("You may not edit this weblog's pages.")).toBeInTheDocument();
    expect(document.getElementById('pageEditForm')).toBeNull();
  });
});

describe('PageEditor: revisions', () => {
  const revisions = [
    { id: 'r2', created: '2026-11-21T09:00:00Z', creator: 'maiia', note: 'Fixed a typo', kept: true, title: 'About' },
    { id: 'r1', created: '2026-11-20T09:00:00Z', creator: 'jake', kept: false, title: 'About' }
  ];
  const setup = () => {
    withPage();
    server.use(http.get(`${API}/weblogs/alpha/pages/p2/revisions`, () => HttpResponse.json(revisions)));
  };

  it('lists them as the JSP does, with the note under its row and a kept marker', async () => {
    setup();
    await open('/weblogs/alpha/pages/p2');
    const card = await screen.findByText('Revisions');
    expect(card.closest('#pageRevisionsCard')).not.toBeNull();
    const table = document.getElementById('pageRevisionsTable') as HTMLElement;
    expect(within(table).getAllByRole('row')).toHaveLength(3);
    expect(table.querySelector('.revision-note-row')).toHaveTextContent('Fixed a typo');
    expect(table.querySelector('.revision-kept-marker')).toHaveTextContent('Kept');
    expect(within(table).getByRole('button', { name: 'Release' })).toBeInTheDocument();
    expect(within(table).getByRole('button', { name: 'Keep' })).toBeInTheDocument();
  });

  it('has no revisions box for a page with none', async () => {
    withPage();
    server.use(http.get(`${API}/weblogs/alpha/pages/p2/revisions`, () => HttpResponse.json([])));
    await open('/weblogs/alpha/pages/p2');
    await screen.findByDisplayValue('About us');
    expect(document.getElementById('pageRevisionsCard')).toBeNull();
  });

  it('compares a revision in a modal that colours added and removed lines, and closes', async () => {
    setup();
    server.use(http.get(`${API}/weblogs/alpha/pages/p2/revisions/r1`, () => HttpResponse.json({
      id: 'r1', diff: '--- r1\n+++ now\n@@ -1 +1 @@\n-old line\n+new line\n same' })));
    const { user } = await open('/weblogs/alpha/pages/p2');
    await screen.findByText('Revisions');
    await user.click(within(document.getElementById('pageRevisionsTable') as HTMLElement).getAllByRole('button', { name: 'Compare' })[1] as HTMLElement);
    const body = await screen.findByText('+new line');
    expect(body).toHaveClass('diff-added');
    expect(screen.getByText('-old line')).toHaveClass('diff-removed');
    expect(screen.getByText('same')).toHaveClass('diff-same');
    expect(screen.getByRole('dialog')).toHaveTextContent('Compare');
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('says so when the revision matches the page, and when the comparison cannot load', async () => {
    setup();
    server.use(http.get(`${API}/weblogs/alpha/pages/p2/revisions/r2`, () => HttpResponse.json({ id: 'r2', diff: '' })),
      http.get(`${API}/weblogs/alpha/pages/p2/revisions/r1`, () => problem(500, 'internal', 'Boom.')));
    const { user } = await open('/weblogs/alpha/pages/p2');
    await screen.findByText('Revisions');
    const compare = () => within(document.getElementById('pageRevisionsTable') as HTMLElement).getAllByRole('button', { name: 'Compare' });
    await user.click(compare()[0] as HTMLElement);
    expect(await screen.findByText('This revision matches the page as it is now.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Close' }));
    await user.click(compare()[1] as HTMLElement);
    expect(await screen.findByText('Preview could not be rendered.', {}, { timeout: 5000 })).toBeInTheDocument();
  });

  it('restores a revision guarded by the loaded text, and shows what it put back', async () => {
    setup();
    server.use(
      writing('post', '/weblogs/alpha/pages/p2/revisions/r1/restore', () => HttpResponse.json(page({ text: 'Restored body', contentHash: 'h9', updateTime: '2026-11-25T12:00:00Z' }))));
    const { user } = await open('/weblogs/alpha/pages/p2');
    await screen.findByText('Revisions');
    await user.click(within(document.getElementById('pageRevisionsTable') as HTMLElement).getAllByRole('button', { name: 'Restore' })[1] as HTMLElement);
    expect(await screen.findByText(/Restored\. The version you replaced/)).toBeInTheDocument();
    expect(writes).toEqual([{ method: 'POST', url: '/weblogs/alpha/pages/p2/revisions/r1/restore', body: { expectedContentHash: 'h1' } }]);
    await waitFor(() => { expect(document.querySelector('.cm-content')).toHaveTextContent('Restored body'); });
  });

  it('shows a refused restore (someone changed the page) in place', async () => {
    setup();
    server.use(writing('post', '/weblogs/alpha/pages/p2/revisions/r1/restore', () => problem(409, 'page.changed', 'The page changed.')));
    const { user } = await open('/weblogs/alpha/pages/p2');
    await screen.findByText('Revisions');
    await user.click(within(document.getElementById('pageRevisionsTable') as HTMLElement).getAllByRole('button', { name: 'Restore' })[1] as HTMLElement);
    expect(await screen.findByText('The page changed.')).toBeInTheDocument();
  });

  it('keeps and releases a revision', async () => {
    setup();
    server.use(
      writing('post', '/weblogs/alpha/pages/p2/revisions/r1/keep', () => HttpResponse.json({ id: 'r1', kept: true })),
      writing('post', '/weblogs/alpha/pages/p2/revisions/r2/unkeep', () => HttpResponse.json({ id: 'r2', kept: false })));
    const { user } = await open('/weblogs/alpha/pages/p2');
    await screen.findByText('Revisions');
    const table = document.getElementById('pageRevisionsTable') as HTMLElement;
    await user.click(within(table).getByRole('button', { name: 'Keep' }));
    expect(await screen.findByText(/Revision kept\./)).toBeInTheDocument();
    await user.click(within(table).getByRole('button', { name: 'Release' }));
    expect(await screen.findByText(/Revision released\./)).toBeInTheDocument();
    expect(writes.map((w) => w.url)).toEqual(['/weblogs/alpha/pages/p2/revisions/r1/keep', '/weblogs/alpha/pages/p2/revisions/r2/unkeep']);
  });

  it('shows a refused keep (the limit) in place', async () => {
    setup();
    server.use(writing('post', '/weblogs/alpha/pages/p2/revisions/r1/keep', () => problem(409, 'revision.keepLimit', 'One item can have at most 10 kept revisions. Release one first.')));
    const { user } = await open('/weblogs/alpha/pages/p2');
    await screen.findByText('Revisions');
    await user.click(within(document.getElementById('pageRevisionsTable') as HTMLElement).getByRole('button', { name: 'Keep' }));
    expect(await screen.findByText(/at most 10 kept revisions/)).toBeInTheDocument();
  });
});

describe('PageEditor: preview', () => {
  it('renders the page on the server and shows the sanitised HTML; a stored page previews by id', async () => {
    withPage();
    server.use(writing('post', '/weblogs/alpha/pages/p2/preview', () => HttpResponse.json({ html: '<h2>Hello</h2><p>world</p>', warnings: [] })));
    const { user } = await open('/weblogs/alpha/pages/p2');
    await screen.findByDisplayValue('About us');
    await user.click(screen.getByRole('radio', { name: 'Preview' }));
    expect(await screen.findByRole('heading', { level: 2, name: 'Hello' })).toBeInTheDocument();
    expect(writes).toEqual([{ method: 'POST', url: '/weblogs/alpha/pages/p2/preview', body: { text: 'Hello **world**' } }]);
    expect(screen.getByRole('radio', { name: 'Preview' })).toHaveAttribute('aria-checked', 'true');
    await user.click(screen.getByRole('radio', { name: 'Write' }));
    expect(document.getElementById('editorPreviewPane')).toBeNull();
  });

  it('previews a new page without an id, and says when the render failed', async () => {
    server.use(writing('post', '/weblogs/alpha/pages/preview', () => problem(500, 'internal', 'Boom.')));
    const { user } = await open('/weblogs/alpha/pages/new');
    await screen.findByLabelText('Title');
    await user.click(screen.getByRole('radio', { name: 'Preview' }));
    expect(await screen.findByText('Preview could not be rendered.', {}, { timeout: 5000 })).toBeInTheDocument();
    expect(writes[0]?.url).toBe('/weblogs/alpha/pages/preview');
  });
});

describe('writeOf', () => {
  const form: FormState = {
    title: ' T ', slug: '  ', status: 'DRAFT', text: 'x', showInNav: false, navOrder: 'nope', strand: false, parts: [],
    metaTitle: ' m ', searchDescription: '', canonicalUrl: '', noindex: false, ogImageId: ''
  };
  it('leaves out a blank slug and an unreadable order, trims optional text, and never clears on a create', () => {
    expect(writeOf(form, undefined, false)).toEqual({
      title: ' T ', text: 'x', status: 'DRAFT', showInNav: false, noindex: false, strand: false, metaTitle: 'm' });
  });
  it('clears only the optional fields that held something', () => {
    const stored = page({ searchDescription: 'was', canonicalUrl: '' });
    expect(writeOf(form, stored, true)).toMatchObject({ metaTitle: 'm', clear: ['searchDescription'], expectedContentHash: 'h1' });
    expect(writeOf(form, stored, true)).not.toHaveProperty('strand');
  });
});
