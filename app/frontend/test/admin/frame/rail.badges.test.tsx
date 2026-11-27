import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { appRoutes } from '../../../src/admin/router';
import { editorBoot, renderRoutes } from '../support';

const API = 'http://pressroom.test/pressroom/api/v1';
let rows: object[];

const server = setupServer(
  http.get(`${API}/me`, () => HttpResponse.json({
    userName: 'jake', screenName: 'Jake', kind: 'PERSON', globalAdmin: false, tokenScope: {},
    weblogs: [{ handle: 'alpha', name: 'Alpha Blog', permission: 'POST' }]
  })),
  http.get(`${API}/meta`, () => HttpResponse.json({ version: '1', features: { customThemes: true, groupBlogging: true }, limits: {} })),
  http.get(`${API}/me/weblogs`, () => HttpResponse.json({ weblogs: rows })),
  http.get(`${API}/weblogs/alpha`, () => HttpResponse.json({ handle: 'alpha', name: 'Alpha Blog', active: true }))
);
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });

const row = (extra: object) => ({ handle: 'alpha', name: 'Alpha Blog', theme: 'journal', permission: 'POST', actions: ['post'],
  canModerate: true, entryCount: 1, pendingComments: 0, newInquiries: 0, drafts: 0, scheduled: 0, ...extra });
const rail = () => document.getElementById('adminRail') as HTMLElement;

describe('Rail count badges', () => {
  it('badges Comments and Inquiries from the current weblog row, with an accessible name that says what the count is', async () => {
    rows = [row({ pendingComments: 3, newInquiries: 2 }), row({ handle: 'beta', pendingComments: 9, newInquiries: 9 })];
    renderRoutes(appRoutes, '/weblogs/alpha', { boot: editorBoot });
    const comments = await screen.findByRole('link', { name: 'Comments, 3 awaiting approval' });
    expect(comments.querySelector('.rail-badge')).toHaveTextContent('3');
    expect(comments.querySelector('.rail-badge')).toHaveAttribute('aria-hidden', 'true');
    const inquiries = within(rail()).getByRole('link', { name: 'Inquiries, 2 new' });
    expect(inquiries.querySelector('.rail-badge')).toHaveTextContent('2');
    expect(rail().querySelectorAll('.rail-badge')).toHaveLength(2);
  });

  it('shows no badge at 0, and the link keeps its plain name', async () => {
    rows = [row({})];
    renderRoutes(appRoutes, '/weblogs/alpha', { boot: editorBoot });
    expect(await screen.findByRole('link', { name: 'Comments' })).toBeInTheDocument();
    expect(within(rail()).getByRole('link', { name: 'Inquiries' })).toBeInTheDocument();
    expect(rail().querySelector('.rail-badge')).toBeNull();
  });

  it('shows no badge while the counts have not loaded', async () => {
    rows = [];
    renderRoutes(appRoutes, '/weblogs/alpha', { boot: editorBoot });
    expect(await screen.findByRole('link', { name: 'Comments' })).toBeInTheDocument();
    expect(rail().querySelector('.rail-badge')).toBeNull();
  });
});
