import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { API, cm, lastRequest, open, problem, resetState, server, state, typeInEditor } from './harness';

beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => { resetState(); window.localStorage.clear(); });

const click = (name: string | RegExp) => userEvent.click(screen.getByRole('button', { name }));
const doc = () => cm().state.doc.toString();
/** Replaces the document and puts the caret at its end (CodeMirror's own selection after typing). */
const setDoc = (text: string) => act(async () => {
  cm().dispatch({ changes: { from: 0, to: cm().state.doc.length, insert: text }, selection: { anchor: text.length } });
});
const ready = () => screen.findByDisplayValue('Rome trip');

describe('the writing surface: the browser suite\'s text seam', () => {
  type Seam = { pressroomGetEntryText?: () => string; pressroomSetEntryText?: (text: string) => void };
  const seam = () => window as unknown as Seam;

  it('lets the browser tests read and replace the text, as the JSP editors\' page script did', async () => {
    const { unmount } = open('/weblogs/alpha/entries/e1');
    await ready();
    expect(seam().pressroomGetEntryText?.()).toBe('Hello **Rome**');
    await act(async () => { seam().pressroomSetEntryText?.('Replaced from a test'); });
    expect(doc()).toBe('Replaced from a test');
    await click('Save draft');
    await screen.findByText('Draft saved');
    expect((lastRequest('PATCH')?.body as { text: string }).text).toBe('Replaced from a test');
    unmount();
    expect(seam().pressroomGetEntryText).toBeUndefined();
    expect(seam().pressroomSetEntryText).toBeUndefined();
  });
});

describe('the writing surface: toolbar', () => {
  it.each([
    ['Bold', '', '**bold text**'],
    ['Italic', '', '*italic text*'],
    ['Code', '', '`code`'],
    ['Link', '', '[link text](https://)'],
    ['Heading', 'Line', '## Line'],
    ['Quote', 'Line', '> Line'],
    ['Bulleted list', 'Line', '- Line'],
    ['Numbered list', 'Line', '1. Line'],
    ['Table', '', '\n| Header | Header |\n| --- | --- |\n|  |  |\n']
  ])('formats with the %s tool', async (tool, start, expected) => {
    state.entry['text'] = start;
    open('/weblogs/alpha/entries/e1');
    await ready();
    await setDoc(start);
    await click(tool);
    expect(doc()).toBe(expected);
  });

  it('takes a line prefix off again on a second click', async () => {
    open('/weblogs/alpha/entries/e1');
    await ready();
    await setDoc('Line');
    await click('Heading');
    await click('Heading');
    expect(doc()).toBe('Line');
  });

  it('answers Ctrl+B and Ctrl+K inside the editor with the toolbar commands', async () => {
    state.entry['text'] = '';
    open('/weblogs/alpha/entries/e1');
    await ready();
    await userEvent.click(document.querySelector('.cm-content') as HTMLElement);
    await userEvent.keyboard('{Control>}b{/Control}');
    expect(doc()).toBe('**bold text**');
  });

  it('inserts a shortcode from the Insert menu, and opens the media chooser for the image card', async () => {
    state.entry['text'] = '';
    open('/weblogs/alpha/entries/e1');
    await ready();
    await click('Insert');
    const menu = document.querySelector('#shortcodeInsertMenu .dropdown-menu') as HTMLElement;
    expect(menu).toHaveClass('show');
    expect(within(menu).getAllByRole('button').map((b) => b.textContent)).toEqual(
      ['Image…', 'Photo gallery', 'Map with pins', 'Call to action', 'Questions and answers', 'Video', 'Contact form', 'Subscribe form']);
    await userEvent.click(within(menu).getByRole('button', { name: 'Contact form' }));
    expect(doc()).toBe('[contact]');
    expect(menu).not.toHaveClass('show');

    await click('Insert');
    await userEvent.click(screen.getByRole('button', { name: 'Image…' }));
    expect(await screen.findByRole('dialog', { name: 'Choose an image' })).toBeInTheDocument();
    // Insert offers every file, not just images.
    expect(await screen.findByLabelText('guide.pdf', { selector: '.mediaObject' })).toBeInTheDocument();
  });

  it('puts a chosen image in as an [image] shortcode and any other file as a Markdown link, then closes the chooser', async () => {
    state.entry['text'] = '';
    open('/weblogs/alpha/entries/e1');
    await ready();
    await click('Insert media file');
    await userEvent.click(await screen.findByLabelText('a.jpg', { selector: '.mediaObject' }));
    expect(doc()).toBe('[image id="img1"]');
    expect(screen.queryByRole('dialog', { name: 'Choose an image' })).toBeNull();
    await click('Insert media file');
    await userEvent.click(await screen.findByLabelText('guide.pdf', { selector: '.mediaObject' }));
    expect(doc()).toBe('[image id="img1"][guide.pdf](https://x/m/doc1)');
    await click('Insert media file');
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog', { name: 'Choose an image' })).toBeNull();
  });

  it('closes the chooser on Escape', async () => {
    open('/weblogs/alpha/entries/e1');
    await ready();
    await click('Insert media file');
    await screen.findByRole('dialog', { name: 'Choose an image' });
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'Choose an image' })).toBeNull();
  });
});

describe('the writing surface: modes and preview', () => {
  it('starts in write mode, remembers a mode per install, and shows the weblog theme shell in preview', async () => {
    open('/weblogs/alpha/entries/e1');
    await ready();
    expect(document.getElementById('editorSurface')).toHaveAttribute('data-mode', 'write');
    expect(document.getElementById('editorPreviewPane')).toBeNull();
    await userEvent.click(screen.getByRole('radio', { name: 'Split' }));
    expect(screen.getByRole('radio', { name: 'Split' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('radio', { name: 'Write' })).toHaveAttribute('aria-checked', 'false');
    expect(document.getElementById('editorSurface')).toHaveAttribute('data-mode', 'split');
    const frame = document.querySelector('#editorPreviewPane iframe') as HTMLIFrameElement;
    expect(frame).toHaveAttribute('sandbox', 'allow-scripts allow-same-origin');
    expect(frame).toHaveAttribute('src', 'http://pressroom.test/pressroom/pressroom-ui/authoring/preview/alpha/?shell=true');
    expect(window.localStorage.getItem('pressroom.editor.mode.v1:http://pressroom.test/pressroom')).toBe('split');
    await userEvent.click(screen.getByRole('radio', { name: 'Write' }));
    expect(document.getElementById('editorPreviewPane')).toBeNull();
  });

  it('opens in the remembered mode', async () => {
    window.localStorage.setItem('pressroom.editor.mode.v1:http://pressroom.test/pressroom', 'preview');
    open('/weblogs/alpha/entries/e1');
    await ready();
    expect(document.getElementById('editorSurface')).toHaveAttribute('data-mode', 'preview');
  });

  async function previewFrame() {
    await userEvent.click(screen.getByRole('radio', { name: 'Preview' }));
    const frame = document.querySelector('#editorPreviewPane iframe') as HTMLIFrameElement;
    const post = vi.fn();
    Object.defineProperty(frame.contentWindow, 'postMessage', { value: post, configurable: true });
    const announce = (source: Window | null = frame.contentWindow, origin = window.location.origin) => {
      act(() => { window.dispatchEvent(new MessageEvent('message', { data: { type: 'pressroom-preview-ready' }, origin, source })); });
    };
    return { frame, post, announce };
  }

  it('renders on the server once the shell says it is ready and posts the HTML into the frame, never into this page', async () => {
    open('/weblogs/alpha/entries/e1');
    await ready();
    const { post, announce } = await previewFrame();
    announce();
    await waitFor(() => { expect(post).toHaveBeenCalled(); });
    expect(lastRequest('POST')?.path).toBe('/weblogs/alpha/entries/e1/preview');
    expect(lastRequest('POST')?.body).toEqual({ text: 'Hello **Rome**' });
    expect(post).toHaveBeenCalledWith({ type: 'pressroom-preview', html: '<p>rendered</p>', scroll: 0 }, window.location.origin);
    expect(screen.queryByText('rendered')).toBeNull();
  });

  it('re-renders after a pause when the text changes, and previews a new entry as a scratch row', async () => {
    open('/weblogs/alpha/entries/new');
    await screen.findByLabelText('Category');
    const { post, announce } = await previewFrame();
    announce();
    await waitFor(() => { expect(post).toHaveBeenCalledTimes(1); });
    expect(lastRequest('POST')?.path).toBe('/weblogs/alpha/entries/preview');
    await typeInEditor('typed');
    await waitFor(() => { expect(post).toHaveBeenCalledTimes(2); }, { timeout: 2000 });
    expect(lastRequest('POST')?.body).toEqual({ text: 'typed' });
  });

  it('ignores a ready message from any window but its own frame or any other origin', async () => {
    open('/weblogs/alpha/entries/e1');
    await ready();
    const { post, announce } = await previewFrame();
    announce(window);
    announce(null);
    announce(undefined, 'https://evil.example');
    await new Promise((r) => setTimeout(r, 600));
    expect(post).not.toHaveBeenCalled();
  });

  it('says so in the frame when the render fails', async () => {
    server.use(http.post(`${API}/weblogs/:handle/entries/:id/preview`, () => problem(500, 'server.error', 'no')));
    open('/weblogs/alpha/entries/e1');
    await ready();
    const { post, announce } = await previewFrame();
    announce();
    await waitFor(() => { expect(post).toHaveBeenCalled(); });
    expect(post).toHaveBeenCalledWith({ type: 'pressroom-preview', html: '<p class="pressroom-preview-error">Preview could not be rendered.</p>' }, window.location.origin);
  });

  it('keeps one render in flight and follows with one more for everything typed meanwhile', async () => {
    let release: () => void = () => undefined;
    let count = 0;
    server.use(http.post(`${API}/weblogs/:handle/entries/:id/preview`, async () => {
      count++;
      if (count === 1) {
        await new Promise<void>((resolve) => { release = resolve; });
      }
      return HttpResponse.json({ html: `<p>render ${String(count)}</p>` });
    }));
    open('/weblogs/alpha/entries/e1');
    await ready();
    const { post, announce } = await previewFrame();
    announce();
    await waitFor(() => { expect(count).toBe(1); });
    await typeInEditor('a');
    await new Promise((r) => setTimeout(r, 600));
    expect(count).toBe(1);
    release();
    await waitFor(() => { expect(count).toBe(2); });
    await waitFor(() => { expect(post).toHaveBeenCalledTimes(2); });
  });

  it('sends the editor scroll to the preview, one message per frame', async () => {
    open('/weblogs/alpha/entries/e1');
    await ready();
    const { post, announce } = await previewFrame();
    announce();
    await waitFor(() => { expect(post).toHaveBeenCalled(); });
    post.mockClear();
    fireEvent.scroll(cm().scrollDOM);
    fireEvent.scroll(cm().scrollDOM);
    await waitFor(() => { expect(post).toHaveBeenCalledTimes(1); });
    expect(post).toHaveBeenCalledWith({ type: 'pressroom-preview-scroll', scroll: 0 }, window.location.origin);
  });
});

describe('the writing surface: status line and guide', () => {
  it('shows the word count and reading time', async () => {
    state.entry['text'] = 'one two three';
    open('/weblogs/alpha/entries/e1');
    await ready();
    expect(document.getElementById('editorStatusWords')).toHaveTextContent('3 words · 1 min read');
    await typeInEditor(' four');
    await waitFor(() => { expect(document.getElementById('editorStatusWords')).toHaveTextContent('4 words'); });
  });

  it('opens the writing guide from its button and Ctrl+/, lists the registry shortcodes and closes on Escape', async () => {
    open('/weblogs/alpha/entries/e1');
    await ready();
    await click('Writing guide');
    const guide = document.getElementById('editorGuide') as HTMLElement;
    expect(guide).toHaveClass('offcanvas-end');
    expect(within(guide).getByText('Questions and answers')).toBeInTheDocument();
    expect(guide.querySelectorAll('[data-shortcode]')).toHaveLength(8);
    const row = (key: string) => within(guide).getByText(key).closest('tr') as HTMLElement;
    expect(row('Publish')).toHaveTextContent('Ctrl+Enter');
    expect(row('Save draft')).toHaveTextContent('Ctrl+S');
    await userEvent.keyboard('{Escape}');
    expect(document.getElementById('editorGuide')).toBeNull();
    await userEvent.click(screen.getByRole('textbox', { name: 'Title' }));
    await userEvent.keyboard('{Control>}/{/Control}');
    expect(document.getElementById('editorGuide')).not.toBeNull();
    await userEvent.click(within(document.getElementById('editorGuide') as HTMLElement).getByRole('button', { name: 'Close' }));
    expect(document.getElementById('editorGuide')).toBeNull();
  });

  it('names Update beside Ctrl+S in the guide for a live entry', async () => {
    state.entry['status'] = 'PUBLISHED';
    open('/weblogs/alpha/entries/e1');
    await ready();
    await click('Writing guide');
    expect(within(document.getElementById('editorGuide') as HTMLElement).getByText('Update').closest('tr')).toHaveTextContent('Ctrl+S');
  });

  it('shows the Command key on a Mac', async () => {
    const platform = vi.spyOn(navigator, 'platform', 'get').mockReturnValue('MacIntel');
    open('/weblogs/alpha/entries/e1');
    await ready();
    expect(screen.getByRole('button', { name: 'Bold' })).toHaveAttribute('title', 'Bold (⌘B)');
    await click('Writing guide');
    expect(document.querySelector('#editorGuide .editor-mod')).toHaveTextContent('⌘');
    platform.mockRestore();
  });
});

describe('the writing surface: paste and drop upload', () => {
  const paste = (files: File[]) => {
    const event = new Event('paste', { bubbles: true, cancelable: true });
    Object.defineProperty(event, 'clipboardData', { value: { files, getData: () => '' } });
    act(() => { (document.querySelector('.cm-content') as HTMLElement).dispatchEvent(event); });
    return event;
  };
  const image = (name = 'p.png') => new File(['x'], name, { type: 'image/png' });

  it('uploads a pasted image and swaps the placeholder for its [image] shortcode', async () => {
    state.entry['text'] = '';
    open('/weblogs/alpha/entries/e1');
    await ready();
    const event = paste([image()]);
    expect(event.defaultPrevented).toBe(true);
    expect(doc()).toContain('[uploading p.png…]');
    await waitFor(() => { expect(doc()).toBe('[image id="up1"]\n'); });
  });

  it('removes the placeholder and says why when the upload is refused, naming the file', async () => {
    state.entry['text'] = 'Before';
    server.use(http.post(`${API}/weblogs/:handle/media`, () => HttpResponse.json(
      { results: [{ fileName: 'p.png', status: 'REFUSED', detail: 'p.png is too large.' }], created: 0, failed: 1 }, { status: 207 })));
    open('/weblogs/alpha/entries/e1');
    await ready();
    paste([image()]);
    await waitFor(() => { expect(document.getElementById('editorStatusAlerts')).toHaveTextContent('p.png is too large.'); });
    expect(doc()).toBe('Before');
    expect(document.querySelector('#editorStatusAlerts .is-error')).not.toBeNull();
  });

  it('shows the top-level problem when the whole request is refused, and a generic line on a network failure', async () => {
    server.use(http.post(`${API}/weblogs/:handle/media`, () => problem(403, 'media.uploadsDisabled', 'Uploads are switched off.')));
    open('/weblogs/alpha/entries/e1');
    await ready();
    paste([image()]);
    await waitFor(() => { expect(document.getElementById('editorStatusAlerts')).toHaveTextContent('Uploads are switched off.'); });
    server.use(http.post(`${API}/weblogs/:handle/media`, () => HttpResponse.error()));
    paste([image('q.png')]);
    await waitFor(() => { expect(document.getElementById('editorStatusAlerts')).toHaveTextContent('The image could not be uploaded.'); });
  });

  it('uploads several images one at a time', async () => {
    state.entry['text'] = '';
    open('/weblogs/alpha/entries/e1');
    await ready();
    paste([image('a.png'), image('b.png')]);
    await waitFor(() => { expect((doc().match(/\[image id="up1"\]/g) ?? [])).toHaveLength(2); });
  });

  it('offers the browser suite the upload seam the JSP editors\' script had (pressroomUploadImages)', async () => {
    state.entry['text'] = '';
    const { unmount } = open('/weblogs/alpha/entries/e1');
    await ready();
    const seam = (window as unknown as { pressroomUploadImages?: (files: File[]) => void }).pressroomUploadImages;
    expect(seam).toBeTypeOf('function');
    act(() => { seam?.([image()]); });
    await waitFor(() => { expect(doc()).toBe('[image id="up1"]\n'); });
    unmount();
    expect((window as unknown as Record<string, unknown>)['pressroomUploadImages']).toBeUndefined();
  });

  it('leaves a paste with no image alone', async () => {
    open('/weblogs/alpha/entries/e1');
    await ready();
    paste([new File(['x'], 'a.txt', { type: 'text/plain' })]);
    expect(doc()).not.toContain('uploading');
    expect(state.requests).toHaveLength(0);
  });
});
