import { readFileSync, readdirSync } from 'node:fs';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { RichMessage } from '../../../src/admin/editor/RichMessage';
import { diffLines } from '../../../src/admin/editor/RevisionsCard';
import { SEO_DESCRIPTION_MAX, SEO_DESCRIPTION_MIN, SEO_TITLE_MAX } from '../../../src/admin/editor/EntryRail';
import { SHORTCODE_CARDS } from '../../../src/admin/editor/shortcodeCards';
import { markdownLink } from '../../../src/admin/screens/EntryEditor';
import { API, cm, lastRequest, open, openWithState, problem, resetState, server, state } from './harness';

const hard = vi.hoisted(() => ({ navigate: vi.fn() }));
vi.mock('../../../src/admin/routes/hardNavigate', () => ({ hardNavigate: hard.navigate }));

beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); hard.navigate.mockClear(); });
afterAll(() => { server.close(); });
beforeEach(() => { resetState(); window.localStorage.clear(); });

const click = (name: string | RegExp) => userEvent.click(screen.getByRole('button', { name }));
const ready = () => screen.findByDisplayValue('Rome trip');

describe('revisions', () => {
  beforeEach(() => {
    state.revisions = [
      { id: 'r1', created: '2026-03-01T08:00:00Z', creator: 'maiia', note: 'Shorten the intro', kept: false },
      { id: 'r2', created: '2026-02-28T08:00:00Z', creator: 'jake', kept: true }
    ];
  });

  it('lists every revision with who, when and why, and marks a kept one', async () => {
    open('/weblogs/alpha/entries/e1');
    await ready();
    const table = await screen.findByRole('table');
    expect(document.getElementById('entryRevisionsCard')).toHaveClass('editor-rail-extra');
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent('2026-03-01 09:00');
    expect(rows[0]).toHaveTextContent('maiia');
    expect(rows[1]).toHaveClass('revision-note-row');
    expect(rows[1]).toHaveTextContent('Shorten the intro');
    expect(rows[2]).toHaveTextContent('Kept');
    expect(within(rows[2] as HTMLElement).getByRole('button', { name: 'Release' })).toBeInTheDocument();
    expect(within(rows[0] as HTMLElement).getByRole('button', { name: 'Keep' })).toBeInTheDocument();
  });

  it('has no card for an entry with no revisions', async () => {
    state.revisions = [];
    open('/weblogs/alpha/entries/e1');
    await ready();
    await waitFor(() => { expect(document.getElementById('entryRevisionsCard')).toBeNull(); });
  });

  it('compares a revision against the entry as stored, in a modal', async () => {
    open('/weblogs/alpha/entries/e1');
    await ready();
    await userEvent.click((await screen.findAllByRole('button', { name: 'Compare' }))[0] as HTMLElement);
    const body = await screen.findByText('Content');
    expect(body).toHaveClass('rail-group-label');
    const pre = [...document.querySelectorAll('#revisionDiffBody pre')].at(-1) as HTMLElement;
    expect(pre.querySelector('.diff-removed')).toHaveTextContent('- old');
    expect(pre.querySelector('.diff-added')).toHaveTextContent('+ Hello **Rome**');
    expect(pre.querySelector('.diff-same')).toHaveTextContent('@@ -1 +1 @@');
    // The title differs too (the revision's was "Old title"), so it is a section of its own.
    expect(screen.getByText('Title')).toBeInTheDocument();
    expect(document.querySelector('#revisionDiffBody pre')).toHaveTextContent('- Old title');
    expect(document.querySelector('#revisionDiffBody pre')).toHaveTextContent('+ Rome trip');
    await userEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(document.getElementById('revision-diff-modal')).toBeNull();
  });

  it('says a revision matches the entry when nothing differs', async () => {
    server.use(http.get(`${API}/weblogs/:handle/entries/:id/revisions/:rid`, () =>
      HttpResponse.json({ id: 'r1', title: 'Rome trip', summary: '', text: 'same', diff: '' })));
    open('/weblogs/alpha/entries/e1');
    await ready();
    await userEvent.click((await screen.findAllByRole('button', { name: 'Compare' }))[0] as HTMLElement);
    expect(await screen.findByText('This revision matches the entry as it is now.')).toHaveClass('revision-diff-same');
  });

  it('says so in the modal when the comparison cannot be made', async () => {
    server.use(http.get(`${API}/weblogs/:handle/entries/:id/revisions/:rid`, () => problem(500, 'server.error', 'no')));
    open('/weblogs/alpha/entries/e1');
    await ready();
    await userEvent.click((await screen.findAllByRole('button', { name: 'Compare' }))[0] as HTMLElement);
    expect(await screen.findByText('Preview could not be rendered.')).toBeInTheDocument();
  });

  it('restores a revision into the form and says the replaced version is itself a revision', async () => {
    open('/weblogs/alpha/entries/e1');
    await ready();
    await userEvent.click((await screen.findAllByRole('button', { name: 'Restore' }))[0] as HTMLElement);
    expect(await screen.findByText(/Restored\. The version you replaced is now itself a revision/)).toBeInTheDocument();
    expect(lastRequest('POST')?.path).toBe('/weblogs/alpha/entries/e1/revisions/r1/restore');
    expect(lastRequest('POST')?.body).toEqual({ expectedContentHash: 'h1' });
    expect(screen.getByRole('textbox', { name: 'Title' })).toHaveValue('Old title');
    expect(cm().state.doc.toString()).toBe('old');
  });

  it('keeps and releases a revision', async () => {
    open('/weblogs/alpha/entries/e1');
    await ready();
    await userEvent.click((await screen.findAllByRole('button', { name: 'Keep' }))[0] as HTMLElement);
    expect(await screen.findByText('Revision kept. No retention limit will remove it.')).toBeInTheDocument();
    expect(lastRequest('POST')?.path).toBe('/weblogs/alpha/entries/e1/revisions/r1/keep');
    await userEvent.click(screen.getByRole('button', { name: 'Release' }));
    expect(await screen.findByText('Revision released. The ordinary limits apply to it again.')).toBeInTheDocument();
    expect(lastRequest('POST')?.path).toBe('/weblogs/alpha/entries/e1/revisions/r2/unkeep');
  });

  it('shows a refused restore and a refused keep in place', async () => {
    server.use(
      http.post(`${API}/weblogs/:handle/entries/:id/revisions/:rid/restore`, () => problem(409, 'entry.changed', 'The entry changed.')),
      http.post(`${API}/weblogs/:handle/entries/:id/revisions/:rid/keep`, () => problem(409, 'revision.keepLimit', 'One item can have at most 10 kept revisions. Release one first.'))
    );
    open('/weblogs/alpha/entries/e1');
    await ready();
    await userEvent.click((await screen.findAllByRole('button', { name: 'Restore' }))[0] as HTMLElement);
    expect(await screen.findByRole('alert')).toHaveTextContent('The entry changed.');
    await userEvent.click(screen.getAllByRole('button', { name: 'Keep' })[0] as HTMLElement);
    await waitFor(() => { expect(screen.getByRole('alert')).toHaveTextContent('at most 10 kept revisions'); });
  });
});

describe('send as newsletter', () => {
  beforeEach(() => { state.entry['status'] = 'PUBLISHED'; });

  it('asks first, then sends once and shows the campaign message and the sent date', async () => {
    open('/weblogs/alpha/entries/e1');
    await ready();
    expect(document.getElementById('newsletterCard')).toHaveClass('editor-rail-extra');
    await click('Send as newsletter');
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('Send this entry to the list?');
    expect(dialog).toHaveTextContent('This emails every subscriber.');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(state.requests).toHaveLength(0);
    await click('Send as newsletter');
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Send as newsletter' }));
    expect(await screen.findByText('Campaign created and sending.')).toBeInTheDocument();
    expect(lastRequest('POST')?.path).toBe('/weblogs/alpha/entries/e1/send-newsletter');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.getElementById('newsletterSentAt')).toHaveTextContent('Sent as newsletter 2026-03-01 11:00.');
    expect(screen.queryByRole('button', { name: 'Send as newsletter' })).toBeNull();
  });

  it('shows an entry already sent without a button', async () => {
    state.entry['newsletterSentAt'] = '2026-02-01T10:00:00Z';
    open('/weblogs/alpha/entries/e1');
    await ready();
    expect(document.getElementById('newsletterSentAt')).toHaveTextContent('Sent as newsletter 2026-02-01 11:00.');
    expect(screen.queryByRole('button', { name: 'Send as newsletter' })).toBeNull();
  });

  it('points to the weblog settings when no list is set', async () => {
    (state.weblog as { newsletterListUuid?: string }).newsletterListUuid = '';
    open('/weblogs/alpha/entries/e1');
    await ready();
    expect(document.getElementById('newsletterNoList')).toHaveTextContent('Set a newsletter list UUID in Weblog Settings first.');
    expect(screen.getByRole('link', { name: 'Open weblog settings' }))
      .toHaveAttribute('href', 'http://pressroom.test/pressroom/pressroom-ui/authoring/weblogConfig.rol?weblog=alpha');
  });

  it('is not offered for an unpublished entry', async () => {
    state.entry['status'] = 'DRAFT';
    open('/weblogs/alpha/entries/e1');
    await ready();
    expect(document.getElementById('newsletterCard')).toBeNull();
  });

  it('shows a refused send in place and closes the confirmation', async () => {
    server.use(http.post(`${API}/weblogs/:handle/entries/:id/send-newsletter`, () =>
      problem(409, 'newsletter.sendFailed', 'Sending failed: list gone. Nothing was stamped; you can retry.')));
    open('/weblogs/alpha/entries/e1');
    await ready();
    await click('Send as newsletter');
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Send as newsletter' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Sending failed: list gone.');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByRole('button', { name: 'Send as newsletter' })).toBeInTheDocument();
  });
});

describe('delete (move to the Trash)', () => {
  it('asks, names the entry, moves it to the trash and goes to the entries list', async () => {
    open('/weblogs/alpha/entries/e1');
    await ready();
    await click(/Delete entry/);
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('Rome trip');
    expect(dialog).toHaveTextContent('This entry goes to the Trash.');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(state.requests).toHaveLength(0);
    await click(/Delete entry/);
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /Move to Trash/i }));
    await waitFor(() => { expect(hard.navigate).toHaveBeenCalledWith('http://pressroom.test/pressroom/pressroom-ui/authoring/entries.rol?weblog=alpha'); });
    expect(lastRequest('DELETE')?.path).toBe('/weblogs/alpha/entries/e1');
  });

  it('shows a refused delete in place and keeps the editor', async () => {
    server.use(http.delete(`${API}/weblogs/:handle/entries/:id`, () => problem(409, 'entry.alreadyTrashed', 'Already in the trash.')));
    open('/weblogs/alpha/entries/e1');
    await ready();
    await click(/Delete entry/);
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /Move to Trash/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Already in the trash.');
    expect(hard.navigate).not.toHaveBeenCalled();
  });
});

describe('trashed entries', () => {
  it('are sent to the Trash, not edited', async () => {
    state.entry['status'] = 'TRASHED';
    open('/weblogs/alpha/entries/e1');
    await waitFor(() => { expect(hard.navigate).toHaveBeenCalledWith('http://pressroom.test/pressroom/pressroom-ui/authoring/trash.rol?weblog=alpha'); });
    expect(screen.queryByRole('textbox', { name: 'Title' })).toBeNull();
  });
});

describe('new entry from media (?mediaId=)', () => {
  it('seeds the text with an [image] shortcode per image and a link for another file, skipping what is not found', async () => {
    open('/weblogs/alpha/entries/new?mediaId=img1&mediaId=gone&mediaId=doc1');
    await screen.findByLabelText('Category');
    expect(cm().state.doc.toString()).toBe(
      '[image id="img1"]\n\n<a href=\'https://x/m/doc1\'>guide.pdf</a> (99 bytes, application/pdf)');
  });

  it('shows another failure of the lookup rather than an empty editor', async () => {
    server.use(http.get(`${API}/weblogs/:handle/media/:id`, () => HttpResponse.error()));
    open('/weblogs/alpha/entries/new?mediaId=img1');
    await waitFor(() => { expect(document.getElementById('errors')).not.toBeNull(); }, { timeout: 4000 });
    expect(screen.queryByLabelText('Category')).toBeNull();
  });
});

describe('arriving from a duplicate', () => {
  it('shows the duplicate message, naming the copy, when the route says so', async () => {
    state.entry['title'] = 'Copy of Rome trip';
    openWithState('/weblogs/alpha/entries/e1', { notice: 'duplicated' });
    expect(await screen.findByText('Duplicated as the draft "Copy of Rome trip". You are now editing the copy.')).toBeInTheDocument();
  });
});

describe('units', () => {
  it('RichMessage renders the bundle markup as elements and refuses a script target', () => {
    const { container } = render(<RichMessage text={'Done. <a href="https://x.test/p">View it</a> now <span>alpha</span> <a href="javascript:alert(1)">bad</a> <b>raw</b>'} />);
    expect(container.querySelector('a')).toHaveAttribute('href', 'https://x.test/p');
    expect(container.querySelectorAll('a')).toHaveLength(1);
    expect(container.querySelector('span')).toHaveTextContent('alpha');
    expect(container).toHaveTextContent('bad <b>raw</b>');
  });

  it('markdownLink neutralises brackets, spaces and parentheses', () => {
    expect(markdownLink('a [b].pdf', 'https://x/a b (1).pdf')).toBe('[a b.pdf](https://x/a%20b%20%281%29.pdf)');
  });

  it('diffLines drops the file header and blank lines but keeps hunks', () => {
    expect(diffLines('--- a\n+++ b\n@@ -1 +1 @@\n-x\n+y\n context\n')).toEqual(['@@ -1 +1 @@', '-x', '+y', ' context']);
  });

  it('lists the shortcodes the registry registers, with the labels and snippets its handlers declare', () => {
    const dir = '../src/main/java/com/wikantik/pressroom/business/shortcodes';
    const declared = new Map<string, { label: string; chooser: boolean }>();
    for (const file of readdirSync(dir).filter((f) => f.endsWith('Shortcode.java'))) {
      const source = readFileSync(`${dir}/${file}`, 'utf8');
      const m = /ShortcodeCard\s*\.\s*(snippet|mediaChooser)\(\s*"(\w+)"\s*,\s*"([\w.]+)"/.exec(source);
      if (m !== null) {
        declared.set(m[2] ?? '', { label: m[3] ?? '', chooser: m[1] === 'mediaChooser' });
      }
    }
    expect([...declared.keys()].sort()).toEqual(SHORTCODE_CARDS.map((c) => c.name).sort());
    for (const card of SHORTCODE_CARDS) {
      expect(declared.get(card.name)).toEqual({ label: card.labelKey, chooser: card.usesMediaChooser });
    }
  });

  it('mirrors SeoLimits', () => {
    const java = readFileSync('../src/main/java/com/wikantik/pressroom/business/seo/SeoLimits.java', 'utf8');
    const constant = (name: string) => Number(new RegExp(`${name}\\s*=\\s*(\\d+)`).exec(java)?.[1]);
    expect([SEO_TITLE_MAX, SEO_DESCRIPTION_MAX, SEO_DESCRIPTION_MIN])
      .toEqual([constant('TITLE_MAX'), constant('DESCRIPTION_MAX'), constant('DESCRIPTION_MIN')]);
  });
});
