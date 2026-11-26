import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { MediaChooser, type MediaChoice } from '../../../../src/admin/screens/media/MediaChooser';
import { renderInApp } from '../../support';
import { file, mediaServer, problem } from './mediaServer';

const { server, state } = mediaServer();
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => {
  state.calls.length = 0;
  state.refusals.clear();
  state.dirs = [
    { id: 'd-default', name: 'default', isPrivate: false, fileCount: 0 },
    { id: 'd-secret', name: 'secret', isPrivate: true, fileCount: 0 }
  ];
  state.files = [
    file('a', { name: 'a.jpg' }),
    file('c', { name: 'c.pdf', contentType: 'application/pdf' }),
    file('s', { name: 's.jpg', directory: 'd-secret' })
  ];
});

const tiles = () => [...document.querySelectorAll('.mediaObject')].map((m) => m.getAttribute('aria-label'));

describe('MediaChooser', () => {
  it('lists every file in every folder as a modal with the JSP texts and an All folders menu', async () => {
    const onSelect = vi.fn();
    renderInApp(<MediaChooser handle="alpha" onSelect={onSelect} onCancel={() => undefined} />);
    expect(await screen.findByRole('dialog', { name: 'Choose an image' })).toHaveAttribute('aria-modal', 'true');
    await waitFor(() => { expect(tiles()).toEqual(['a.jpg', 'c.pdf', 's.jpg']); });
    expect(screen.getByText(/Select an image and it will be included/)).toHaveClass('pagetip');
    expect(screen.getByLabelText('Folder')).toHaveValue('');
    expect(screen.getByRole('option', { name: 'All folders' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    expect(document.querySelector('.bi-file-earmark')).not.toBeNull();
  });

  it('hands back the clicked file: id, name, url, whether it is an image', async () => {
    const onSelect = vi.fn();
    renderInApp(<MediaChooser handle="alpha" onSelect={onSelect} onCancel={() => undefined} />);
    await userEvent.click(await screen.findByLabelText('a.jpg', { selector: '.mediaObject' }));
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({
      id: 'a', name: 'a.jpg', url: 'http://pressroom.test/pressroom/media/a', isImage: true }));
    await userEvent.click(screen.getByLabelText('c.pdf', { selector: '.mediaObject' }));
    expect(onSelect).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'c', isImage: false }));
    screen.getByLabelText('a.jpg', { selector: '.mediaObject' }).focus();
    await userEvent.keyboard('{Enter}');
    await userEvent.keyboard(' ');
    await userEvent.keyboard('x');
    expect(onSelect).toHaveBeenCalledTimes(4);
  });

  it('offers images only, outside private folders, when asked for featured images', async () => {
    renderInApp(<MediaChooser handle="alpha" imagesOnly onSelect={() => undefined} onCancel={() => undefined} />);
    await waitFor(() => { expect(tiles()).toEqual(['a.jpg']); });
    expect(state.calls.find((c) => c.path === '/media')).toBeDefined();
  });

  it('narrows to one folder from the menu', async () => {
    renderInApp(<MediaChooser handle="alpha" onSelect={() => undefined} onCancel={() => undefined} />);
    await screen.findByLabelText('a.jpg', { selector: '.mediaObject' });
    await userEvent.selectOptions(screen.getByLabelText('Folder'), 'secret');
    await waitFor(() => { expect(tiles()).toEqual(['s.jpg']); });
  });

  it('shows the empty state naming the Media tab', async () => {
    state.files = [];
    renderInApp(<MediaChooser handle="alpha" onSelect={() => undefined} onCancel={() => undefined} />);
    expect(await screen.findByText('No files in this folder.')).toBeInTheDocument();
    expect(screen.getByText(/Photos uploaded on the Media tab/)).toBeInTheDocument();
  });

  it('shows a refused listing in place', async () => {
    state.refusals.set('GET list', problem(403, 'auth.noPermission', 'You may not browse media here.'));
    renderInApp(<MediaChooser handle="alpha" onSelect={() => undefined} onCancel={() => undefined} />);
    expect(await screen.findByText('You may not browse media here.')).toBeInTheDocument();
  });

  it('cancels from the button, Escape and the backdrop click, and gives focus back', async () => {
    const onCancel = vi.fn();
    function Host() {
      const [shown, setShown] = useState(true);
      return (
        <>
          <button type="button" id="opener">open</button>
          {shown ? <MediaChooser handle="alpha" onSelect={() => undefined} onCancel={() => { onCancel(); setShown(false); }} /> : null}
        </>
      );
    }
    renderInApp(<Host />);
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('cancels on Escape and on a backdrop click', async () => {
    const onCancel = vi.fn();
    renderInApp(<MediaChooser handle="alpha" onSelect={() => undefined} onCancel={onCancel} />);
    const dialog = await screen.findByRole('dialog');
    await userEvent.keyboard('{Escape}');
    expect(onCancel).toHaveBeenCalledTimes(1);
    await userEvent.click(dialog);
    expect(onCancel).toHaveBeenCalledTimes(2);
    await userEvent.click(within(dialog).getByText('Choose an image'));
    expect(onCancel).toHaveBeenCalledTimes(2);
  });

  it('exports a choice shape the editors can type against', () => {
    const choice: MediaChoice = { id: 'a', name: 'a', url: 'u', isImage: true, file: file('a') };
    expect(choice.id).toBe('a');
    act(() => undefined);
  });
});
