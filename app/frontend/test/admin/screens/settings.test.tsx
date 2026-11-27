import '@testing-library/jest-dom/vitest';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { appRoutes } from '../../../src/admin/router';
import { buildPatch, valuesOf } from '../../../src/admin/screens/WeblogSettings';
import { adminBoot, editorBoot, renderRoutes } from '../support';
import { API, alpha, me, meta, options, problem, record, type Seen } from './weblogFixtures';

let seen: Seen[];
let current: Record<string, unknown>;
let who: object;
let businessesOk: boolean;
let pagesOk: boolean;
const server = setupServer(
  http.get(`${API}/me`, () => HttpResponse.json(who)),
  http.get(`${API}/meta`, () => HttpResponse.json(meta())),
  http.get(`${API}/meta/weblog-options`, () => HttpResponse.json(options)),
  http.get(`${API}/weblogs/alpha`, () => HttpResponse.json(current)),
  http.get(`${API}/weblogs/alpha/businesses`, () => businessesOk
    ? HttpResponse.json({ items: [{ id: 'b1', name: 'Hotel Co', businessType: 'LocalBusiness' }, { id: 'b2', name: 'Cafe', businessType: 'Organization' }],
      offset: 0, limit: 200, hasMore: false, total: 2 })
    : problem('auth.forbidden', 'nope', undefined, 403)),
  http.get(`${API}/weblogs/alpha/pages`, () => pagesOk
    ? HttpResponse.json([{ id: 'p1', title: 'Welcome', status: 'PUBLISHED' }, { id: 'p2', title: 'Draft one', status: 'DRAFT' }])
    : problem('auth.forbidden', 'nope', undefined, 403)),
  http.patch(`${API}/weblogs/alpha`, async ({ request }) => {
    await record(seen, request);
    const body = seen[seen.length - 1]?.body as Record<string, unknown>;
    if (body['entryDisplayCount'] === 999) {
      return problem('entryDisplayCount.outOfRange', 'Check the form.', [{ field: 'entryDisplayCount', message: 'More than the site allows.' },
        { field: 'somethingElse', message: 'Unplaced field message.' }]);
    }
    const { clear, ...rest } = body;
    const removed = new Set((clear as string[] | undefined) ?? []);
    current = Object.fromEntries(Object.entries({ ...current, ...rest }).filter(([key]) => !removed.has(key)));
    return HttpResponse.json(current);
  })
);
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });
beforeEach(() => {
  seen = [];
  current = { ...alpha };
  who = me();
  businessesOk = true;
  pagesOk = true;
});

const open = async (boot = editorBoot) => {
  renderRoutes(appRoutes, '/weblogs/alpha/settings', { boot });
  return screen.findByLabelText('Title');
};

describe('WeblogSettings', () => {
  it('shows every group and field of the JSP form with the stored values', async () => {
    const title = await open();
    expect(title).toHaveValue('Alpha Blog');
    expect(screen.getByRole('heading', { level: 1, name: 'Settings' })).toBeInTheDocument();
    expect(screen.getByText('alpha', { selector: '.subtitle span' })).toBeInTheDocument();
    for (const heading of ['General', 'Language and time zone', 'Newsletter', 'Business', 'Custom domain']) {
      expect(screen.getByRole('heading', { level: 2, name: heading })).toBeInTheDocument();
    }
    expect(screen.getByLabelText('Tagline')).toHaveValue('Hello');
    expect(screen.getByLabelText('Copyright notice')).toHaveValue('(c) me');
    expect(screen.getByLabelText('Icon')).toHaveValue('/i.png');
    expect(screen.getByLabelText('About this weblog')).toHaveValue('About text');
    expect(screen.getByLabelText('Email address of weblog owner')).toHaveValue('a@example.com');
    expect(screen.getByLabelText('Entries shown per page')).toHaveValue(10);
    expect(screen.getByLabelText(/Weblog is active/)).toBeChecked();
    expect(screen.getByLabelText('Allow comments')).toBeChecked();
    expect(screen.getByLabelText('Email me when a comment needs approval')).not.toBeChecked();
    expect(screen.getByLabelText('Show the share row under posts')).not.toBeChecked();
    expect(screen.getByLabelText('Order posts')).toHaveValue('OLDEST');
    expect(screen.getByLabelText('Page width')).toHaveValue('STANDARD');
    expect(await screen.findByRole('option', { name: 'Welcome' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Draft one' })).toBeNull();
    expect(screen.getByLabelText('Home page')).toHaveValue('p1');
    expect(screen.getByLabelText('Locale')).toHaveValue('en_US');
    expect(screen.getByLabelText('Time zone')).toHaveValue('Europe/Berlin');
    expect(await screen.findByRole('option', { name: 'Hotel Co' })).toBeInTheDocument();
    expect(screen.getByLabelText('Business')).toHaveValue('b1');
    expect(screen.getByLabelText('Place type')).toHaveValue('LodgingBusiness');
    expect(screen.getByLabelText('Town or city')).toHaveValue('Lisbon');
    expect(screen.getByLabelText('Region')).toHaveValue('LX');
    expect(screen.getByLabelText('Country code')).toHaveValue('PT');
    expect(screen.getByLabelText('Latitude')).toHaveValue('38.72');
    expect(screen.getByLabelText('Longitude')).toHaveValue('-9.14');
    expect(screen.getByLabelText('Booking link')).toHaveValue('https://book.example/');
    expect(screen.getByLabelText('Custom domain')).toHaveValue('blog.alpha.example');
    expect(screen.getAllByRole('link', { name: 'https://blog.alpha.example/' })).toHaveLength(2);
    expect(screen.getByLabelText('Newsletter list UUID')).toHaveValue('');
    expect(document.getElementById('subscribe-form-status')).toHaveTextContent('No newsletter list is set');
    expect(document.getElementById('weblogAbsoluteUrl')).toHaveAttribute('href', 'https://blog.alpha.example/');
    expect(document.querySelector('form')).toHaveClass('settings-grid', 'form-stacked');
    expect(screen.getByRole('link', { name: 'Remove weblog' })).toHaveAttribute('href', '/weblogs/alpha/remove');
  });

  it('shows the weblog address the server built (WeblogView.absoluteUrl), and copies it', async () => {
    current = { ...alpha, customDomain: undefined, absoluteUrl: 'https://blog.example.com/alpha' };
    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    await open();
    expect(document.getElementById('weblogAbsoluteUrl')).toHaveAttribute('href', 'https://blog.example.com/alpha');
    expect(screen.queryByRole('link', { name: /^https:\/\/blog\.alpha/ })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Copy to clipboard' }));
    expect(writeText).toHaveBeenCalledWith('https://blog.example.com/alpha');
    await waitFor(() => { expect(document.querySelector('.clipbutton')).toHaveClass('copied'); });
  });

  it('takes the address from the server, not from the site url and the handle, even when they differ', async () => {
    current = { ...alpha, customDomain: undefined, absoluteUrl: 'https://elsewhere.example/blogs/alpha/' };
    await open();
    expect(document.getElementById('weblogAbsoluteUrl')).toHaveAttribute('href', 'https://elsewhere.example/blogs/alpha/');
  });

  it('gives the entries field the site maximum and says it: "At most N per page"', async () => {
    server.use(http.get(`${API}/meta`, () => HttpResponse.json({ ...meta(), maxEntriesPerPage: 25 })));
    await open();
    const count = screen.getByLabelText('Entries shown per page');
    await waitFor(() => { expect(count).toHaveAttribute('max', '25'); });
    expect(count).toHaveAttribute('min', '1');
    expect(screen.getByText('At most 25 per page.')).toBeInTheDocument();
  });

  it.each([
    ['on', 'Subscribe form: on'],
    ['offNotConfigured', 'newsletter service'],
    ['offNoList', 'No newsletter list is set']
  ])('says in words whether readers get the subscribe form: %s', async (status, words) => {
    current = { ...alpha, subscribeForm: status, newsletterListUuid: status === 'offNoList' ? '' : 'list-1' };
    await open();
    expect(document.getElementById('subscribe-form-status')).toHaveTextContent(words);
  });

  it('warns, as the JSP did, when the saved custom domain is outside the wildcard certificate zones', async () => {
    current = { ...alpha, customDomainOutsideCertZone: true };
    await open();
    expect(await screen.findByText(/no wildcard certificate covering that hostname/)).toHaveClass('form-text', 'text-warning');
  });

  it('shows no certificate warning for a domain inside the zones', async () => {
    await open();
    expect(screen.queryByText(/no wildcard certificate/)).toBeNull();
  });

  it('offers no home-page row on the site space, which has no front door', async () => {
    const boot = { ...editorBoot, site: { ...editorBoot.site, siteSpaceHandle: 'alpha' } };
    await open(boot);
    expect(screen.queryByLabelText('Home page')).toBeNull();
    expect(screen.queryByText(/The page list could not be loaded/)).toBeNull();
  });

  it('saves only what changed, blanks become clear, and says so; the PATCH carries the CSRF header', async () => {
    const user = userEvent.setup();
    await open();
    await screen.findByRole('option', { name: 'Hotel Co' });
    await user.clear(screen.getByLabelText('Title'));
    await user.type(screen.getByLabelText('Title'), 'Renamed');
    await user.clear(screen.getByLabelText('Tagline'));
    await user.click(screen.getByLabelText('Email me when a comment needs approval'));
    await user.selectOptions(screen.getByLabelText('Order posts'), 'NEWEST');
    await user.selectOptions(screen.getByLabelText('Home page'), '');
    await user.selectOptions(screen.getByLabelText('Business'), 'b2');
    await user.clear(screen.getByLabelText('Entries shown per page'));
    await user.type(screen.getByLabelText('Entries shown per page'), '12');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Settings saved.')).toBeInTheDocument();
    expect(seen).toHaveLength(1);
    expect(seen[0]).toEqual({ method: 'PATCH', path: '/weblogs/alpha', csrf: 'tok', body: {
      name: 'Renamed', entryOrder: 'NEWEST', commentNotify: true, businessId: 'b2',
      entryDisplayCount: 12, clear: ['tagline', 'frontPage']
    } });
    expect(screen.getByLabelText('Tagline')).toHaveValue('');
    // a second save with nothing changed sends nothing and still confirms
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(seen).toHaveLength(1);
  });

  it('clears latitude and longitude together, and refuses a non-number before it leaves the page', async () => {
    const user = userEvent.setup();
    await open();
    await user.clear(screen.getByLabelText('Latitude'));
    await user.type(screen.getByLabelText('Latitude'), 'north');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(screen.getByLabelText('Latitude')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Latitude')).toHaveFocus();
    expect(screen.getByText(/Latitude must be a number/, { selector: '.invalid-feedback' })).toBeInTheDocument();
    expect(seen).toHaveLength(0);

    await user.clear(screen.getByLabelText('Latitude'));
    await user.clear(screen.getByLabelText('Longitude'));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('Settings saved.');
    expect(seen[0]?.body).toEqual({ clear: ['placeLat', 'placeLng'] });

    await user.type(screen.getByLabelText('Latitude'), '41.5');
    await user.type(screen.getByLabelText('Longitude'), '-8.4');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => { expect(seen).toHaveLength(2); });
    expect(seen[1]?.body).toEqual({ placeLat: 41.5, placeLng: -8.4 });
  });

  it('shows a refusal in place: the field marked and focused with its message, the rest in the banner', async () => {
    const user = userEvent.setup();
    await open();
    await user.clear(screen.getByLabelText('Entries shown per page'));
    await user.type(screen.getByLabelText('Entries shown per page'), '999');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    const count = screen.getByLabelText('Entries shown per page');
    await waitFor(() => { expect(count).toHaveAttribute('aria-invalid', 'true'); });
    expect(count).toHaveClass('form-control', 'is-invalid');
    // focus moves in an effect after the commit that marks the field: wait for it too
    await waitFor(() => { expect(count).toHaveFocus(); });
    expect(screen.getByText('More than the site allows.', { selector: '.invalid-feedback' })).toBeInTheDocument();
    const banner = document.getElementById('errors') as HTMLElement;
    expect(banner).toHaveTextContent('Check the form.');
    expect(banner).toHaveTextContent('Unplaced field message.');
    expect(banner).not.toHaveTextContent('More than the site allows.');
    expect(screen.queryByText('Settings saved.')).toBeNull();
    // what was typed stays
    expect(count).toHaveValue(999);
  });

  it('a clean save after a refusal clears the refusal: no error, no marked field, and says saved', async () => {
    const user = userEvent.setup();
    await open();
    await user.clear(screen.getByLabelText('Entries shown per page'));
    await user.type(screen.getByLabelText('Entries shown per page'), '999');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => { expect(screen.getByLabelText('Entries shown per page')).toHaveAttribute('aria-invalid', 'true'); });

    await user.clear(screen.getByLabelText('Entries shown per page'));
    await user.type(screen.getByLabelText('Entries shown per page'), '10');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Settings saved.')).toBeInTheDocument();
    expect(document.getElementById('errors')).toBeNull();
    expect(screen.getByLabelText('Entries shown per page')).not.toHaveAttribute('aria-invalid');
  });

  it('keeps the current business and front page when their lists cannot load, and says so', async () => {
    businessesOk = false;
    pagesOk = false;
    const user = userEvent.setup();
    await open();
    expect(await screen.findByText(/The list of businesses could not be loaded/)).toBeInTheDocument();
    expect(await screen.findByText(/The page list could not be loaded/)).toBeInTheDocument();
    expect(screen.getByLabelText('Business')).toBeDisabled();
    expect(screen.getByLabelText('Business')).toHaveDisplayValue('Current business (kept)');
    expect(screen.queryByLabelText('Home page')).toBeNull();
    await user.clear(screen.getByLabelText('Title'));
    await user.type(screen.getByLabelText('Title'), 'X');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('Settings saved.');
    expect(seen[0]?.body).toEqual({ name: 'X' });
  });

  it('says None for an unavailable business list when the weblog has no business', async () => {
    businessesOk = false;
    current = { ...alpha, businessId: undefined };
    await open();
    await waitFor(() => { expect(screen.getByLabelText('Business')).toHaveDisplayValue('None'); });
  });

  it('shows a member who is not a weblog admin the refusal, not the form; a site admin gets the form', async () => {
    who = me('POST');
    renderRoutes(appRoutes, '/weblogs/alpha/settings', { boot: editorBoot });
    expect(await screen.findByText('Access denied.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Title')).toBeNull();
  });

  it('lets a site admin who is no member edit any weblog', async () => {
    who = { ...me('POST', true), weblogs: [] };
    await open(adminBoot);
    expect(screen.getByLabelText('Title')).toHaveValue('Alpha Blog');
  });

  it('reports a weblog that cannot be read', async () => {
    server.use(http.get(`${API}/weblogs/alpha`, () => problem('weblog.notFound', 'No such weblog.', undefined, 404)));
    renderRoutes(appRoutes, '/weblogs/alpha/settings', { boot: editorBoot });
    expect((await screen.findAllByText('No such weblog.')).length).toBeGreaterThan(0);
  });

  it('marks the section whose heading scrolls into the band, with an IntersectionObserver', async () => {
    let callback: IntersectionObserverCallback = () => undefined;
    const observed: string[] = [];
    class FakeObserver {
      constructor(cb: IntersectionObserverCallback) { callback = cb; }
      observe(el: Element) { observed.push(el.id); }
      disconnect() { observed.length = 0; }
    }
    vi.stubGlobal('IntersectionObserver', FakeObserver);
    try {
      await open();
      expect(observed).toContain('settings-business');
      const link = (name: string) => screen.getByRole('link', { name });
      expect(link('General')).toHaveClass('is-current');
      act(() => {
        callback([{ target: document.getElementById('settings-business'), isIntersecting: true } as unknown as IntersectionObserverEntry],
          {} as IntersectionObserver);
      });
      expect(link('Business')).toHaveClass('is-current');
      expect(link('General')).not.toHaveClass('is-current');
      act(() => {
        callback([{ target: document.getElementById('settings-business'), isIntersecting: false } as unknown as IntersectionObserverEntry],
          {} as IntersectionObserver);
      });
      expect(link('Business')).toHaveClass('is-current');
    } finally {
      vi.unstubAllGlobals();
    }
  });
});

describe('buildPatch', () => {
  const before = valuesOf(alpha);
  it('is null when nothing changed, and defaults a sparse weblog to the JSP form defaults', () => {
    expect(buildPatch(before, { ...before })).toBeNull();
    const sparse = valuesOf({});
    expect(sparse).toMatchObject({ name: '', active: false, showMoreFromSite: true, shareRowEnabled: true,
      entryOrder: 'NEWEST', pageWidth: 'WIDE', entryDisplayCount: '', placeLat: '' });
  });

  it('sends a lone latitude as it stands, for the API to refuse', () => {
    expect(buildPatch(before, { ...before, placeLng: '' })).toEqual({ placeLat: 38.72, placeLng: 0 });
  });

  it('puts a new value in the patch and a removed one in clear', () => {
    expect(buildPatch(before, { ...before, icon: '/new.png', about: ' ', customDomain: '' }))
      .toEqual({ icon: '/new.png', clear: ['about', 'customDomain'] });
  });
});

