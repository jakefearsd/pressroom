import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { appRoutes } from '../../../src/admin/router';
import { APP_DRAFT_PREFIX } from '../../../src/admin/session/drafts';
import { editorBoot, renderRoutes } from '../support';
import { API, makeServer, page, writes, writing } from './pagesSupport';

/** The page editor when the server leaves optional things out: nothing may crash, and the words must stay sensible. */
const { server, start } = makeServer();
start();

const open = (at: string) => {
  const view = renderRoutes(appRoutes, at, { boot: editorBoot });
  return { ...view, user: userEvent.setup() };
};
const input = (id: string) => document.getElementById(id) as HTMLInputElement;
const withPage = (body: object) => server.use(http.get(`${API}/weblogs/:handle/pages/p2`, () => HttpResponse.json(body)));

describe('PageEditor with a thin page record', () => {
  it('shows an empty draft with no navigation entry, and trashes it naming the empty address', async () => {
    withPage({ id: 'p2' });
    server.use(writing('delete', '/weblogs/alpha/pages/p2', () => new HttpResponse(null, { status: 204 })));
    const { user, router } = open('/weblogs/alpha/pages/p2');
    await screen.findByLabelText('Title');
    expect(input('page_bean_title')).toHaveValue('');
    expect(input('page_bean_status')).toHaveValue('DRAFT');
    expect(input('page_bean_navOrder')).toHaveValue(0);
    expect(screen.getByLabelText('Show in navigation')).not.toBeChecked();
    await user.click(await screen.findByRole('button', { name: 'Move to the trash:' }));
    expect(screen.getByRole('dialog')).not.toHaveTextContent('posts stay attached');
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Move to the trash' }));
    await waitFor(() => { expect(router.state.location.pathname).toBe('/weblogs/alpha/pages'); });
    expect(router.state.location.state).toEqual({ message: 'Page "" moved to the trash.' });
  });

  it('lists a strand\'s parts as drafts, and offers more posts, when the server gives no titles or statuses', async () => {
    withPage({ id: 'p2', strand: true, parts: [{ id: 'e1' }] });
    server.use(http.get(`${API}/weblogs/alpha/entries`, () => HttpResponse.json({ hasMore: false, offset: 0, limit: 200, items: [
      { id: 'e1' }, { id: 'e5' }] })));
    const { user } = open('/weblogs/alpha/pages/p2');
    await waitFor(() => { expect(document.querySelectorAll('.strand-part')).toHaveLength(1); });
    expect(document.querySelector('.strand-part')).toHaveAttribute('data-status', 'draft');
    const select = await screen.findByLabelText('Add posts');
    await waitFor(() => { expect(within(select).getAllByRole('option').length).toBeGreaterThan(1); });
    await user.selectOptions(select, 'e5');
    expect(document.querySelectorAll('.strand-part')).toHaveLength(2);
    expect(document.querySelectorAll('.strand-part')[1]).toHaveAttribute('data-status', 'draft');
  });

  it('reports the failure when a new strand saves but its parts cannot be stored, without claiming success', async () => {
    server.use(
      http.get(`${API}/weblogs/alpha/entries`, () => HttpResponse.json({ hasMore: false, offset: 0, limit: 200, items: [
        { id: 'e1', title: 'First post', status: 'PUBLISHED' }] })),
      writing('post', '/weblogs/alpha/pages', () => HttpResponse.json(page({ id: 'p9', strand: false }), { status: 201 })),
      http.put(`${API}/weblogs/alpha/pages/p9/parts`, () => HttpResponse.error()));
    const { user } = open('/weblogs/alpha/pages/new');
    await screen.findByLabelText('Title');
    await user.type(input('page_bean_title'), 'Series');
    await user.click(screen.getByLabelText('This page is a strand'));
    await user.selectOptions(await screen.findByLabelText('Add posts'), 'e1');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.queryByText('Page saved.')).toBeNull();
    expect(writes.map((w) => w.method)).toEqual(['POST']);
  });

  it('goes to the pages list when a create answers without an id', async () => {
    server.use(writing('post', '/weblogs/alpha/pages', () => HttpResponse.json({}, { status: 201 })));
    const { user, router } = open('/weblogs/alpha/pages/new');
    await screen.findByLabelText('Title');
    await user.type(input('page_bean_title'), 'Odd');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => { expect(router.state.location.pathname).not.toBe('/weblogs/alpha/pages/new'); });
    expect(router.state.location.pathname).toBe('/weblogs/alpha/pages/');
  });
});

describe('PageEditor with a stored draft that is not a draft', () => {
  it.each([['a JSON number', '42'], ['not JSON', '{nope']])('leaves the form as loaded when the stored text is %s', async (_name, text) => {
    window.localStorage.setItem(`${APP_DRAFT_PREFIX}jake:alpha:pageEdit:new`, JSON.stringify({ text, at: Date.now() }));
    const { user } = open('/weblogs/alpha/pages/new');
    await user.click(await screen.findByRole('button', { name: 'Restore' }));
    expect(input('page_bean_title')).toHaveValue('');
    expect(input('page_bean_status')).toHaveValue('DRAFT');
  });
});
