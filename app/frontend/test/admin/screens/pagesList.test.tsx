import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { appRoutes } from '../../../src/admin/router';
import { editorBoot, renderRoutes } from '../support';
import { API, makeServer, page, problem, writes, writing } from './pagesSupport';

const { server, start } = makeServer();
start();

const open = async (at = '/weblogs/alpha/pages') => {
  const view = renderRoutes(appRoutes, at, { boot: editorBoot });
  return { ...view, user: userEvent.setup() };
};
const row = (slug: string) => document.querySelector(`tr[data-page-slug="${slug}"]`) as HTMLElement;

describe('PagesList', () => {
  it('lists the pages as Pages.jsp does: slug links only for published pages, home marker, nav check, status', async () => {
    await open();
    expect(await screen.findByRole('heading', { level: 1, name: 'Pages' })).toBeInTheDocument();
    expect(document.querySelector('p.subtitle')).toHaveTextContent('Static pages for alpha');
    expect(document.querySelector('p.subtitle b')).toHaveTextContent('alpha');
    await screen.findByText('Welcome');

    const about = row('about');
    expect(within(about).getByRole('link', { name: '/about' }))
      .toHaveAttribute('href', 'https://blog.example.com/alpha/about');
    expect(within(about).getByRole('link', { name: '/about' })).toHaveAttribute('target', '_blank');
    expect(about.querySelectorAll('td')[3]).toHaveTextContent('✓');
    expect(about.querySelector('.status-pill')).toHaveClass('status-published');

    // The page shown at the blog's root is marked and linked at the blog's address.
    expect(within(row('home')).getByText('Home page')).toHaveClass('page-home-marker');
    expect(within(row('home')).getByRole('link', { name: '/home' })).toHaveAttribute('href', 'https://blog.example.com/alpha/');

    // A draft is served nowhere, so its slug is text; its title is text, never markup.
    const draft = row('draft-one');
    expect(within(draft).queryByRole('link', { name: '/draft-one' })).not.toBeInTheDocument();
    expect(draft).toHaveTextContent('/draft-one');
    expect(within(draft).getByRole('link', { name: 'Draft <b>one</b>' })).toHaveAttribute('href', '/weblogs/alpha/pages/p3');
    expect(draft.querySelector('b')).toBeNull();
    expect(screen.getByRole('link', { name: 'New page' })).toHaveAttribute('href', '/weblogs/alpha/pages/new');
    expect(document.querySelector('.pages-trash-link')).toBeNull();
    expect(document.getElementById('pagesOrderSave')).toHaveAttribute('hidden');
  });

  it('wraps the list in a marker the browser route sweep waits for, in both states', async () => {
    await open();
    const row = (await screen.findByText('Welcome')).closest('tr') as HTMLElement;
    expect(document.getElementById('pages-list-marker')).toContainElement(row);
  });

  it('keeps the marker around the invitation when there are no pages', async () => {
    server.use(http.get(`${API}/weblogs/:handle/pages`, () => HttpResponse.json([])));
    await open();
    await screen.findByText('No pages yet.');
    expect(document.getElementById('pages-list-marker')?.querySelector('.empty-state')).not.toBeNull();
  });

  it('never asks the API for the site space as a weblog (it answers "No such weblog")', async () => {
    server.use(http.get(`${API}/weblogs/site-space`, () => problem(404, 'weblog.notFound', 'No such weblog.')));
    await open('/weblogs/site-space/pages');
    await screen.findByText('Welcome');
    expect(screen.queryByText('No such weblog.')).toBeNull();
  });

  it('says "Site pages" for the site space', async () => {
    await open('/weblogs/site-space/pages');
    await screen.findByText('Welcome');
    expect(document.querySelector('p.subtitle')).toHaveTextContent('Site pages');
  });

  it('shows the invitation, with the way in, when there are no pages, and the trash link when pages are trashed', async () => {
    server.use(http.get(`${API}/weblogs/:handle/pages`, ({ request }) =>
      HttpResponse.json(new URL(request.url).searchParams.get('status') === 'TRASHED' ? [page({ id: 'x' }), page({ id: 'y' })] : [])));
    await open();
    expect(await screen.findByText('No pages yet.')).toHaveClass('empty-state-title');
    expect(screen.getByText('Your first page rounds out the site.')).toBeInTheDocument();
    expect(document.querySelector('.empty-state')?.querySelector('a.btn-primary')).toHaveAttribute('href', '/weblogs/alpha/pages/new');
    expect(await screen.findByRole('link', { name: 'Trash (2)' })).toHaveAttribute('href', '/weblogs/alpha/pages/trash');
  });

  it('shows the refusal in place when the list is refused', async () => {
    server.use(http.get(`${API}/weblogs/:handle/pages`, () => problem(403, 'forbidden', 'You may not see these pages.')));
    await open();
    expect(await screen.findByText('You may not see these pages.')).toBeInTheDocument();
    expect(document.getElementById('errors')).toHaveAttribute('role', 'alert');
  });

  describe('reordering', () => {
    it('moves rows, shows Save order only once the order differs, and numbers the rows by position', async () => {
      const { user } = await open();
      await screen.findByText('Welcome');
      const order = () => [...document.querySelectorAll('#pagesList tr')].map((r) => r.getAttribute('data-page-slug'));
      expect(order()).toEqual(['home', 'about', 'draft-one']);
      await user.click(within(row('about')).getByRole('button', { name: 'Move up: About us' }));
      expect(order()).toEqual(['about', 'home', 'draft-one']);
      expect(document.getElementById('pagesOrderSave')).not.toHaveAttribute('hidden');
      expect([...document.querySelectorAll('.page-nav-order')].map((c) => c.textContent)).toEqual(['0', '1', '2']);
      // Moving back restores the loaded order: nothing to save.
      await user.click(within(row('about')).getByRole('button', { name: 'Move down: About us' }));
      expect(document.getElementById('pagesOrderSave')).toHaveAttribute('hidden');
      // The ends do not move.
      await user.click(within(row('draft-one')).getByRole('button', { name: 'Move down: Draft <b>one</b>' }));
      expect(order()).toEqual(['home', 'about', 'draft-one']);
    });

    it('saves with one PATCH per moved page, then says so', async () => {
      server.use(writing('patch', '/weblogs/alpha/pages/:id', (request) => HttpResponse.json(page({ id: request.url.split('/').pop() }))));
      const { user } = await open();
      await screen.findByText('Welcome');
      await user.click(within(row('about')).getByRole('button', { name: 'Move up: About us' }));
      await user.click(screen.getByRole('button', { name: 'Save order' }));
      expect(await screen.findByText('Page order saved.')).toBeInTheDocument();
      // home 0 -> 1 and about 1 -> 0 moved; draft-one stayed at 2.
      expect([...writes].sort((a, b) => a.url.localeCompare(b.url))).toEqual([
        { method: 'PATCH', url: '/weblogs/alpha/pages/p1', body: { navOrder: 1 } },
        { method: 'PATCH', url: '/weblogs/alpha/pages/p2', body: { navOrder: 0 } }
      ]);
    });

    it('drags a row onto another', async () => {
      await open();
      await screen.findByText('Welcome');
      const { fireEvent } = await import('@testing-library/react');
      fireEvent.dragStart(row('draft-one'));
      fireEvent.dragOver(row('home'));
      fireEvent.drop(row('home'));
      expect([...document.querySelectorAll('#pagesList tr')].map((r) => r.getAttribute('data-page-slug')))
        .toEqual(['draft-one', 'home', 'about']);
      fireEvent.drop(row('about'));
      expect(document.getElementById('pagesOrderSave')).not.toHaveAttribute('hidden');
    });

    it('reports each page that could not be moved, keeps the order on screen, and does not claim success', async () => {
      server.use(writing('patch', '/weblogs/alpha/pages/:id', (request) =>
        request.url.endsWith('/p2') ? problem(409, 'page.changed', 'That page changed.')
          : HttpResponse.json(page({ id: 'p1' }))));
      const { user } = await open();
      await screen.findByText('Welcome');
      await user.click(within(row('about')).getByRole('button', { name: 'Move up: About us' }));
      await user.click(screen.getByRole('button', { name: 'Save order' }));
      const errors = await screen.findByRole('alert');
      expect(errors).toHaveTextContent('1 page could not be moved.');
      expect(errors).toHaveTextContent('/about: That page changed.');
      expect(screen.queryByText('Page order saved.')).not.toBeInTheDocument();
      expect(writes).toHaveLength(2);
    });
  });

  describe('moving to the trash', () => {
    it('asks first, naming the page, and what a strand and the home page lose; then trashes it', async () => {
      server.use(
        http.get(`${API}/weblogs/:handle/pages`, ({ request }) => HttpResponse.json(
          new URL(request.url).searchParams.get('status') === 'TRASHED' ? [] : [
            page({ id: 'p1', slug: 'home', title: "Maiia's home", strand: true, parts: [{ id: 'e1' }, { id: 'e2' }] })])),
        writing('delete', '/weblogs/alpha/pages/p1', () => new HttpResponse(null, { status: 204 })));
      const { user } = await open();
      await screen.findByText("Maiia's home");
      await user.click(screen.getByRole('button', { name: "Move to the trash: Maiia's home" }));
      const dialog = screen.getByRole('dialog');
      expect(dialog).toHaveTextContent("Move this page to the trash: 'Maiia's home'?");
      expect(dialog).toHaveTextContent('2 posts stay attached. The strand bar disappears until the page is restored and published.');
      expect(dialog).toHaveTextContent('This is the home page of the blog; the post list returns to the root.');
      expect(writes).toEqual([]);
      await user.click(within(dialog).getByRole('button', { name: 'Move to the trash' }));
      expect(await screen.findByText('Page "home" moved to the trash.')).toBeInTheDocument();
      expect(writes).toEqual([{ method: 'DELETE', url: '/weblogs/alpha/pages/p1', body: null }]);
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('does nothing on cancel', async () => {
      const { user } = await open();
      await screen.findByText('Welcome');
      await user.click(screen.getByRole('button', { name: 'Move to the trash: About us' }));
      await user.click(screen.getByRole('button', { name: 'Cancel' }));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(writes).toEqual([]);
    });

    it('shows a refusal in place and keeps the page', async () => {
      server.use(writing('delete', '/weblogs/alpha/pages/p2', () => problem(409, 'page.alreadyTrashed', 'Page is already in the trash.')));
      const { user } = await open();
      await screen.findByText('Welcome');
      await user.click(screen.getByRole('button', { name: 'Move to the trash: About us' }));
      await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Move to the trash' }));
      expect(await screen.findByText('Page is already in the trash.')).toBeInTheDocument();
      await waitFor(() => { expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); });
      expect(row('about')).toBeInTheDocument();
    });
  });
});

describe('PagesList with thin page records', () => {
  const thin = () => server.use(
    http.get(`${API}/weblogs/:handle/pages`, ({ request }) => HttpResponse.json(
      new URL(request.url).searchParams.get('status') === 'TRASHED' ? [] : [{ id: 'q1' }])),
    writing('delete', '/weblogs/alpha/pages/q1', () => new HttpResponse(null, { status: 204 })));

  it('lists a page that has no slug, title or status as an unlinked draft at the root', async () => {
    thin();
    await open();
    const cell = await screen.findByText('/');
    expect(cell.closest('a')).toBeNull();
    expect(within(cell.closest('tr') as HTMLElement).getByText('Draft')).toBeInTheDocument();
  });

  it('asks about it without a name and trashes it, naming the empty address', async () => {
    thin();
    const { user } = await open();
    await user.click(await screen.findByRole('button', { name: /Move to the trash/ }));
    expect(screen.getByRole('dialog')).toHaveTextContent("Move this page to the trash: ''?");
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Move to the trash' }));
    expect(await screen.findByText('Page "" moved to the trash.')).toBeInTheDocument();
  });

  it('names a page that could not be moved by its address, and falls back to an empty one', async () => {
    server.use(
      http.get(`${API}/weblogs/:handle/pages`, ({ request }) => HttpResponse.json(
        new URL(request.url).searchParams.get('status') === 'TRASHED' ? [] : [
          page({ id: 'p1', slug: 'one', navOrder: 0 }), { id: 'q1', navOrder: 1 }])),
      http.patch(`${API}/weblogs/:handle/pages/:id`, () => problem(409, 'page.changed', 'Changed meanwhile.')));
    const { user } = await open();
    await screen.findByText('/one');
    await user.click(screen.getAllByRole('button', { name: /Move up/ })[1] as HTMLElement);
    await user.click(await screen.findByRole('button', { name: 'Save order' }));
    expect(await screen.findByText('/: Changed meanwhile.')).toBeInTheDocument();
    expect(screen.getByText('/one: Changed meanwhile.')).toBeInTheDocument();
  });

  it('links a published page that has no slug to the address the server gave', async () => {
    server.use(http.get(`${API}/weblogs/:handle/pages`, ({ request }) => HttpResponse.json(
      new URL(request.url).searchParams.get('status') === 'TRASHED' ? [] : [
        { id: 'q2', status: 'PUBLISHED', absoluteUrl: 'https://blog.example.com/alpha/' }])));
    await open();
    expect((await screen.findByText('/')).closest('a')).toHaveAttribute('href', 'https://blog.example.com/alpha/');
  });
});
