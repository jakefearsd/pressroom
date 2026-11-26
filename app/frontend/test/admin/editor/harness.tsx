import { act, screen } from '@testing-library/react';
import { EditorView } from '@codemirror/view';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { appRoutes } from '../../../src/admin/router';
import type { Bootstrap } from '../../../src/admin/bootstrap';
import { editorBoot, renderRoutes } from '../support';

export const API = 'http://pressroom.test/pressroom/api/v1';

export interface Recorded {
  method: string;
  path: string;
  body: unknown;
  search: string;
}

/** What the fake API holds; each test resets it in beforeEach. */
export const state = {
  me: {} as object,
  weblog: {} as object,
  entry: {} as Record<string, unknown>,
  categories: [] as object[],
  pages: [] as object[],
  trashedPages: [] as object[],
  revisions: [] as object[],
  comments: [] as object[],
  requests: [] as Recorded[]
};

export function resetState(): void {
  state.me = { userName: 'jake', screenName: 'Jake', kind: 'PERSON', globalAdmin: false, tokenScope: {},
    weblogs: [{ handle: 'alpha', name: 'Alpha Blog', permission: 'POST' }] };
  state.weblog = { handle: 'alpha', name: 'Alpha Blog', active: true, timeZone: 'Europe/Berlin', locale: 'en_US',
    commentsEnabled: true, newsletterListUuid: 'list-1', theme: 'journal' };
  state.entry = {
    id: 'e1', anchor: 'rome-trip', title: 'Rome trip', summary: '', text: 'Hello **Rome**', contentHash: 'h1', status: 'DRAFT',
    category: 'Trips', tags: ['travel', 'food'], pubTime: '2026-03-01T08:30:00Z', updateTime: '2026-03-01T09:00:00Z',
    permalink: 'https://blog.example.com/alpha/entry/rome-trip', locale: 'en_US', noindex: false, commentsClosed: false,
    pinned: false, pinnedToMain: false, jsonLdType: 'BLOG_POSTING'
  };
  state.categories = [{ id: 'c1', name: 'Trips' }, { id: 'c2', name: 'Food' }];
  state.pages = [{ id: 's1', title: 'Italy', strand: true }, { id: 'p1', title: 'About', strand: false }];
  state.trashedPages = [];
  state.revisions = [];
  state.comments = [];
  state.requests = [];
}

function record(method: string, request: Request, body: unknown) {
  const url = new URL(request.url);
  state.requests.push({ method, path: url.pathname.replace('/pressroom/api/v1', ''), body, search: url.search });
}
const problem = (status: number, code: string, detail: string, errors?: object[]) =>
  HttpResponse.json({ status, code, detail, ...(errors === undefined ? {} : { errors }) },
    { status, headers: { 'Content-Type': 'application/problem+json' } });

export { problem };

/** Override one handler for one test, e.g. a refusal. */
export const server = setupServer(
  http.get(`${API}/me`, () => HttpResponse.json(state.me)),
  http.get(`${API}/meta`, () => HttpResponse.json({ version: '1', siteUrl: 'https://blog.example.com', features: { customThemes: true, groupBlogging: true }, limits: {} })),
  http.get(`${API}/weblogs/:handle`, () => HttpResponse.json(state.weblog)),
  http.get(`${API}/weblogs/:handle/categories`, () => HttpResponse.json(state.categories)),
  http.get(`${API}/weblogs/:handle/pages`, ({ request }) =>
    HttpResponse.json(new URL(request.url).searchParams.get('status') === 'TRASHED' ? state.trashedPages : state.pages)),
  http.get(`${API}/weblogs/:handle/entries/:id`, () => HttpResponse.json(state.entry)),
  http.get(`${API}/weblogs/:handle/entries/:id/revisions`, () => HttpResponse.json(state.revisions)),
  http.get(`${API}/weblogs/:handle/entries/:id/revisions/:rid`, () =>
    HttpResponse.json({ id: 'r1', title: 'Old title', summary: '', text: 'old', diff: '--- a\n+++ b\n@@ -1 +1 @@\n-old\n+Hello **Rome**\n' })),
  http.get(`${API}/weblogs/:handle/comments`, ({ request }) => {
    const status = new URL(request.url).searchParams.get('status');
    return HttpResponse.json({ items: state.comments.filter((c) => (c as { status: string }).status === status), offset: 0, limit: 200, hasMore: false });
  }),
  http.post(`${API}/weblogs/:handle/entries`, async ({ request }) => {
    const body = await request.json();
    record('POST', request, body);
    return HttpResponse.json({ ...state.entry, id: 'new1', ...(body as object), status: (body as { status: string }).status }, { status: 201 });
  }),
  http.patch(`${API}/weblogs/:handle/entries/:id`, async ({ request }) => {
    const body = await request.json() as Record<string, unknown>;
    record('PATCH', request, body);
    return HttpResponse.json({ ...state.entry, ...body, tags: body['tags'] ?? state.entry['tags'], contentHash: 'h2' });
  }),
  http.delete(`${API}/weblogs/:handle/entries/:id`, ({ request }) => {
    record('DELETE', request, null);
    return new HttpResponse(null, { status: 204 });
  }),
  http.post(`${API}/weblogs/:handle/entries/:id/send-newsletter`, ({ request }) => {
    record('POST', request, null);
    return HttpResponse.json({ ...state.entry, newsletterSentAt: '2026-03-01T10:00:00Z' });
  }),
  http.post(`${API}/weblogs/:handle/entries/:id/revisions/:rid/restore`, async ({ request }) => {
    record('POST', request, await request.json());
    return HttpResponse.json({ ...state.entry, title: 'Old title', text: 'old', contentHash: 'h3' });
  }),
  http.post(`${API}/weblogs/:handle/entries/:id/revisions/:rid/keep`, ({ request }) => {
    record('POST', request, null);
    return new HttpResponse(null, { status: 204 });
  }),
  http.post(`${API}/weblogs/:handle/entries/:id/revisions/:rid/unkeep`, ({ request }) => {
    record('POST', request, null);
    return new HttpResponse(null, { status: 204 });
  }),
  http.post(`${API}/weblogs/:handle/entries/:id/preview`, async ({ request }) => {
    record('POST', request, await request.json());
    return HttpResponse.json({ html: '<p>rendered</p>' });
  }),
  http.post(`${API}/weblogs/:handle/entries/preview`, async ({ request }) => {
    record('POST', request, await request.json());
    return HttpResponse.json({ html: '<p>rendered new</p>' });
  }),
  // The media chooser's two reads (specific routes before the by-id one).
  http.get(`${API}/weblogs/:handle/media/directories`, () =>
    HttpResponse.json([{ id: 'd1', name: 'default', isPrivate: false, fileCount: 2 }])),
  http.get(`${API}/weblogs/:handle/media`, ({ request }) => {
    const imagesOnly = new URL(request.url).searchParams.get('type') === 'image';
    const files = [
      { id: 'img1', name: 'a.jpg', contentType: 'image/jpeg', length: 10, url: 'https://x/m/img1', directory: 'd1' },
      { id: 'doc1', name: 'guide.pdf', contentType: 'application/pdf', length: 99, url: 'https://x/m/doc1', directory: 'd1' },
      { id: 'img2', name: 'b.jpg', contentType: 'image/jpeg', length: 11, url: 'https://x/b.jpg', directory: 'd1' }
    ];
    return HttpResponse.json({ items: imagesOnly ? files.filter((f) => f.contentType.startsWith('image/')) : files,
      offset: 0, limit: 200, hasMore: false });
  }),
  http.get(`${API}/weblogs/:handle/media/:id`, ({ params }) => HttpResponse.json(
    params['id'] === 'img1' ? { id: 'img1', name: 'a.jpg', contentType: 'image/jpeg', length: 10, url: 'https://x/m/img1' }
      : params['id'] === 'doc1' ? { id: 'doc1', name: 'guide.pdf', contentType: 'application/pdf', length: 99, url: 'https://x/m/doc1' }
        : { code: 'media.notFound', detail: 'No such file.' }, { status: params['id'] === 'gone' ? 404 : 200 })),
  http.post(`${API}/weblogs/:handle/media`, () => HttpResponse.json({ results: [{ fileName: 'p.png', status: 'CREATED', file: { id: 'up1' } }], created: 1, failed: 0 }, { status: 201 }))
);

export const adminBoot: Bootstrap = { ...editorBoot, user: { ...editorBoot.user, globalPermission: 'admin' } };

export function open(path: string, boot: Bootstrap = editorBoot) {
  return renderRoutes(appRoutes, path, { boot });
}

/** The CodeMirror view the editor mounted. */
export function cm(): EditorView {
  const dom = document.querySelector('.cm-editor');
  const view = dom === null ? null : EditorView.findFromDOM(dom as HTMLElement);
  if (view === null) {
    throw new Error('editor not mounted');
  }
  return view;
}

export async function typeInEditor(text: string) {
  await act(async () => {
    const view = cm();
    view.dispatch({ changes: { from: view.state.doc.length, insert: text } });
  });
}

export const titleField = () => screen.getByRole('textbox', { name: 'Title' }) as HTMLTextAreaElement;
export const lastRequest = (method: string) => [...state.requests].reverse().find((r) => r.method === method);
