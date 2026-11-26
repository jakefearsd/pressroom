import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { DirectoryView, MediaView } from '../../../../src/admin/api/media';

export const API = 'http://pressroom.test/pressroom/api/v1';

export interface Call {
  method: string;
  path: string;
  body: unknown;
  csrf: string | null;
}

export const problem = (status: number, code: string, detail: string, errors?: { field: string; message: string }[]) =>
  HttpResponse.json({ status, code, detail, ...(errors ? { errors } : {}) }, { status, headers: { 'Content-Type': 'application/problem+json' } });

export const file = (id: string, over: Partial<MediaView> = {}): MediaView => ({
  id, name: `${id}.jpg`, contentType: 'image/jpeg', length: 2048, width: 800, height: 600, directory: 'd-default',
  url: `http://pressroom.test/pressroom/media/${id}`, altText: 'alt', description: '', copyrightText: '', tags: [], ...over
});

/**
 * An in-memory media API behind MSW: folders, files, filters, and every write applied so a
 * screen's refetch shows it. `refuse` makes one route answer a problem+json instead.
 */
export function mediaServer(initial: { me?: object } = {}) {
  const state = {
    me: initial.me ?? { userName: 'jake', screenName: 'Jake', kind: 'PERSON', globalAdmin: false, tokenScope: {},
      weblogs: [{ handle: 'alpha', name: 'Alpha Blog', permission: 'POST' }] },
    dirs: [
      { id: 'd-default', name: 'default', isPrivate: false, fileCount: 0 },
      { id: 'd-trips', name: 'trips', isPrivate: false, fileCount: 0 }
    ] as DirectoryView[],
    files: [] as MediaView[],
    calls: [] as Call[],
    refusals: new Map<string, Response>(),
    uploadStatus: 'created'
  };
  const counts = () => state.dirs.map((d) => ({ ...d, fileCount: state.files.filter((f) => f.directory === d.id).length }));
  const record = async (request: Request, path: string) => {
    const type = request.headers.get('content-type') ?? '';
    const body: unknown = type.includes('json') ? await request.clone().json() : type.includes('multipart') ? 'multipart' : undefined;
    state.calls.push({ method: request.method, path, body, csrf: request.headers.get('X-CSRF-TOKEN') });
  };
  const guarded = (key: string, make: (r: Request, p: Record<string, string | readonly string[] | undefined>) => Response | Promise<Response>) =>
    async ({ request, params }: { request: Request; params: Record<string, string | readonly string[] | undefined> }) => {
      await record(request, new URL(request.url).pathname.replace('/pressroom/api/v1/weblogs/alpha', ''));
      const refused = state.refusals.get(key);
      return refused ? refused.clone() : make(request, params);
    };
  const find = (id: unknown) => state.files.find((f) => f.id === id);

  const server = setupServer(
    http.get(`${API}/me`, () => HttpResponse.json(state.me)),
    http.get(`${API}/meta`, () => HttpResponse.json({ version: '1', siteUrl: 'https://x.example', features: { customThemes: true, groupBlogging: true }, limits: {} })),
    http.get(`${API}/weblogs/:h`, () => HttpResponse.json({ handle: 'alpha', name: 'Alpha Blog', active: true, timeZone: 'UTC' })),
    http.get(`${API}/weblogs/alpha/media/directories`, guarded('GET dirs', () => HttpResponse.json(counts()))),
    http.post(`${API}/weblogs/alpha/media/directories`, guarded('POST dirs', async (r) => {
      const { name } = (await r.clone().json()) as { name: string };
      const created = { id: `d-${name}`, name, isPrivate: false, fileCount: 0 };
      state.dirs.push(created);
      return HttpResponse.json(created, { status: 201 });
    })),
    http.patch(`${API}/weblogs/alpha/media/directories/:id`, guarded('PATCH dir', async (r, p) => {
      const dir = state.dirs.find((d) => d.id === p['id']);
      if (dir) { dir.isPrivate = ((await r.clone().json()) as { isPrivate: boolean }).isPrivate; }
      return HttpResponse.json(dir);
    })),
    http.delete(`${API}/weblogs/alpha/media/directories/:id`, guarded('DELETE dir', (_r, p) => {
      if (state.files.some((f) => f.directory === p['id'])) { return problem(409, 'directory.notEmpty', 'The folder still holds files.'); }
      state.dirs = state.dirs.filter((d) => d.id !== p['id']);
      return new HttpResponse(null, { status: 204 });
    })),
    http.get(`${API}/weblogs/alpha/media`, guarded('GET list', (r) => {
      const q = new URL(r.url).searchParams;
      let items = state.files.filter((f) => (q.get('directoryId') ?? '') === '' || f.directory === q.get('directoryId'));
      if (q.get('q')) { items = items.filter((f) => (f.name ?? '').startsWith(q.get('q') ?? '')); }
      if (q.get('type')) {
        const type = q.get('type');
        items = items.filter((f) => type === 'other' ? !(f.contentType ?? '').startsWith('image/') : (f.contentType ?? '').startsWith(`${type ?? ''}/`));
      }
      if (q.get('sort') === 'type') { items = [...items].sort((a, b) => (a.contentType ?? '').localeCompare(b.contentType ?? '')); }
      return HttpResponse.json({ items, offset: 0, limit: items.length, hasMore: false });
    })),
    http.post(`${API}/weblogs/alpha/media`, guarded('POST upload', () => {
      if (state.uploadStatus !== 'created') {
        return HttpResponse.json({ created: 0, failed: 1, results: [{ fileName: 'x', status: state.uploadStatus, detail: 'Quota exceeded.' }] }, { status: 207 });
      }
      const made = file(`up${String(state.files.length + 1)}`);
      state.files.push(made);
      return HttpResponse.json({ created: 1, failed: 0, results: [{ fileName: made.name, status: 'created', file: made }] }, { status: 201 });
    })),
    http.post(`${API}/weblogs/alpha/media/:id/crop`, guarded('POST crop', async (r, p) => {
      const body = (await r.clone().json()) as { width: number; height: number };
      const found = find(p['id']);
      if (found) { found.width = body.width; found.height = body.height; }
      return HttpResponse.json(found);
    })),
    http.get(`${API}/weblogs/alpha/media/:id`, guarded('GET file', (_r, p) => {
      const found = find(p['id']);
      return found ? HttpResponse.json(found) : problem(404, 'media.notFound', 'No such media file.');
    })),
    http.patch(`${API}/weblogs/alpha/media/:id`, guarded('PATCH file', async (r, p) => {
      const body = (await r.clone().json()) as Record<string, unknown>;
      const found = find(p['id']);
      if (found) {
        const { clear, directoryId, ...rest } = body;
        Object.assign(found, rest, directoryId === undefined ? {} : { directory: directoryId });
        for (const field of (clear as string[] | undefined) ?? []) { Reflect.deleteProperty(found, field); }
      }
      return HttpResponse.json(found);
    })),
    http.delete(`${API}/weblogs/alpha/media/:id`, guarded('DELETE file', (_r, p) => {
      state.files = state.files.filter((f) => f.id !== p['id']);
      return new HttpResponse(null, { status: 204 });
    }))
  );
  return { server, state };
}
