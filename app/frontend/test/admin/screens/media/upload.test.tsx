import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { appRoutes } from '../../../../src/admin/router';
import { renderRoutes } from '../../support';
import { API, file, mediaServer, problem } from './mediaServer';

const { server, state } = mediaServer();
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => {
  state.calls.length = 0;
  state.refusals.clear();
  state.uploadStatus = 'created';
  state.files = [];
  state.dirs = [
    { id: 'd-default', name: 'default', isPrivate: false, fileCount: 0 },
    { id: 'd-trips', name: 'trips', isPrivate: false, fileCount: 0 }
  ];
});

let last: ReturnType<typeof renderRoutes>;
const open = (at = '/weblogs/alpha/media/upload') => (last = renderRoutes(appRoutes, at));
const router = () => last.router;
const png = (name: string, bytes = 10) => new File([new Uint8Array(bytes)], name, { type: 'image/png' });
const choose = async (...files: File[]) => {
  await userEvent.upload(await screen.findByLabelText('Choose media files to upload', { selector: 'input' }), files);
};
const uploads = () => state.calls.filter((c) => c.method === 'POST' && c.path === '/media');

describe('Media upload form', () => {
  it('shows the JSP form: folder first, the drop zone, collapsed details, Upload disabled', async () => {
    open();
    expect(await screen.findByRole('heading', { level: 1, name: 'Add new media file' })).toBeInTheDocument();
    expect(document.querySelector('.subtitle')).toHaveTextContent('Add new media file');
    expect(document.querySelector('.pagetip')).toHaveTextContent('Use this page to upload new media files.');
    await waitFor(() => { expect(screen.getByLabelText('Folder')).toHaveValue('d-default'); });
    expect(document.getElementById('mediaDropZone')).toHaveClass('media-dropzone');
    expect(screen.getByText('Drag files here, or click to choose')).toHaveClass('media-dropzone-hint');
    expect(document.querySelector('details.editor-details')).not.toHaveAttribute('open');
    expect(screen.getByRole('button', { name: 'Upload' })).toBeDisabled();
    expect(document.getElementById('uploadedFiles')).toHaveAttribute('multiple');
    expect(screen.getByRole('link', { name: 'Cancel' })).toHaveAttribute('href', '/weblogs/alpha/media?dir=d-default');
  });

  it('preselects the folder from ?dir= and lets the author change it', async () => {
    open('/weblogs/alpha/media/upload?dir=d-trips');
    await waitFor(() => { expect(screen.getByLabelText('Folder')).toHaveValue('d-trips'); });
    await userEvent.selectOptions(screen.getByLabelText('Folder'), 'default');
    expect(screen.getByLabelText('Folder')).toHaveValue('d-default');
    expect(screen.getByRole('link', { name: 'Cancel' })).toHaveAttribute('href', '/weblogs/alpha/media?dir=d-default');
  });

  it('lists the chosen files with sizes and a one-file or many-files summary', async () => {
    open();
    await choose(png('one.png', 1536));
    const list = document.getElementById('mediaChosenFiles') as HTMLElement;
    expect(list).toHaveTextContent('one.png (1.5 KB)');
    expect(list.querySelector('.media-chosen-total')).toHaveTextContent('1 file, 1.5 KB total');
    expect(screen.getByRole('button', { name: 'Upload' })).toBeEnabled();
    await choose(png('a.png', 500), png('b.png', 5 * 1024 * 1024));
    expect(list.querySelector('.media-chosen-total')).toHaveTextContent('2 files, 5.0 MB total');
    expect(list).toHaveTextContent('a.png (500 B)');
  });

  it('takes dropped files and shows the drag highlight without flicker', async () => {
    open();
    const zone = await waitFor(() => document.getElementById('mediaDropZone') as HTMLElement);
    fireEvent.dragEnter(zone);
    fireEvent.dragEnter(zone);
    fireEvent.dragOver(zone);
    expect(zone).toHaveClass('media-dropzone-active');
    fireEvent.dragLeave(zone);
    expect(zone).toHaveClass('media-dropzone-active');
    fireEvent.dragLeave(zone);
    expect(zone).not.toHaveClass('media-dropzone-active');
    fireEvent.dragEnter(zone);
    fireEvent.drop(zone, { dataTransfer: { files: [png('dropped.png')] } });
    expect(zone).not.toHaveClass('media-dropzone-active');
    expect(document.getElementById('mediaChosenFiles')).toHaveTextContent('dropped.png');
    fireEvent.dragLeave(zone);
  });

  it('opens the file picker from a click anywhere in the zone, but not twice from the input', async () => {
    open();
    const zone = await waitFor(() => document.getElementById('mediaDropZone') as HTMLElement);
    const input = document.getElementById('uploadedFiles') as HTMLInputElement;
    const click = vi.spyOn(input, 'click');
    fireEvent.click(zone.querySelector('.media-dropzone-hint') as HTMLElement);
    expect(click).toHaveBeenCalledTimes(1);
    fireEvent.click(input);
    expect(click).toHaveBeenCalledTimes(1);
  });

  it('opens the details when toggled and keeps what is typed', async () => {
    open();
    const details = await waitFor(() => document.querySelector('details.editor-details') as HTMLDetailsElement);
    await userEvent.click(screen.getByText('Details'));
    expect(details.open).toBe(true);
    expect(screen.getByLabelText('Description')).toHaveAttribute('maxlength', '255');
    expect(screen.getByLabelText('Copyright statement')).toHaveAttribute('maxlength', '1023');
  });
});

describe('Media upload', () => {
  it('uploads each file in its own multipart request with the CSRF header, then offers the next steps', async () => {
    const { router } = open('/weblogs/alpha/media/upload?dir=d-trips');
    await choose(png('one.png'), png('two.png'));
    await userEvent.click(screen.getByRole('button', { name: 'Upload' }));
    expect(await screen.findByText('Your upload was successful')).toBeInTheDocument();
    expect(uploads()).toHaveLength(2);
    expect(uploads().every((c) => c.csrf === 'tok' && c.body === 'multipart')).toBe(true);
    expect(screen.getByText('Select images')).toHaveClass('section-head');
    expect(screen.getAllByRole('checkbox')).toHaveLength(2);
    expect(screen.getByRole('link', { name: 'Done' })).toHaveAttribute('href', '/weblogs/alpha/media?dir=d-trips');
    expect(router.state.location.pathname).toBe('/weblogs/alpha/media/upload');
  });

  it('sends the folder as a form part', async () => {
    let sent: string | null = null;
    server.use(http.post(`${API}/weblogs/alpha/media`, async ({ request }) => {
      const body = await request.text();
      sent = /name="directoryId"\r?\n\r?\n([^\r\n]*)/.exec(body)?.[1] ?? null;
      const made = file('up1');
      return HttpResponse.json({ created: 1, failed: 0, results: [{ status: 'created', file: made }] }, { status: 201 });
    }));
    open('/weblogs/alpha/media/upload?dir=d-trips');
    await choose(png('one.png'));
    await userEvent.click(screen.getByRole('button', { name: 'Upload' }));
    await screen.findByText('Your upload was successful');
    expect(sent).toBe('d-trips');
  });

  it('applies the shared description, copyright and tags to every uploaded file', async () => {
    open();
    await choose(png('one.png'), png('two.png'));
    await userEvent.click(screen.getByText('Details'));
    await userEvent.type(screen.getByLabelText('Description'), '  Trip  ');
    await userEvent.type(screen.getByLabelText('Copyright statement'), '(c)');
    await userEvent.type(screen.getByLabelText('Tags (space separated)'), 'a, b');
    await userEvent.click(screen.getByRole('button', { name: 'Upload' }));
    await screen.findByText('Your upload was successful');
    const patches = state.calls.filter((c) => c.method === 'PATCH');
    expect(patches).toHaveLength(2);
    expect(patches.every((c) => JSON.stringify(c.body) === JSON.stringify({ description: 'Trip', copyrightText: '(c)', tags: ['a', 'b'] }))).toBe(true);
  });

  it('applies only the shared field that was filled', async () => {
    open();
    await choose(png('one.png'));
    await userEvent.click(screen.getByText('Details'));
    await userEvent.type(screen.getByLabelText('Tags (space separated)'), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Upload' }));
    await screen.findByText('Your upload was successful');
    expect(state.calls.find((c) => c.method === 'PATCH')?.body).toEqual({ tags: ['x'] });
  });

  it('names a file whose shared details were refused', async () => {
    state.refusals.set('PATCH file', problem(400, 'field.tooLong', 'Description is too long.'));
    open();
    await choose(png('one.png'));
    await userEvent.click(screen.getByText('Details'));
    await userEvent.type(screen.getByLabelText('Description'), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Upload' }));
    await screen.findByText('Your upload was successful');
    expect(screen.getByRole('alert')).toHaveTextContent('up1.jpg: Description is too long.');
  });

  it('shows Uploading... and a progress bar per file while it runs', async () => {
    let release: () => void = () => undefined;
    const held = new Promise<void>((r) => { release = r; });
    server.use(http.post(`${API}/weblogs/alpha/media`, async () => {
      await held;
      return HttpResponse.json({ created: 1, failed: 0, results: [{ status: 'created', file: file('up9') }] }, { status: 201 });
    }));
    open();
    await choose(png('one.png'));
    await userEvent.click(screen.getByRole('button', { name: 'Upload' }));
    expect(await screen.findByRole('button', { name: 'Uploading...' })).toBeDisabled();
    expect(await screen.findByRole('progressbar', { name: 'one.png' })).toBeInTheDocument();
    await act(async () => { release(); await held; });
    expect(await screen.findByText('Your upload was successful')).toBeInTheDocument();
  });

  it('says which file the server refused and keeps the form for another try', async () => {
    state.uploadStatus = 'quota_exceeded';
    open();
    await choose(png('big.png'));
    await userEvent.click(screen.getByRole('button', { name: 'Upload' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Error uploading file big.png: Quota exceeded.');
    expect(screen.queryByText('Your upload was successful')).toBeNull();
    expect(screen.getByRole('button', { name: 'Upload' })).toBeEnabled();
  });

  it('shows a refused request (problem+json) with its detail', async () => {
    state.refusals.set('POST upload', problem(403, 'auth.noPermission', 'Uploads are switched off.'));
    open();
    await choose(png('x.png'));
    await userEvent.click(screen.getByRole('button', { name: 'Upload' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Error uploading file x.png');
    expect(alert).toHaveTextContent('Uploads are switched off.');
  });

  it('reports a landed file and a refused one together', async () => {
    let n = 0;
    server.use(http.post(`${API}/weblogs/alpha/media`, () => {
      n++;
      return n === 1
        ? HttpResponse.json({ created: 1, failed: 0, results: [{ status: 'created', file: file('okay') }] }, { status: 201 })
        : HttpResponse.json({ created: 0, failed: 1, results: [{ status: 'forbidden_extension' }] }, { status: 207 });
    }));
    open();
    await choose(png('one.png'), png('two.png'));
    await userEvent.click(screen.getByRole('button', { name: 'Upload' }));
    expect(await screen.findByText('Your upload was successful')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Error uploading file');
    expect(screen.getAllByRole('checkbox')).toHaveLength(1);
  });

  it('reports a network failure by file name', async () => {
    server.use(http.post(`${API}/weblogs/alpha/media`, () => HttpResponse.error()));
    open();
    await choose(png('lost.png'));
    await userEvent.click(screen.getByRole('button', { name: 'Upload' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Error uploading file lost.png');
  });

  it('treats a 401 as the end of the session', async () => {
    server.use(http.post(`${API}/weblogs/alpha/media`, () => problem(401, 'auth.notAuthenticated', 'Sign in.')));
    open();
    await choose(png('x.png'));
    await userEvent.click(screen.getByRole('button', { name: 'Upload' }));
    await waitFor(() => { expect(document.getElementById('sessionExpiryBar')).not.toBeNull(); });
    expect(await screen.findByText('Error uploading file x.png')).toBeInTheDocument();
  });
});

describe('Media upload success page', () => {
  const uploadMixed = async () => {
    let n = 0;
    server.use(http.post(`${API}/weblogs/alpha/media`, () => {
      n++;
      const made = n === 1 ? file('img1', { name: 'pic.png', contentType: 'image/png', length: 123, width: 3, height: 2 })
        : file('doc1', { name: 'guide.pdf', contentType: 'application/pdf', length: 77, width: 0, height: 0 });
      return HttpResponse.json({ created: 1, failed: 0, results: [{ status: 'created', file: made }] }, { status: 201 });
    }));
    open();
    await choose(png('pic.png'), new File(['x'], 'guide.pdf', { type: 'application/pdf' }));
    await userEvent.click(screen.getByRole('button', { name: 'Upload' }));
    await screen.findByText('Your upload was successful');
  };

  it('lists images with facts and a copyable link, and other files as plain information', async () => {
    await uploadMixed();
    expect(document.getElementById('entry')?.getAttribute('method')).toBeNull();
    const card = screen.getByRole('checkbox', { name: 'pic.png' }).closest('.card') as HTMLElement;
    expect(card).toHaveTextContent('Name: pic.png');
    expect(card).toHaveTextContent('Type: image/png, Size: 123 bytes, 3 x 2 pixels');
    expect(card.querySelector('img.mediaFileImage')).toHaveAttribute('src', expect.stringContaining('/media/img1?t=true'));
    expect(within(card).getByLabelText('Link:')).toHaveValue('http://pressroom.test/pressroom/media/img1');
    expect(screen.getByText('Other files')).toBeInTheDocument();
    const other = screen.getByText('guide.pdf', { exact: false, selector: 'p' }).closest('.card') as HTMLElement;
    expect(other).toHaveTextContent('Size: 77 bytes');
    expect(other).not.toHaveTextContent('pixels');
    expect(within(other).getByLabelText('Link:')).toHaveValue('http://pressroom.test/pressroom/media/doc1');
    expect(screen.queryByRole('radio')).toBeNull();
  });

  it('copies a link with feedback', async () => {
    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    await uploadMixed();
    const button = screen.getAllByRole('button', { name: 'Copy to clipboard' })[0] as HTMLElement;
    await userEvent.click(button);
    expect(writeText).toHaveBeenCalledWith('http://pressroom.test/pressroom/media/img1');
    await waitFor(() => { expect(button).toHaveClass('copied'); });
    await waitFor(() => { expect(button).not.toHaveClass('copied'); }, { timeout: 3000 });
  });

  it('keeps New entry disabled until an image is chosen, then opens the entry editor with it, no form post', async () => {
    await uploadMixed();
    const button = screen.getByRole('button', { name: 'New entry with these' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('type', 'button');
    await userEvent.click(screen.getByRole('checkbox', { name: 'pic.png' }));
    expect(button).toBeEnabled();
    await userEvent.click(screen.getByRole('checkbox', { name: 'pic.png' }));
    expect(button).toBeDisabled();
    await userEvent.click(screen.getByRole('checkbox', { name: 'pic.png' }));
    await userEvent.click(button);
    await waitFor(() => { expect(router().state.location.pathname).toBe('/weblogs/alpha/entries/new'); });
    expect(new URLSearchParams(router().state.location.search).getAll('mediaId')).toEqual(['img1']);
    expect(state.calls.filter((c) => c.method === 'POST' && c.path !== '/media')).toEqual([]);
  });

  it('offers the site space no entry step: it has no posts', async () => {
    state.me = { userName: 'jake', screenName: 'Jake', kind: 'PERSON', globalAdmin: false, tokenScope: {},
      weblogs: [{ handle: 'site-space', name: 'Site', permission: 'POST' }] };
    server.use(
      http.get(`${API}/weblogs/site-space`, () => HttpResponse.json({ handle: 'site-space', name: 'Site', active: true, timeZone: 'UTC' })),
      http.get(`${API}/weblogs/site-space/media/directories`, () => HttpResponse.json(state.dirs)),
      http.post(`${API}/weblogs/site-space/media`, () => HttpResponse.json({ created: 1, failed: 0, results: [
        { status: 'created', file: file('s1', { name: 'logo.png', contentType: 'image/png' }) }] }, { status: 201 })));
    last = open('/weblogs/site-space/media/upload');
    await choose(png('logo.png'));
    await userEvent.click(screen.getByRole('button', { name: 'Upload' }));
    await screen.findByText('Your upload was successful');
    expect(screen.queryByRole('button', { name: 'New entry with these' })).toBeNull();
    expect(screen.queryByRole('checkbox', { name: 'logo.png' })).toBeNull();
    expect(screen.getByRole('link', { name: 'Done' })).toBeInTheDocument();
  });

  it('goes back to an empty form with Upload more', async () => {
    await uploadMixed();
    await userEvent.click(screen.getByRole('button', { name: 'Upload more' }));
    expect(await screen.findByRole('button', { name: 'Upload' })).toBeDisabled();
    expect(document.getElementById('mediaChosenFiles')).toBeEmptyDOMElement();
  });
});
