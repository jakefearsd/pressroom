import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { API, open, problem, recordCalls, resetWorld, server, world, writes } from './harness';

const required = { id: 't1', name: 'Weblog', action: 'WEBLOG', description: 'The front page', required: true,
  hidden: false, navbar: false, contents: '<html>front</html>' };
const custom = { id: 't2', name: 'Side', action: 'CUSTOM', description: 'A sidebar', required: false, hidden: false,
  navbar: true, link: 'side', contentType: 'text/xml', contents: '<aside/>' };
let current: Record<string, unknown>;

beforeAll(() => { recordCalls(); server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => {
  resetWorld();
  window.localStorage.clear();
  current = custom;
  server.use(
    http.get(`${API}/weblogs/alpha/templates/:id`, () => HttpResponse.json(current)),
    http.patch(`${API}/weblogs/alpha/templates/:id`, async ({ request }) => {
      current = { ...current, ...(await request.json() as object) };
      return HttpResponse.json(current);
    })
  );
});

describe('Template edit screen', () => {
  it('locks name and description of a required template and sends only its source', async () => {
    const user = userEvent.setup();
    current = required;
    open('/weblogs/alpha/templates/t1');
    const source = await screen.findByLabelText('Template source');
    expect(screen.getByLabelText('Name')).toHaveAttribute('readonly');
    expect(screen.getByLabelText('Description')).toHaveAttribute('readonly');
    expect(screen.getByLabelText('Edit template')).toHaveValue('WEBLOG');
    expect(screen.queryByLabelText('Link (path)')).toBeNull();
    expect(screen.queryByText('Advanced settings')).toBeNull();
    expect(screen.getByText(/You can edit this template to change what it generates\./)).toBeInTheDocument();
    await user.clear(source);
    await user.type(source, 'new');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Template "Weblog" updated.')).toBeInTheDocument();
    expect(writes()).toMatchObject([{ method: 'PATCH', path: '/weblogs/alpha/templates/t1', body: { contents: 'new' }, csrf: 'tok' }]);
    expect(screen.getByRole('link', { name: 'Done' })).toHaveAttribute('href', expect.stringContaining('/weblogs/alpha/templates'));
  });

  it('keeps the link preview current, withdraws Open while the link differs from the saved one, and opens the saved page', async () => {
    const user = userEvent.setup();
    const opened = vi.spyOn(window, 'open').mockReturnValue(null);
    open('/weblogs/alpha/templates/t2');
    const link = await screen.findByLabelText('Link (path)');
    await waitFor(() => { expect(document.getElementById('linkPreview')).toHaveTextContent('side'); });
    expect(document.querySelector('#good_link .data')).toHaveTextContent('https://elsewhere.example/blogs/alpha/page/side');
    await user.click(screen.getByRole('button', { name: 'Open' }));
    expect(opened).toHaveBeenCalledWith('https://elsewhere.example/blogs/alpha/page/side?type=CUSTOM', '_blank', 'noopener');
    await user.type(link, '2');
    expect(document.getElementById('linkPreview')).toHaveTextContent('side2');
    expect(document.getElementById('launchLink')).toHaveAttribute('hidden');
    expect(screen.getByText('The link has changed. Save the template before opening the page.')).not.toHaveAttribute('hidden');
    await user.clear(link);
    expect(document.getElementById('no_link')).not.toHaveAttribute('hidden');
    expect(document.getElementById('good_link')).toHaveAttribute('hidden');
    opened.mockRestore();
  });

  it('saves a custom template with its link, flags and manual content type', async () => {
    const user = userEvent.setup();
    open('/weblogs/alpha/templates/t2');
    await user.type(await screen.findByLabelText('Link (path)'), '2');
    await user.click(screen.getByRole('button', { name: 'Advanced settings' }));
    expect(screen.getByLabelText('Use a manually specified content type')).toHaveValue('text/xml');
    await user.click(screen.getByLabelText(/Hidden/));
    await user.click(screen.getByLabelText(/Include in page menu/));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('Template "Side" updated.');
    expect(writes()[0]?.body).toEqual({
      contents: '<aside/>', name: 'Side', description: 'A sidebar', link: 'side2', hidden: true, navbar: false, contentType: 'text/xml'
    });
  });

  it('sends an empty content type when it is set automatically, and hides the manual field', async () => {
    const user = userEvent.setup();
    open('/weblogs/alpha/templates/t2');
    await user.click(await screen.findByRole('button', { name: 'Advanced settings' }));
    await user.click(screen.getByLabelText('Automatically set content type'));
    expect(screen.queryByLabelText('Use a manually specified content type')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('Template "Side" updated.');
    expect(writes()[0]?.body).toMatchObject({ contentType: '' });
  });

  it('marks and focuses the refused field, and opens Advanced for a refused content type', async () => {
    const user = userEvent.setup();
    server.use(http.patch(`${API}/weblogs/alpha/templates/t2`, () =>
      problem(400, 'request.invalidFields', 'Bad type.', [{ field: 'contentType', message: 'Bad type.' }])));
    open('/weblogs/alpha/templates/t2');
    await user.click(await screen.findByRole('button', { name: 'Save' }));
    const type = await screen.findByLabelText('Use a manually specified content type');
    await waitFor(() => { expect(type).toHaveFocus(); });
    expect(type).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText('Bad type.')).toBeInTheDocument();
    expect(document.getElementById('collapseAdvanced')).toHaveClass('show');
  });

  it('shows a name conflict on the name field and keeps what was typed', async () => {
    const user = userEvent.setup();
    server.use(http.patch(`${API}/weblogs/alpha/templates/t2`, () =>
      problem(409, 'template.nameInUse', 'Taken.', [{ field: 'name', message: 'Taken.' }])));
    open('/weblogs/alpha/templates/t2');
    const name = await screen.findByLabelText('Name');
    await user.clear(name);
    await user.type(name, 'Weblog');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => { expect(name).toHaveFocus(); });
    expect(name).toHaveClass('is-invalid');
    expect(name).toHaveValue('Weblog');
  });

  it('says so when the template is gone', async () => {
    server.use(http.get(`${API}/weblogs/alpha/templates/t9`, () => problem(404, 'template.notFound', 'No such template.')));
    open('/weblogs/alpha/templates/t9');
    expect(await screen.findByText('No such template.')).toBeInTheDocument();
  });

  it('offers back text typed before the session ended', async () => {
    const user = userEvent.setup();
    const first = open('/weblogs/alpha/templates/t2');
    const source = await screen.findByLabelText('Template source');
    fireEvent.change(source, { target: { value: 'lost work' } });
    await waitFor(() => { expect(Object.keys(window.localStorage).some((k) => k.includes('template:alpha:t2'))).toBe(true); }, { timeout: 4000 });
    first.unmount();
    open('/weblogs/alpha/templates/t2');
    await user.click(await screen.findByRole('button', { name: /restore/i }));
    expect(screen.getByLabelText('Template source')).toHaveValue('lost work');
  });

  it('is not there for an author', async () => {
    world.permission = 'POST';
    open('/weblogs/alpha/templates/t2');
    expect(await screen.findByText('That page does not exist.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Template source')).toBeNull();
  });
});
