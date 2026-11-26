import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { APP_DRAFT_PREFIX } from '../../../src/admin/session/drafts';
import { API, cm, lastRequest, open, problem, resetState, server, state, titleField } from './harness';

/**
 * What the editor does when the server leaves optional things out (the API's types make nearly every
 * field optional): no status, no permalink, no hash. The screen must still say something sensible.
 */
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => { resetState(); window.localStorage.clear(); });

const click = (name: string | RegExp) => userEvent.click(screen.getByRole('button', { name }));
const loaded = () => screen.findByDisplayValue('Rome trip');
const patchReturns = (body: object) => server.use(http.patch(`${API}/weblogs/:handle/entries/:id`, () => HttpResponse.json(body)));

describe('saving when the answer is thin', () => {
  it('calls a save "Draft saved" when the server names no status, for a create and for an update', async () => {
    server.use(http.post(`${API}/weblogs/:handle/entries`, () => HttpResponse.json({ id: 'new1' }, { status: 201 })));
    const { router, unmount } = open('/weblogs/alpha/entries/new');
    await screen.findByLabelText('Category');
    await userEvent.type(titleField(), 'Fresh');
    await click('Save draft');
    await waitFor(() => { expect(router.state.location.pathname).toBe('/weblogs/alpha/entries/new1'); });
    expect(await screen.findByText('Draft saved')).toBeInTheDocument();
    unmount();

    patchReturns({ id: 'e1', title: 'Rome trip' });
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await click('Save draft');
    expect(await screen.findByText('Draft saved')).toBeInTheDocument();
  });

  it('says a contributor\'s save went to review', async () => {
    (state.me as { weblogs: object[] }).weblogs = [{ handle: 'alpha', name: 'Alpha Blog', permission: 'EDIT_DRAFT' }];
    patchReturns({ id: 'e1', title: 'Rome trip', status: 'PENDING' });
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await click('Submit for review');
    expect(await screen.findByText('Entry submitted for review')).toBeInTheDocument();
  });

  it('still announces a publish or a schedule when the answer has no link or time to show', async () => {
    patchReturns({ id: 'e1', title: 'Rome trip', status: 'PUBLISHED' });
    const { unmount } = open('/weblogs/alpha/entries/e1');
    await loaded();
    await click('Publish');
    expect(await screen.findByText(/Entry published\./)).toBeInTheDocument();
    unmount();

    patchReturns({ id: 'e1', title: 'Rome trip', status: 'SCHEDULED' });
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await click('Publish');
    expect(await screen.findByText('Entry scheduled for .')).toBeInTheDocument();
  });

  it('lets the author dismiss the saved message', async () => {
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await click('Save draft');
    const notice = await screen.findByText('Draft saved');
    await userEvent.click(within(notice.closest('#messages') as HTMLElement).getByRole('button', { name: 'Close' }));
    expect(screen.queryByText('Draft saved')).toBeNull();
  });
});

describe('refusals that name no field', () => {
  it('shows the message and marks no field invalid', async () => {
    server.use(http.patch(`${API}/weblogs/:handle/entries/:id`, () =>
      problem(400, 'request.invalidFields', 'Fix these.', [{ message: 'Something general is wrong.' }])));
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await click('Save draft');
    expect(await screen.findByRole('alert')).toHaveTextContent('Something general is wrong.');
    expect(document.querySelector('[aria-invalid="true"]')).toBeNull();
    expect(document.getElementById('collapseSeo')).not.toHaveClass('show');
  });
});

describe('a new entry\'s starting point', () => {
  it('ignores a remembered category the weblog no longer has, taking the first', async () => {
    window.localStorage.setItem('pressroom.appLastCategory.v1:http://pressroom.test/pressroom:alpha', 'Deleted');
    open('/weblogs/alpha/entries/new');
    expect(await screen.findByLabelText('Category')).toHaveValue('Trips');
    await userEvent.type(titleField(), 'Fresh');
    await click('Save draft');
    await waitFor(() => { expect(lastRequest('POST')).toBeDefined(); });
    expect((lastRequest('POST')?.body as { category: string }).category).toBe('Trips');
  });

  it('starts with no category when the weblog has none', async () => {
    state.categories = [];
    server.use(http.post(`${API}/weblogs/:handle/entries`, async ({ request }) => {
      state.requests.push({ method: 'POST', path: '/entries', body: await request.json(), search: '' });
      return HttpResponse.json({ id: 'new1', status: 'DRAFT' }, { status: 201 });
    }));
    open('/weblogs/alpha/entries/new');
    await screen.findByLabelText('Category');
    await userEvent.type(titleField(), 'Uncategorised');
    await click('Save draft');
    await waitFor(() => { expect(lastRequest('POST')).toBeDefined(); });
    expect((lastRequest('POST')?.body as { category?: string }).category ?? '').toBe('');
  });

  it('seeds the text from chosen media: an image by its id, a file as a link, a gone file skipped', async () => {
    server.use(http.get(`${API}/weblogs/:handle/media/:id`, ({ params }) => {
      if (params['id'] === 'gone') {
        return problem(404, 'media.notFound', 'No such file.');
      }
      return params['id'] === 'bare-image' ? HttpResponse.json({ contentType: 'image/png' }) : HttpResponse.json({});
    }));
    open('/weblogs/alpha/entries/new?mediaId=bare-image&mediaId=gone&mediaId=odd');
    await screen.findByLabelText('Category');
    await waitFor(() => { expect(cm().state.doc.toString()).toContain('[image id="bare-image"]'); });
    expect(cm().state.doc.toString()).toContain("<a href=''></a> (0 bytes, )");
    expect(cm().state.doc.toString()).not.toContain('gone');
  });
});

describe('a stored draft that is not a draft', () => {
  const key = `${APP_DRAFT_PREFIX}jake:alpha:e1`;
  it.each([['a JSON string', '"just text"'], ['not JSON', '{oops'], ['null', 'null']])(
    'leaves the form alone when the stored text is %s', async (_name, text) => {
      window.localStorage.setItem(key, JSON.stringify({ text, at: Date.now() }));
      open('/weblogs/alpha/entries/e1');
      await loaded();
      await userEvent.click(within(document.getElementById('draftRecoveryBar') as HTMLElement).getByRole('button', { name: 'Restore' }));
      expect(titleField()).toHaveValue('Rome trip');
      expect(cm().state.doc.toString()).toBe('Hello **Rome**');
      // Nothing was changed, so the editor does not call the entry unsaved.
      expect(screen.queryByText('Not saved')).toBeNull();
      expect(document.getElementById('draftRecoveryBar')).toBeNull();
    });
});

describe('sparse weblogs and entries', () => {
  it('says the newsletter needs a list when the weblog has none configured', async () => {
    state.entry['status'] = 'PUBLISHED';
    delete (state.weblog as { newsletterListUuid?: string }).newsletterListUuid;
    open('/weblogs/alpha/entries/e1');
    await loaded();
    expect(await screen.findByText('Set a newsletter list UUID in Weblog Settings first.')).toBeInTheDocument();
  });

  it('removes an entry the server never gave a title, naming it by what is on screen', async () => {
    delete state.entry['title'];
    const { router } = open('/weblogs/alpha/entries/e1');
    await screen.findByLabelText('Category');
    await userEvent.type(titleField(), 'My draft');
    await click(/Delete entry/);
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /Move to Trash/i }));
    await waitFor(() => { expect(router.state.location.pathname).toBe('/weblogs/alpha/entries'); });
    expect(router.state.location.state).toEqual({ message: 'Entry "My draft" moved to Trash. It can be restored from there.' });
  });

  it('restores a revision without a hash guard when the entry has none', async () => {
    delete state.entry['contentHash'];
    state.revisions = [{ id: 'r1', created: '2026-03-01T08:00:00Z', creator: 'maiia', kept: false }];
    open('/weblogs/alpha/entries/e1');
    await loaded();
    await userEvent.click((await screen.findAllByRole('button', { name: 'Restore' }))[0] as HTMLElement);
    await waitFor(() => { expect(lastRequest('POST')?.path).toContain('/revisions/r1/restore'); });
    expect(lastRequest('POST')?.body).toEqual({});
  });

  it('compares a bare revision with a bare entry as matching', async () => {
    delete state.entry['title'];
    delete state.entry['summary'];
    state.revisions = [{ id: 'r1', created: '2026-03-01T08:00:00Z', creator: 'maiia', kept: false }];
    server.use(http.get(`${API}/weblogs/:handle/entries/:id/revisions/:rid`, () => HttpResponse.json({ id: 'r1' })));
    open('/weblogs/alpha/entries/e1');
    await screen.findByLabelText('Category');
    await userEvent.click((await screen.findAllByRole('button', { name: 'Compare' }))[0] as HTMLElement);
    expect(await screen.findByText('This revision matches the entry as it is now.')).toBeInTheDocument();
  });
});
