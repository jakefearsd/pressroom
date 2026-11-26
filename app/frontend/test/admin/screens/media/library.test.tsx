import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { appRoutes } from '../../../../src/admin/router';
import { adminBoot, renderRoutes } from '../../support';
import { http, HttpResponse } from 'msw';
import { API, file, mediaServer, problem } from './mediaServer';

const { server, state } = mediaServer();
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => {
  state.calls.length = 0;
  state.refusals.clear();
  state.me = { userName: 'jake', screenName: 'Jake', kind: 'PERSON', globalAdmin: false, tokenScope: {},
    weblogs: [{ handle: 'alpha', name: 'Alpha Blog', permission: 'POST' }] };
  state.dirs = [
    { id: 'd-default', name: 'default', isPrivate: false, fileCount: 0 },
    { id: 'd-trips', name: 'trips', isPrivate: false, fileCount: 0 }
  ];
  state.files = [
    file('a', { name: 'a-beach.jpg', altText: 'Waves', length: 5000, tags: ['sea', 'sun'] }),
    file('b', { name: 'b-noalt.png', contentType: 'image/png', altText: '  ', length: 300000 }),
    file('c', { name: 'c-guide.pdf', contentType: 'application/pdf', length: 10 }),
    file('t1', { name: 't1-trip.jpg', directory: 'd-trips' })
  ];
});

const open = (at = '/weblogs/alpha/media') => renderRoutes(appRoutes, at);
const tiles = () => [...document.querySelectorAll('.mediaObject')].map((m) => m.getAttribute('aria-label'));
const listCalls = () => state.calls.filter((c) => c.method === 'GET' && c.path === '/media');

describe('Media library browse', () => {
  it('lists the default folder with the JSP subtitle, tiles, thumbnails and the alt-text marker', async () => {
    open();
    expect(await screen.findByRole('heading', { level: 1, name: 'Media files' })).toHaveClass('pressroom-page-title');
    await screen.findByLabelText('a-beach.jpg', { selector: '.mediaObject' });
    expect(document.querySelector('.subtitle')).toHaveTextContent('Media files in weblog alpha');
    expect(document.querySelector('.subtitle span')).not.toBeNull();
    expect(tiles()).toEqual(['a-beach.jpg', 'b-noalt.png', 'c-guide.pdf']);
    expect(document.querySelector('img[alt="a-beach.jpg"]')).toHaveAttribute('src', 'http://pressroom.test/pressroom/media/a?t=true');
    expect(document.querySelector('.bi-file-earmark')).not.toBeNull();
    const markers = screen.getAllByRole('button', { name: 'no alt text' });
    expect(markers).toHaveLength(1);
    expect(markers[0]).toHaveClass('media-alt-missing');
    expect(listCalls()[0]?.path).toBe('/media');
  });

  it('shows another folder from ?dir=, with its name and tip', async () => {
    open('/weblogs/alpha/media?dir=d-trips');
    await screen.findByLabelText('t1-trip.jpg', { selector: '.mediaObject' });
    expect(document.querySelector('.subtitle')).toHaveTextContent('Folder: trips');
    expect(document.querySelector('.pagetip')).toHaveTextContent('This is a media file subfolder.');
  });

  it('switches folders from the folder menu and keeps the choice in the URL', async () => {
    const { router } = open();
    await screen.findByLabelText('a-beach.jpg', { selector: '.mediaObject' });
    await userEvent.selectOptions(screen.getByLabelText('Folder'), 'trips');
    await screen.findByLabelText('t1-trip.jpg', { selector: '.mediaObject' });
    expect(router.state.location.search).toBe('?dir=d-trips');
  });

  it('sorts through the API and offers no sort control in an empty folder', async () => {
    const { router } = open();
    await screen.findByLabelText('a-beach.jpg', { selector: '.mediaObject' });
    await userEvent.selectOptions(screen.getByLabelText('Sort by'), 'Type');
    await waitFor(() => { expect(router.state.location.search).toBe('?sort=type'); });
    await waitFor(() => { expect(tiles()).toEqual(['c-guide.pdf', 'a-beach.jpg', 'b-noalt.png']); });
    state.files = [];
    open('/weblogs/alpha/media?dir=d-trips');
    await screen.findByText('No photos yet.');
  });

  it('shows the empty state with its call to action in an empty folder', async () => {
    state.files = [];
    open();
    expect(await screen.findByText('No photos yet.')).toBeInTheDocument();
    expect(screen.getByText('Photos you add here become available to every entry.')).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'Add a photo' })).toHaveAttribute('href', '/weblogs/alpha/media/upload?dir=d-default');
    expect(screen.queryByLabelText('Sort by')).toBeNull();
    expect(screen.getByLabelText('Folder')).toBeInTheDocument();
  });

  it('shows a refusal to list in place', async () => {
    state.refusals.set('GET list', problem(500, 'server.internalError', 'The library could not be read.'));
    open();
    expect(await screen.findByText('The library could not be read.', {}, { timeout: 4000 })).toBeInTheDocument();
  });

  it('opens a tile on click and on Enter and Space, and from the alt-text marker', async () => {
    const { router } = open();
    const tile = await screen.findByLabelText('a-beach.jpg', { selector: '.mediaObject' });
    await userEvent.click(tile);
    await waitFor(() => { expect(router.state.location.pathname).toBe('/weblogs/alpha/media/a'); });
    await router.navigate('/weblogs/alpha/media');
    const again = await screen.findByLabelText('a-beach.jpg', { selector: '.mediaObject' });
    again.focus();
    await userEvent.keyboard('{Enter}');
    await waitFor(() => { expect(router.state.location.pathname).toBe('/weblogs/alpha/media/a'); });
    await router.navigate('/weblogs/alpha/media');
    await userEvent.click(await screen.findByRole('button', { name: 'no alt text' }));
    await waitFor(() => { expect(router.state.location.pathname).toBe('/weblogs/alpha/media/b'); });
    await router.navigate('/weblogs/alpha/media');
    (await screen.findByLabelText('c-guide.pdf', { selector: '.mediaObject' })).focus();
    await userEvent.keyboard(' ');
    await userEvent.keyboard('x');
    await waitFor(() => { expect(router.state.location.pathname).toBe('/weblogs/alpha/media/c'); });
  });

  it('lights a tile on hover and focus', async () => {
    open();
    const tile = await screen.findByLabelText('a-beach.jpg', { selector: '.mediaObject' });
    const item = tile.closest('li') as HTMLElement;
    await userEvent.hover(tile);
    expect(item).toHaveClass('highlight');
    await userEvent.unhover(tile);
    expect(item).not.toHaveClass('highlight');
    act(() => { tile.focus(); });
    expect(item).toHaveClass('highlight');
    act(() => { tile.blur(); });
    expect(item).not.toHaveClass('highlight');
  });
});

describe('Media library search', () => {
  const search = async (fill: () => Promise<void>) => {
    await screen.findByLabelText('a-beach.jpg', { selector: '.mediaObject' });
    expect(screen.getByRole('button', { name: 'Search' })).toBeDisabled();
    await fill();
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
  };

  it('searches the whole weblog by name and states the criteria', async () => {
    open();
    await search(async () => { await userEvent.type(screen.getByLabelText('Name'), 'a-'); });
    await waitFor(() => { expect(document.querySelector('.subtitle')).toHaveTextContent('Search results'); });
    await waitFor(() => { expect(tiles()).toEqual(['a-beach.jpg']); });
    expect(document.querySelector('.pagetip')).toHaveTextContent('Found 1 matching results. You searched for all media files');
    expect(document.querySelector('.pagetip b')).toHaveTextContent('1');
    expect(screen.getByText(/Files named like/)).toHaveTextContent('Files named like a-');
    const last = listCalls().at(-1);
    expect(last).toBeDefined();
    expect(screen.queryByLabelText('Sort by')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Delete folder' })).toBeNull();
    expect(document.getElementById('directoryPrivacyCard')).toBeNull();
    expect(screen.queryByLabelText('Folder name')).toBeNull();
  });

  it('filters by type through the API', async () => {
    open();
    await search(async () => { await userEvent.selectOptions(screen.getByLabelText('Type'), 'Image'); });
    await waitFor(() => { expect(tiles()).toEqual(['a-beach.jpg', 'b-noalt.png', 't1-trip.jpg']); });
    expect(screen.getByText(/Files of type/)).toHaveTextContent('Files of type Image');
  });

  it('filters by size and by tags on the server, so a page holds the matches', async () => {
    open();
    await search(async () => {
      await userEvent.type(screen.getByLabelText('Larger than', { selector: 'input' }), '2');
      await userEvent.selectOptions(screen.getByLabelText('Larger than', { selector: 'select' }), 'KB');
    });
    await waitFor(() => { expect(tiles()).toEqual(['a-beach.jpg', 'b-noalt.png']); });
    expect(screen.getByText(/^size > 2 KB$/)).toBeInTheDocument();
    expect(listCalls().at(-1)?.search).toContain('largerThan=2048');
    await userEvent.type(screen.getByLabelText('Tags'), 'Sea, SUN');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => { expect(tiles()).toEqual(['a-beach.jpg']); });
    const last = new URLSearchParams(listCalls().at(-1)?.search);
    expect(last.getAll('tags')).toEqual(['sea', 'sun']);
    expect(last.get('largerThan')).toBe('2048');
    expect(screen.getByText(/Files with tags/)).toHaveTextContent('Files with tags Sea, SUN');
  });

  it('counts the size unit: megabytes and bytes reach the API as bytes', async () => {
    open('/weblogs/alpha/media?size=1&unit=mb');
    await screen.findByText(/^size > 1 MB$/);
    await waitFor(() => { expect(listCalls().at(-1)?.search).toContain('largerThan=1048576'); });
    open('/weblogs/alpha/media?size=300&unit=bytes');
    await waitFor(() => { expect(listCalls().at(-1)?.search).toContain('largerThan=300'); });
  });

  it('states an empty search and its way back, and Reset clears it', async () => {
    open();
    await search(async () => { await userEvent.type(screen.getByLabelText('Name'), 'zzz'); });
    expect(await screen.findByText('Nothing matched.')).toBeInTheDocument();
    expect(screen.getByText('No matching results found. Try widening your search.')).toBeInTheDocument();
    expect(document.getElementById('mediaFileViewForm')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Browse all files' }));
    await screen.findByLabelText('a-beach.jpg', { selector: '.mediaObject' });
    await userEvent.type(screen.getByLabelText('Name'), 'a-');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Reset' }));
    await waitFor(() => { expect(tiles()).toHaveLength(3); });
    expect(screen.queryByRole('button', { name: 'Reset' })).toBeNull();
  });

  it.each([
    ['video', '', 'Video'],
    ['audio', '&size=1&unit=mb', 'Audio'],
    ['other', '&size=1&unit=bytes', 'Others']
  ])('shows the %s type label in the criteria', async (type, extra, label) => {
    open(`/weblogs/alpha/media?type=${type}${extra}`);
    expect(await screen.findByText(/Files of type/)).toHaveTextContent(label);
    if (extra !== '') {
      expect(screen.getByText(/^size > 1 /)).toHaveTextContent(extra.includes('mb') ? 'MB' : 'Bytes');
    }
  });
});

describe('Media library bulk actions', () => {
  const select = async (...names: string[]) => {
    await screen.findByLabelText('a-beach.jpg', { selector: '.mediaObject' });
    for (const name of names) {
      await userEvent.click(screen.getByRole('checkbox', { name }));
    }
  };

  it('hides the selection bar until something is checked, then counts', async () => {
    open();
    await select();
    const bar = document.querySelector('.selection-bar') as HTMLElement;
    expect(bar).toHaveAttribute('hidden');
    expect(screen.getByRole('button', { name: 'Delete selected', hidden: true })).toBeDisabled();
    await userEvent.click(screen.getByRole('checkbox', { name: 'a-beach.jpg' }));
    expect(bar).not.toHaveAttribute('hidden');
    expect(bar.querySelector('.selection-count')).toHaveTextContent('1 selected');
    await userEvent.click(screen.getByRole('checkbox', { name: 'a-beach.jpg' }));
    expect(bar).toHaveAttribute('hidden');
  });

  it('Toggle selects all, then none', async () => {
    open();
    await select();
    await userEvent.click(document.getElementById('toggleButton') as HTMLElement);
    expect(document.querySelector('.selection-count')).toHaveTextContent('3 selected');
    await userEvent.click(document.getElementById('toggleButton') as HTMLElement);
    expect(document.querySelector('.selection-count')).toHaveTextContent('0 selected');
  });

  it('deletes the selection after a confirmation, one call per file', async () => {
    open();
    await select('a-beach.jpg', 'c-guide.pdf');
    await userEvent.click(screen.getByRole('button', { name: 'Delete selected' }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('Delete selected media files?');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(state.calls.some((c) => c.method === 'DELETE')).toBe(false);
    await userEvent.click(screen.getByRole('button', { name: 'Delete selected' }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Delete selected' }));
    expect(await screen.findByText('Media deleted.')).toBeInTheDocument();
    expect(state.calls.filter((c) => c.method === 'DELETE').map((c) => c.path).sort()).toEqual(['/media/a', '/media/c']);
    expect(state.calls.filter((c) => c.method === 'DELETE').every((c) => c.csrf === 'tok')).toBe(true);
    await waitFor(() => { expect(tiles()).toEqual(['b-noalt.png']); });
  });

  it('reports a file that could not be deleted by name, keeps it selected, and still deletes the rest', async () => {
    state.refusals.set('DELETE file', problem(403, 'auth.noPermission', 'You may not delete this.'));
    open();
    await select('a-beach.jpg');
    await userEvent.click(screen.getByRole('button', { name: 'Delete selected' }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Delete selected' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Error deleting the selected media files.');
    expect(alert).toHaveTextContent('a-beach.jpg: You may not delete this.');
    expect(screen.queryByText('Media deleted.')).toBeNull();
    expect(screen.getByRole('checkbox', { name: 'a-beach.jpg' })).toBeChecked();
  });

  it('moves the selection to the chosen folder, one PATCH each', async () => {
    open();
    await select('a-beach.jpg', 'b-noalt.png');
    await userEvent.selectOptions(screen.getByLabelText('Move selected to', { selector: 'select' }), 'trips');
    await userEvent.click(screen.getByRole('button', { name: 'Move selected to' }));
    expect(await screen.findByText('Media moved.')).toBeInTheDocument();
    const patches = state.calls.filter((c) => c.method === 'PATCH');
    expect(patches.map((c) => [c.path, c.body])).toEqual(expect.arrayContaining([
      ['/media/a', { directoryId: 'd-trips' }], ['/media/b', { directoryId: 'd-trips' }]]));
    await waitFor(() => { expect(tiles()).toEqual(['c-guide.pdf']); });
  });

  it('reports a failed move in place', async () => {
    state.refusals.set('PATCH file', problem(409, 'media.nameInUse', 'That name is taken.', [{ field: 'name', message: 'taken' }]));
    open();
    await select('a-beach.jpg');
    await userEvent.click(screen.getByRole('button', { name: 'Move selected to' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Error moving media files.');
    expect(alert).toHaveTextContent('a-beach.jpg: That name is taken.');
  });

  it('starts an entry from the selection by opening the entry editor with the files, no form post', async () => {
    const { router } = open();
    await select('a-beach.jpg', 'b-noalt.png');
    expect(document.getElementById('createPostForm')).toBeNull();
    const button = screen.getByRole('button', { name: 'New entry with selected' });
    expect(button).toHaveAttribute('type', 'button');
    await userEvent.click(button);
    await waitFor(() => { expect(router.state.location.pathname).toBe('/weblogs/alpha/entries/new'); });
    expect(new URLSearchParams(router.state.location.search).getAll('mediaId')).toEqual(['a', 'b']);
    expect(state.calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('offers no new entry from the site space, which has no posts', async () => {
    state.me = { userName: 'jake', screenName: 'Jake', kind: 'PERSON', globalAdmin: false, tokenScope: {},
      weblogs: [{ handle: 'site-space', name: 'Site', permission: 'POST' }] };
    server.use(
      http.get(`${API}/weblogs/site-space`, () => HttpResponse.json({ handle: 'site-space', name: 'Site', active: true, timeZone: 'UTC' })),
      http.get(`${API}/weblogs/site-space/media/directories`, () => HttpResponse.json(state.dirs)),
      http.get(`${API}/weblogs/site-space/media`, () => HttpResponse.json({ items: state.files, offset: 0, limit: 3, hasMore: false })));
    open('/weblogs/site-space/media');
    await screen.findByLabelText('a-beach.jpg', { selector: '.mediaObject' });
    await userEvent.click(screen.getByLabelText('a-beach.jpg', { selector: 'input' }));
    expect(document.getElementById('moveButton')).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'New entry with selected' })).toBeNull();
  });
});

describe('Media library folders', () => {
  it('creates a folder, shows it and says so', async () => {
    const { router } = open();
    await screen.findByLabelText('a-beach.jpg', { selector: '.mediaObject' });
    const button = screen.getByRole('button', { name: 'Create' });
    expect(button).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Folder name'), 'Berlin');
    await userEvent.click(button);
    expect(await screen.findByText('Folder "Berlin" created.')).toBeInTheDocument();
    await waitFor(() => { expect(router.state.location.search).toBe('?dir=d-Berlin'); });
    expect(state.calls.find((c) => c.method === 'POST' && c.path === '/media/directories')?.body).toEqual({ name: 'Berlin' });
    await screen.findByText('No photos yet.');
  });

  it('names an existing folder as the JSP did and marks the field', async () => {
    state.refusals.set('POST dirs', problem(409, 'directory.nameInUse', 'exists', [{ field: 'name', message: 'Already used.' }]));
    open();
    await screen.findByLabelText('a-beach.jpg', { selector: '.mediaObject' });
    await userEvent.type(screen.getByLabelText('Folder name'), 'trips');
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));
    expect(await screen.findByText('A folder named "trips" already exists. Choose another name.')).toBeInTheDocument();
    const field = screen.getByLabelText('Folder name');
    expect(field).toHaveClass('is-invalid');
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(field).toHaveFocus();
  });

  it('shows any other refusal to create, with the server detail', async () => {
    state.refusals.set('POST dirs', problem(400, 'directory.nameReserved', 'A folder cannot be named default.', [{ field: 'name', message: 'reserved' }]));
    open();
    await screen.findByLabelText('a-beach.jpg', { selector: '.mediaObject' });
    await userEvent.type(screen.getByLabelText('Folder name'), 'default');
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('A folder cannot be named default.');
  });

  it('offers no Delete folder on the default folder, even when it is empty', async () => {
    state.files = [];
    open();
    await screen.findByText('No photos yet.');
    expect(screen.queryByRole('button', { name: 'Delete folder' })).toBeNull();
  });

  it('offers no Delete folder on a folder that still holds files (the API refuses it)', async () => {
    open('/weblogs/alpha/media?dir=d-trips');
    await screen.findByLabelText('t1-trip.jpg', { selector: '.mediaObject' });
    expect(screen.queryByRole('button', { name: 'Delete folder' })).toBeNull();
  });

  it('offers Delete folder on an empty folder once its last file is deleted, and deletes only the folder', async () => {
    const { router } = open('/weblogs/alpha/media?dir=d-trips');
    await screen.findByLabelText('t1-trip.jpg', { selector: '.mediaObject' });
    await userEvent.click(screen.getByLabelText('t1-trip.jpg', { selector: 'input' }));
    await userEvent.click(screen.getByRole('button', { name: 'Delete selected' }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Delete selected' }));
    await screen.findByText('No photos yet.');
    state.calls.length = 0;
    await userEvent.click(await screen.findByRole('button', { name: 'Delete folder' }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('Delete entire folder including all its media files?');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(state.calls.some((c) => c.method === 'DELETE')).toBe(false);
    await userEvent.click(screen.getByRole('button', { name: 'Delete folder' }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Delete folder' }));
    expect(await screen.findByText('Folder deleted.')).toBeInTheDocument();
    expect(state.calls.filter((c) => c.method === 'DELETE').map((c) => c.path)).toEqual(['/media/directories/d-trips']);
    expect(router.state.location.search).toBe('');
  });

  it('shows the server refusal when the folder itself will not delete', async () => {
    state.refusals.set('DELETE dir', problem(409, 'directory.notEmpty', 'The directory still holds files.'));
    state.files = state.files.filter((f) => f.directory !== 'd-trips');
    open('/weblogs/alpha/media?dir=d-trips');
    await screen.findByText('No photos yet.');
    await userEvent.click(await screen.findByRole('button', { name: 'Delete folder' }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Delete folder' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The folder could not be deleted.');
    expect(alert).toHaveTextContent('The directory still holds files.');
  });

  it('sets a folder private by stating the value, and says so', async () => {
    open('/weblogs/alpha/media?dir=d-trips');
    await screen.findByLabelText('t1-trip.jpg', { selector: '.mediaObject' });
    const box = screen.getByRole('checkbox', { name: /Private directory/ });
    expect(box).not.toBeChecked();
    await userEvent.click(box);
    expect(await screen.findByText('Directory privacy updated')).toBeInTheDocument();
    expect(state.calls.find((c) => c.method === 'PATCH' && c.path === '/media/directories/d-trips')?.body).toEqual({ isPrivate: true });
    await waitFor(() => { expect(screen.getByRole('checkbox', { name: /Private directory/ })).toBeChecked(); });
    expect(document.querySelector('#directoryPrivacyCard .card-header')).toHaveTextContent('Privacy');
    await userEvent.click(screen.getByRole('checkbox', { name: /Private directory/ }));
    await waitFor(() => { expect(state.calls.filter((c) => c.method === 'PATCH').at(-1)?.body).toEqual({ isPrivate: false }); });
  });

  it('shows a refused privacy change in place', async () => {
    state.refusals.set('PATCH dir', problem(404, 'directory.notFound', 'No such folder.'));
    open('/weblogs/alpha/media?dir=d-trips');
    await screen.findByLabelText('t1-trip.jpg', { selector: '.mediaObject' });
    await userEvent.click(screen.getByRole('checkbox', { name: /Private directory/ }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not update directory privacy');
    expect(alert).toHaveTextContent('No such folder.');
  });
});

describe('Media library roles', () => {
  it('lets a post-level member manage: upload link, new folder, privacy', async () => {
    open();
    await screen.findByLabelText('a-beach.jpg', { selector: '.mediaObject' });
    expect(screen.getByRole('link', { name: /Add media files/ })).toHaveAttribute('href', '/weblogs/alpha/media/upload?dir=d-default');
    expect(screen.getByLabelText('Folder name')).toBeInTheDocument();
    expect(document.getElementById('directoryPrivacyCard')).not.toBeNull();
  });

  it('lets a site admin manage a weblog they are not a member of', async () => {
    state.me = { userName: 'root', screenName: 'Root', kind: 'PERSON', globalAdmin: true, tokenScope: {}, weblogs: [] };
    renderRoutes(appRoutes, '/weblogs/alpha/media', { boot: adminBoot });
    await screen.findByLabelText('a-beach.jpg', { selector: '.mediaObject' });
    expect(screen.getByRole('link', { name: /Add media files/ })).toBeInTheDocument();
  });

  it('shows a contributor the files and none of the controls', async () => {
    state.me = { userName: 'jake', screenName: 'Jake', kind: 'PERSON', globalAdmin: false, tokenScope: {},
      weblogs: [{ handle: 'alpha', name: 'Alpha Blog', permission: 'EDIT_DRAFT' }] };
    open();
    await screen.findByLabelText('a-beach.jpg', { selector: '.mediaObject' });
    await waitFor(() => { expect(screen.queryByRole('link', { name: /Add media files/ })).toBeNull(); });
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(document.querySelector('.selection-bar')).toBeNull();
    expect(document.querySelector('.mediaObject')).not.toHaveAttribute('role');
    expect(screen.queryByRole('button', { name: 'no alt text' })).toBeNull();
    expect(screen.queryByLabelText('Folder name')).toBeNull();
    expect(document.getElementById('directoryPrivacyCard')).toBeNull();
    expect(screen.getByLabelText('Name')).toBeInTheDocument();
  });

  it('shows a contributor no call to action in an empty folder', async () => {
    state.me = { userName: 'jake', screenName: 'Jake', kind: 'PERSON', globalAdmin: false, tokenScope: {},
      weblogs: [{ handle: 'alpha', name: 'Alpha Blog', permission: 'EDIT_DRAFT' }] };
    state.files = [];
    open();
    await screen.findByText('No photos yet.');
    expect(screen.queryByRole('link', { name: 'Add a photo' })).toBeNull();
  });
});
