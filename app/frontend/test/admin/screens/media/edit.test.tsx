import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { patchFor } from '../../../../src/admin/screens/media/MediaEdit';
import { appRoutes } from '../../../../src/admin/router';
import { renderRoutes } from '../../support';
import { file, mediaServer, problem } from './mediaServer';

vi.mock('../../../../src/admin/screens/media/crop', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../../src/admin/screens/media/crop')>()),
  loadCropper: () => Promise.resolve()
}));

const { server, state } = mediaServer();
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => {
  state.calls.length = 0;
  state.refusals.clear();
  state.dirs = [
    { id: 'd-default', name: 'default', isPrivate: false, fileCount: 0 },
    { id: 'd-trips', name: 'trips', isPrivate: false, fileCount: 0 }
  ];
  state.files = [
    file('a', { name: 'beach.jpg', altText: 'Waves', description: 'Sunny', copyrightText: '(c) me', tags: ['sea', 'sun'], focalX: 0.25, focalY: 0.75 }),
    file('p', { name: 'guide.pdf', contentType: 'application/pdf', length: 99, width: 0, height: 0 }),
    file('g', { name: 'anim.gif', contentType: 'image/gif' })
  ];
});

const open = (id = 'a') => renderRoutes(appRoutes, `/weblogs/alpha/media/${id}`);
const patches = () => state.calls.filter((c) => c.method === 'PATCH');

describe('patchFor', () => {
  const base = file('a', { altText: 'Waves', description: 'Sunny', copyrightText: 'c', tags: ['b', 'a'] });
  const same = { name: 'a.jpg', description: 'Sunny', altText: 'Waves', tags: 'b a', copyrightText: 'c', directoryId: 'd-default', focal: null };

  it('is empty when nothing changed, whatever the tag order or separators', () => {
    expect(patchFor(base, same)).toEqual({});
    expect(patchFor(base, { ...same, tags: 'b,  a' })).toEqual({});
  });

  it('sends changes, blanks as clear, tags as a list, a move as directoryId', () => {
    expect(patchFor(base, { ...same, name: 'n.jpg', altText: '', description: '', copyrightText: 'd', tags: 'x, y', directoryId: 'd-trips' }))
      .toEqual({ name: 'n.jpg', copyrightText: 'd', tags: ['x', 'y'], directoryId: 'd-trips', clear: ['description', 'altText'] });
  });

  it('sets, moves and clears the focal point as a pair', () => {
    expect(patchFor(base, { ...same, focal: { x: 0.1, y: 0.2 } })).toEqual({ focalX: 0.1, focalY: 0.2 });
    const focused = { ...base, focalX: 0.5, focalY: 0.5 };
    expect(patchFor(focused, { ...same, focal: { x: 0.5, y: 0.5 } })).toEqual({});
    expect(patchFor(focused, { ...same, focal: { x: 0.5, y: 0.6 } })).toEqual({ focalX: 0.5, focalY: 0.6 });
    expect(patchFor(focused, { ...same, focal: null })).toEqual({ clear: ['focalX'] });
  });
});

describe('Media edit', () => {
  it('shows the file as the JSP form did', async () => {
    open();
    expect(await screen.findByRole('heading', { level: 1, name: 'Edit media file' })).toBeInTheDocument();
    expect(await screen.findByLabelText('Name')).toHaveValue('beach.jpg');
    expect(screen.getByLabelText('Description')).toHaveValue('Sunny');
    expect(screen.getByLabelText('Alt text')).toHaveValue('Waves');
    expect(screen.getByLabelText('Tags (space separated)')).toHaveValue('sea sun');
    expect(screen.getByLabelText('Copyright statement')).toHaveValue('(c) me');
    await waitFor(() => { expect(screen.getByLabelText('Folder')).toHaveValue('d-default'); });
    expect(screen.getByLabelText('URL')).toHaveValue('http://pressroom.test/pressroom/media/a');
    expect(screen.getByLabelText('URL')).toHaveAttribute('readonly');
    expect(screen.getByLabelText('Shortcode')).toHaveValue('[image id="a"]');
    expect(document.getElementById('entry')).toHaveClass('form-stacked');
    expect(screen.getByText('Type').closest('div')).toHaveTextContent('Type: image/jpeg Size: 2048 bytes');
    expect(screen.getByText('Image').closest('div')).toHaveTextContent('Image: 800 X 600 pixels');
    expect(screen.getByRole('img', { name: 'Click to view in new window' })).toHaveAttribute('src', expect.stringContaining('/media/a?t=true'));
    expect(screen.getByRole('link', { name: 'Click to view in new window' })).toHaveAttribute('target', '_blank');
    expect(screen.getByRole('link', { name: 'Cancel' })).toHaveAttribute('href', '/weblogs/alpha/media?dir=d-default');
  });

  it('saves only what changed, returns to the folder and says so', async () => {
    const { router } = open();
    await userEvent.clear(await screen.findByLabelText('Alt text'));
    await userEvent.type(screen.getByLabelText('Description'), ' day');
    await userEvent.selectOptions(screen.getByLabelText('Folder'), 'trips');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => { expect(router.state.location.pathname).toBe('/weblogs/alpha/media'); });
    expect(router.state.location.search).toBe('?dir=d-trips');
    expect(patches()).toHaveLength(1);
    expect(patches()[0]?.body).toEqual({ description: 'Sunny day', directoryId: 'd-trips', clear: ['altText'] });
    expect(patches()[0]?.csrf).toBe('tok');
    expect(await screen.findByText('Media file saved.')).toBeInTheDocument();
  });

  it('saves with no call when nothing changed', async () => {
    const { router } = open();
    await userEvent.click(await screen.findByRole('button', { name: 'Save' }));
    await waitFor(() => { expect(router.state.location.pathname).toBe('/weblogs/alpha/media'); });
    expect(patches()).toHaveLength(0);
  });

  it('marks the refused field, focuses it and shows the detail in place', async () => {
    state.refusals.set('PATCH file', problem(409, 'media.nameInUse', 'A file with that name is already in the folder.',
      [{ field: 'name', message: 'Name in use.' }]));
    open();
    await userEvent.type(await screen.findByLabelText('Name'), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('A file with that name is already in the folder.');
    const field = screen.getByLabelText('Name');
    expect(field).toHaveClass('is-invalid');
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(field).toHaveFocus();
    expect(screen.getByRole('alert')).toHaveTextContent('Name in use.');
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
  });

  it('marks each other refused field with its message', async () => {
    state.refusals.set('PATCH file', problem(400, 'field.tooLong', 'Too long.', [
      { field: 'description', message: 'D long' }, { field: 'altText', message: 'A long' }, { field: 'tags', message: 'T long' },
      { field: 'copyrightText', message: 'C long' }, { field: 'directoryId', message: 'F bad' }, { field: 'focalX', message: 'Focal bad' }]));
    open();
    await userEvent.type(await screen.findByLabelText('Name'), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    const alert = await screen.findByRole('alert');
    for (const text of ['D long', 'A long', 'T long', 'C long', 'F bad', 'Focal bad']) {
      expect(alert).toHaveTextContent(text);
    }
    expect(screen.getByLabelText('Description')).toHaveClass('is-invalid');
    expect(document.getElementById('focalPicker')).toHaveAttribute('aria-invalid', 'true');
  });

  it('shows a missing file in place', async () => {
    open('nope');
    expect(await screen.findByText('No such media file.', {}, { timeout: 4000 })).toBeInTheDocument();
  });

  it('copies the URL and shortcode, with feedback', async () => {
    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    open();
    const [urlCopy, codeCopy] = await screen.findAllByRole('button', { name: 'Copy to clipboard' });
    await userEvent.click(urlCopy as HTMLElement);
    expect(writeText).toHaveBeenCalledWith('http://pressroom.test/pressroom/media/a');
    await waitFor(() => { expect(urlCopy).toHaveClass('copied'); });
    await userEvent.click(codeCopy as HTMLElement);
    expect(writeText).toHaveBeenLastCalledWith('[image id="a"]');
    await waitFor(() => { expect(urlCopy).not.toHaveClass('copied'); }, { timeout: 3000 });
  });

  it('survives a clipboard that refuses', async () => {
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: () => Promise.reject(new Error('no')) }, configurable: true });
    open();
    await userEvent.click((await screen.findAllByRole('button', { name: 'Copy to clipboard' }))[0] as HTMLElement);
    expect(screen.getByLabelText('URL')).toBeInTheDocument();
  });

  it('offers no thumbnail, focal point or crop for a PDF, and no crop for a GIF', async () => {
    open('p');
    await screen.findByLabelText('Name');
    expect(screen.queryByText('Thumbnail')).toBeNull();
    expect(document.getElementById('focalPicker')).toBeNull();
    expect(document.getElementById('cropSectionTitle')).toBeNull();
    expect(screen.queryByText('Image')).toBeNull();
  });

  it('offers a focal point but no crop for a GIF', async () => {
    open('g');
    await screen.findByLabelText('Name');
    expect(document.getElementById('focalPicker')).not.toBeNull();
    expect(document.getElementById('cropSectionTitle')).toBeNull();
  });
});

describe('Focal point', () => {
  const picker = async () => {
    const el = await screen.findByLabelText('Focal point', { selector: '#focalPicker' });
    const image = el.querySelector('img') as HTMLElement;
    image.getBoundingClientRect = () => ({ left: 100, top: 50, width: 200, height: 100, right: 300, bottom: 150, x: 100, y: 50, toJSON: () => ({}) });
    return { el, image };
  };

  it('shows the stored point and marks a click as a fraction, saved as a pair', async () => {
    const { router } = open();
    const { el, image } = await picker();
    expect(image).toHaveAttribute('src', expect.stringMatching(/\/media\/a\?w=480$/));
    expect(screen.getByTestId('focal-marker')).toHaveStyle({ left: '25%', top: '75%' });
    fireEvent.click(el, { clientX: 200, clientY: 75 });
    expect(screen.getByTestId('focal-marker')).toHaveStyle({ left: '50%', top: '25%' });
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => { expect(router.state.location.pathname).toBe('/weblogs/alpha/media'); });
    expect(patches()[0]?.body).toEqual({ focalX: 0.5, focalY: 0.25 });
  });

  it('clamps a click outside the image and ignores one before layout', async () => {
    open();
    const { el, image } = await picker();
    fireEvent.click(el, { clientX: 900, clientY: -5 });
    expect(screen.getByTestId('focal-marker')).toHaveStyle({ left: '100%', top: '0%' });
    image.getBoundingClientRect = () => ({ left: 0, top: 0, width: 0, height: 0, right: 0, bottom: 0, x: 0, y: 0, toJSON: () => ({}) });
    fireEvent.click(el, { clientX: 5, clientY: 5 });
    expect(screen.getByTestId('focal-marker')).toHaveStyle({ left: '100%', top: '0%' });
  });

  it('nudges from the keyboard by 5 percent, 1 with Shift, from the centre when unset', async () => {
    state.files = [file('a')];
    open();
    const { el } = await picker();
    expect(screen.getByTestId('focal-marker')).toHaveAttribute('hidden');
    el.focus();
    await userEvent.keyboard('{ArrowRight}');
    expect(screen.getByTestId('focal-marker')).toHaveStyle({ left: '55%', top: '50%' });
    await userEvent.keyboard('{Shift>}{ArrowDown}{/Shift}');
    expect(screen.getByTestId('focal-marker')).toHaveStyle({ left: '55%', top: '51%' });
    await userEvent.keyboard('{ArrowLeft}{ArrowUp}x');
    expect(screen.getByTestId('focal-marker')).toHaveStyle({ left: '50%', top: '46%' });
  });

  it('clears the point and saves the clear', async () => {
    const { router } = open();
    await picker();
    await userEvent.click(screen.getByRole('button', { name: 'Clear focal point' }));
    expect(screen.getByTestId('focal-marker')).toHaveAttribute('hidden');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => { expect(router.state.location.pathname).toBe('/weblogs/alpha/media'); });
    expect(patches()[0]?.body).toEqual({ clear: ['focalX'] });
  });
});

describe('Crop', () => {
  const place = async () => {
    await screen.findByText('Crop image');
    const canvas = await waitFor(() => { const el = document.getElementById('cropCanvas'); expect(el).not.toBeNull(); return el as HTMLElement; });
    const image = document.getElementById('cropImage') as HTMLElement;
    const selection = document.getElementById('cropSelection') as HTMLElement;
    const box = (left: number, top: number, width: number, height: number) => () =>
      ({ left, top, width, height, right: left + width, bottom: top + height, x: left, y: top, toJSON: () => ({}) });
    canvas.getBoundingClientRect = box(0, 0, 500, 360);
    image.getBoundingClientRect = box(50, 0, 400, 300);
    Object.assign(selection, { x: 60, y: 20, width: 100, height: 50 });
    return { canvas, image, selection };
  };

  it('renders the cropper elements over the original, under the crop heading', async () => {
    open();
    const { image } = await place();
    expect(image).toHaveAttribute('src', 'http://pressroom.test/pressroom/media/a');
    expect(document.getElementById('cropSectionTitle')).toHaveClass('section-head');
    expect(screen.getByText(/Drag a selection over the image/)).toBeInTheDocument();
    expect(document.querySelectorAll('cropper-handle').length).toBeGreaterThan(8);
  });

  it('applies the selection in natural pixels after a confirmation, with the stale-guard sizes', async () => {
    open();
    await place();
    await userEvent.click(screen.getByRole('button', { name: 'Apply crop' }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('Cropping permanently replaces the original image');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(state.calls.some((c) => c.path === '/media/a/crop')).toBe(false);
    await userEvent.click(screen.getByRole('button', { name: 'Apply crop' }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Apply crop' }));
    expect(await screen.findByText('The image was cropped and all of its renditions were regenerated.')).toBeInTheDocument();
    const call = state.calls.find((c) => c.path === '/media/a/crop');
    expect(call?.body).toEqual({ x: 20, y: 40, width: 200, height: 100, expectedWidth: 800, expectedHeight: 600 });
    expect(call?.csrf).toBe('tok');
    await waitFor(() => { expect(document.getElementById('cropImage')).toHaveAttribute('src', expect.stringContaining('?v=1')); });
  });

  it('does nothing with no selection drawn', async () => {
    open();
    const { selection } = await place();
    Object.assign(selection, { width: 0, height: 0 });
    await userEvent.click(screen.getByRole('button', { name: 'Apply crop' }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('shows a refused crop in place with the server detail', async () => {
    state.refusals.set('POST crop', problem(409, 'media.cropStale', 'The file is now 700 by 500.'));
    open();
    await place();
    await userEvent.click(screen.getByRole('button', { name: 'Apply crop' }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Apply crop' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The image could not be cropped.');
    expect(alert).toHaveTextContent('The file is now 700 by 500.');
    expect(screen.getByRole('button', { name: 'Apply crop' })).toBeEnabled();
  });

  it('shows Cropping... while the request runs', async () => {
    let release: () => void = () => undefined;
    const held = new Promise<void>((r) => { release = r; });
    const { http, HttpResponse } = await import('msw');
    server.use(http.post('http://pressroom.test/pressroom/api/v1/weblogs/alpha/media/a/crop', async () => {
      await held;
      return HttpResponse.json(state.files[0]);
    }));
    open();
    await place();
    await userEvent.click(screen.getByRole('button', { name: 'Apply crop' }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Apply crop' }));
    expect(await screen.findByRole('button', { name: 'Cropping...' })).toBeDisabled();
    await act(async () => { release(); await held; });
    expect(await screen.findByRole('button', { name: 'Apply crop' })).toBeEnabled();
  });
});
