import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Categories } from '../../../src/admin/screens/Categories';
import { BLOG, meHandler, problem, renderScreen, server, useMockApi } from './harness';

const CATS = [
  { id: 'c1', name: 'News', description: 'What is new', entryCount: 3, image: 'https://img.test/n.png' },
  { id: 'c2', name: "Maiia's Portraits", description: '', entryCount: 0 },
  { id: 'c3', name: 'Travel', description: 'Trips', entryCount: 1 }
];

function list(items: unknown[] = CATS) {
  return http.get(`${BLOG}/categories`, () => HttpResponse.json(items));
}

const show = () => renderScreen('/weblogs/:handle/categories', <Categories />, '/weblogs/blog/categories');
const rowOf = async (name: string) => (await screen.findByText(name)).closest('tr') as HTMLElement;

describe('Categories', () => {
  useMockApi();
  beforeEach(() => { server.use(meHandler('POST'), list()); });

  it('lists name, description and the row actions', async () => {
    show();
    const row = await rowOf('News');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Categories');
    expect(document.querySelector('table#category-table')).not.toBeNull();
    expect(within(row).getByText('What is new')).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: 'Edit: News' })).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: 'Remove: News' })).toBeInTheDocument();
    expect(screen.getByText(/Categories group your entries, and each one gives readers a feed/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add category' })).toHaveAttribute('id', 'addCategoryButton');
  });

  it('shows an invitation, in the page and not the table, for a weblog with no categories', async () => {
    server.use(list([]));
    show();
    expect(await screen.findByText('No categories yet.')).toBeInTheDocument();
    const button = screen.getByRole('button', { name: 'Add a category' });
    expect(button).toHaveAttribute('id', 'addCategoryButton');
    expect(document.querySelector('table#category-table')).not.toBeNull();
    expect(document.querySelector('table#category-table tr')).toBeNull();
    expect(document.querySelector('table#category-table')?.contains(button)).toBe(false);
  });

  it('offers no remove for the only category', async () => {
    server.use(list([CATS[0]]));
    show();
    const row = await rowOf('News');
    expect(within(row).queryByRole('button', { name: /^Remove/ })).toBeNull();
  });

  it('adds a category with name, description and image', async () => {
    const user = userEvent.setup();
    let sent: unknown;
    server.use(http.post(`${BLOG}/categories`, async ({ request }) => {
      sent = await request.json();
      return HttpResponse.json({ id: 'c9', name: 'Food' }, { status: 201 });
    }));
    show();
    await user.click(await screen.findByRole('button', { name: 'Add category' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Add category', { selector: '.modal-title' })).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(within(dialog).getByText('Name is required.')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Name'), 'Food');
    await user.type(within(dialog).getByLabelText('Description'), 'Eating');
    await user.type(within(dialog).getByLabelText('Image URL'), 'https://img.test/f.png');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Category "Food" created')).toBeInTheDocument();
    expect(sent).toEqual({ name: 'Food', description: 'Eating', image: 'https://img.test/f.png' });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('refuses a bad image URL before it is sent, and saves on Enter', async () => {
    const user = userEvent.setup();
    let calls = 0;
    server.use(http.post(`${BLOG}/categories`, () => { calls++; return HttpResponse.json({ id: 'c9', name: 'Food' }, { status: 201 }); }));
    show();
    await user.click(await screen.findByRole('button', { name: 'Add category' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText('Name'), 'Food');
    await user.type(within(dialog).getByLabelText('Image URL'), 'not a url');
    expect(within(dialog).getByText('Image URL is invalid')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Save' })).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Image URL'), '{Enter}');
    expect(calls).toBe(0);
    await user.clear(within(dialog).getByLabelText('Image URL'));
    await user.type(within(dialog).getByLabelText('Name'), '{Enter}');
    await waitFor(() => { expect(calls).toBe(1); });
  });

  it('puts the server\'s refusal on the field and in the dialog', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${BLOG}/categories`, () => problem(409, 'category.nameInUse', 'A category named "News" already exists.', [{ field: 'name', message: 'Already taken.' }])));
    show();
    await user.click(await screen.findByRole('button', { name: 'Add category' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText('Name'), 'News');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('A category named "News" already exists.');
    expect(within(dialog).getByLabelText('Name')).toHaveAttribute('aria-invalid', 'true');
    expect(within(dialog).getByText('Already taken.')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('edits a category, sending a blank image to remove it', async () => {
    const user = userEvent.setup();
    let sent: unknown;
    server.use(http.patch(`${BLOG}/categories/c1`, async ({ request }) => {
      sent = await request.json();
      return HttpResponse.json({ id: 'c1', name: 'Headlines' });
    }));
    show();
    await user.click(within(await rowOf('News')).getByRole('button', { name: 'Edit: News' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Edit category', { selector: '.modal-title' })).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Name')).toHaveValue('News');
    expect(within(dialog).getByLabelText('Image URL')).toHaveValue('https://img.test/n.png');
    await user.clear(within(dialog).getByLabelText('Name'));
    await user.type(within(dialog).getByLabelText('Name'), 'Headlines');
    await user.clear(within(dialog).getByLabelText('Image URL'));
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Category "Headlines" updated')).toBeInTheDocument();
    expect(sent).toEqual({ name: 'Headlines', description: 'What is new', image: '' });
  });

  it('cancels without saving', async () => {
    const user = userEvent.setup();
    show();
    await user.click(within(await rowOf('News')).getByRole('button', { name: 'Edit: News' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('deletes an unused category without asking where its entries go', async () => {
    const user = userEvent.setup();
    let url = '';
    server.use(http.delete(`${BLOG}/categories/c2`, ({ request }) => { url = request.url; return new HttpResponse(null, { status: 204 }); }));
    show();
    await user.click(within(await rowOf("Maiia's Portraits")).getByRole('button', { name: "Remove: Maiia's Portraits" }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('This category has no entries, so nothing else changes.')).toBeInTheDocument();
    expect(within(dialog).queryByLabelText('Move its entries to')).toBeNull();
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('Category "Maiia\'s Portraits" deleted')).toBeInTheDocument();
    expect(new URL(url).searchParams.get('moveTo')).toBeNull();
  });

  it('moves the entries of a used category to the chosen one, never offering the category itself', async () => {
    const user = userEvent.setup();
    let url = '';
    server.use(http.delete(`${BLOG}/categories/c1`, ({ request }) => { url = request.url; return new HttpResponse(null, { status: 204 }); }));
    show();
    await user.click(within(await rowOf('News')).getByRole('button', { name: 'Remove: News' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(/This category is in use\./)).toBeInTheDocument();
    const select = within(dialog).getByLabelText('Move its entries to');
    expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual(["Maiia's Portraits", 'Travel']);
    await user.selectOptions(select, 'Travel');
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }));
    await screen.findByText('Category "News" deleted');
    expect(new URL(url).searchParams.get('moveTo')).toBe('c3');
  });

  it('shows a refused delete in the dialog', async () => {
    const user = userEvent.setup();
    server.use(http.delete(`${BLOG}/categories/c1`, () => problem(409, 'category.hasEntries', 'This category has entries.')));
    show();
    await user.click(within(await rowOf('News')).getByRole('button', { name: 'Remove: News' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));
    expect(await within(screen.getByRole('dialog')).findByRole('alert')).toHaveTextContent('This category has entries.');
  });

  it('shows a failed list in place', async () => {
    server.use(http.get(`${BLOG}/categories`, () => problem(403, 'auth.forbidden', 'No.')));
    show();
    expect(await screen.findByRole('alert')).toHaveTextContent('No.');
  });
});
