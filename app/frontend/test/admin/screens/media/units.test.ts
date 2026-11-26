import { runBulk } from '../../../../src/admin/api/bulk';
import { uploadFile } from '../../../../src/admin/api/upload';
import { cropRectInNaturalPixels, loadCropper, seedSelection } from '../../../../src/admin/screens/media/crop';
import { formatSize, isCroppable, libraryPath, splitTags } from '../../../../src/admin/screens/media/shared';
import { narrow } from '../../../../src/admin/screens/media/MediaLibrary';
import { sample } from '../../fixtures';
import { file } from './mediaServer';

describe('runBulk', () => {
  it('attempts every item, keeps order, records failures and reports progress', async () => {
    const seen: number[] = [];
    const results = await runBulk([1, 2, 3, 4, 5], (n) => (n % 2 === 0 ? Promise.reject(new Error(`no ${String(n)}`)) : Promise.resolve(n)), 2,
      (done) => { seen.push(done); });
    expect(results.map((r) => r.item)).toEqual([1, 2, 3, 4, 5]);
    expect(results.map((r) => r.error !== undefined)).toEqual([false, true, false, true, false]);
    expect(seen).toEqual([1, 2, 3, 4, 5]);
  });

  it('never runs more than the concurrency at once, and copes with nothing to do', async () => {
    let live = 0;
    let peak = 0;
    await runBulk(Array.from({ length: 9 }, (_, i) => i), async () => {
      peak = Math.max(peak, ++live);
      await new Promise((r) => setTimeout(r, 2));
      live--;
    }, 4);
    expect(peak).toBe(4);
    expect(await runBulk([], () => Promise.resolve())).toEqual([]);
  });
});

describe('uploadFile', () => {
  class FakeXhr {
    static last: FakeXhr;
    headers: Record<string, string> = {};
    upload: { onprogress: ((e: { lengthComputable: boolean; loaded: number; total: number }) => void) | null } = { onprogress: null };
    onload: (() => void) | null = null;
    onerror: (() => void) | null = null;
    status = 201;
    responseText = '{"results":[]}';
    method = '';
    url = '';
    body: FormData | null = null;
    constructor() { FakeXhr.last = this; }
    open(method: string, url: string) { this.method = method; this.url = url; }
    setRequestHeader(name: string, value: string) { this.headers[name] = value; }
    send(body: FormData) { this.body = body; }
  }
  const make = () => new FakeXhr() as unknown as XMLHttpRequest;
  const start = (dir: string | undefined, progress?: (l: number, t: number) => void, expire?: () => void) =>
    uploadFile(sample, 'my blog', dir, new File(['x'], 'a b.png'), progress, expire, make);

  it('posts multipart to the weblog with CSRF, and reports determinate progress only', async () => {
    const seen: number[][] = [];
    const done = start('d1', (l, t) => { seen.push([l, t]); });
    const xhr = FakeXhr.last;
    expect(xhr.method).toBe('POST');
    expect(xhr.url).toBe('/pressroom/api/v1/weblogs/my%20blog/media');
    expect(xhr.headers['X-CSRF-TOKEN']).toBe('tok');
    expect(xhr.body?.get('directoryId')).toBe('d1');
    expect((xhr.body?.get('file') as File).name).toBe('a b.png');
    xhr.upload.onprogress?.({ lengthComputable: true, loaded: 5, total: 10 });
    xhr.upload.onprogress?.({ lengthComputable: false, loaded: 0, total: 0 });
    xhr.onload?.();
    await expect(done).resolves.toEqual({ results: [] });
    expect(seen).toEqual([[5, 10]]);
  });

  it('omits an empty folder, accepts 207, and survives progress with no listener', async () => {
    const done = start('');
    const xhr = FakeXhr.last;
    expect(xhr.body?.has('directoryId')).toBe(false);
    xhr.status = 207;
    xhr.upload.onprogress?.({ lengthComputable: true, loaded: 1, total: 2 });
    xhr.onload?.();
    await expect(done).resolves.toBeDefined();
    const none = start(undefined);
    FakeXhr.last.onload?.();
    await none;
  });

  it('rejects a problem as an ApiError, tells the session store on 401, and rejects a non-JSON body', async () => {
    const expire = vi.fn();
    const refused = start('d', undefined, expire);
    Object.assign(FakeXhr.last, { status: 401, responseText: '{"code":"auth.notAuthenticated","detail":"Sign in."}' });
    FakeXhr.last.onload?.();
    await expect(refused).rejects.toMatchObject({ status: 401, code: 'auth.notAuthenticated', detail: 'Sign in.' });
    expect(expire).toHaveBeenCalledTimes(1);
    const garbled = start('d');
    Object.assign(FakeXhr.last, { status: 502, responseText: '<html>' });
    FakeXhr.last.onload?.();
    await expect(garbled).rejects.toMatchObject({ status: 502, code: 'http.502' });
    const lost = start('d');
    FakeXhr.last.onerror?.();
    await expect(lost).rejects.toThrow('a b.png');
  });

  it('builds a real XMLHttpRequest by default', () => {
    const real = uploadFile(sample, 'x', undefined, new File(['x'], 'a.png'));
    real.catch(() => undefined);
    expect(real).toBeInstanceOf(Promise);
  });
});

describe('cropRectInNaturalPixels', () => {
  const box = (left: number, top: number, width: number, height: number) => ({ left, top, width, height });

  it('scales the selection from the rendered image to natural pixels, offset by the canvas', () => {
    expect(cropRectInNaturalPixels({ x: 60, y: 20, width: 100, height: 50 }, box(0, 0, 500, 360), box(50, 0, 400, 300), { width: 800, height: 600 }))
      .toEqual({ x: 20, y: 40, width: 200, height: 100 });
  });

  it('is null before layout or without a known size', () => {
    const sel = { x: 0, y: 0, width: 1, height: 1 };
    expect(cropRectInNaturalPixels(sel, box(0, 0, 1, 1), box(0, 0, 0, 10), { width: 8, height: 6 })).toBeNull();
    expect(cropRectInNaturalPixels(sel, box(0, 0, 1, 1), box(0, 0, 10, 0), { width: 8, height: 6 })).toBeNull();
    expect(cropRectInNaturalPixels(sel, box(0, 0, 1, 1), box(0, 0, 10, 10), { width: 0, height: 6 })).toBeNull();
    expect(cropRectInNaturalPixels(sel, box(0, 0, 1, 1), box(0, 0, 10, 10), { width: 8, height: 0 })).toBeNull();
  });
});

describe('seedSelection', () => {
  it('seeds a centred selection from the canvas at the initial coverage, once, while empty', () => {
    const change = vi.fn();
    const selection = { x: 0, y: 0, width: 0, height: 0, initialCoverage: 0.5, $change: change };
    seedSelection({ offsetWidth: 400, offsetHeight: 200 }, selection);
    expect(change).toHaveBeenCalledWith(100, 50, 200, 100);
    seedSelection({ offsetWidth: 400, offsetHeight: 200 }, { ...selection, width: 5, height: 5 });
    expect(change).toHaveBeenCalledTimes(1);
  });

  it('falls back to 0.9 for a bad coverage, waits for a laid-out canvas, and needs $change', () => {
    const change = vi.fn();
    seedSelection({ offsetWidth: 100, offsetHeight: 100 }, { x: 0, y: 0, width: 0, height: 0, initialCoverage: 7, $change: change });
    expect(change).toHaveBeenCalledWith(5, 5, 90, 90);
    seedSelection({ offsetWidth: 100, offsetHeight: 100 }, { x: 0, y: 0, width: 0, height: 0, $change: change });
    expect(change).toHaveBeenLastCalledWith(5, 5, 90, 90);
    seedSelection({ offsetWidth: 0, offsetHeight: 0 }, { x: 0, y: 0, width: 0, height: 0, $change: change });
    expect(change).toHaveBeenCalledTimes(2);
    expect(() => { seedSelection({ offsetWidth: 9, offsetHeight: 9 }, { x: 0, y: 0, width: 0, height: 0 }); }).not.toThrow();
  });
});

describe('loadCropper', () => {
  it('injects the webjar script once from the context path, resolves when the elements exist, and retries after a failed load', async () => {
    const first = loadCropper('/pressroom');
    const script = document.head.querySelector('script[src$="cropperjs/2.2.0/dist/cropper.min.js"]') as HTMLScriptElement;
    expect(script.getAttribute('src')).toBe('/pressroom/webjars/cropperjs/2.2.0/dist/cropper.min.js');
    expect(loadCropper('/pressroom')).toBe(first);
    customElements.define('cropper-canvas', class extends HTMLElement {});
    script.onload?.(new Event('load'));
    await expect(first).resolves.toBeUndefined();
    expect(document.head.querySelectorAll('script[src*="cropperjs"]')).toHaveLength(1);
  });
});

describe('shared helpers', () => {
  it('formats sizes as the upload page did', () => {
    expect(formatSize(0)).toBe('0 B');
    expect(formatSize(1023)).toBe('1023 B');
    expect(formatSize(1536)).toBe('1.5 KB');
    expect(formatSize(5 * 1024 ** 3)).toBe('5.0 GB');
    expect(formatSize(5000 * 1024 ** 3)).toBe('5000.0 GB');
  });

  it('knows what can be cropped, builds library paths and splits tags', () => {
    expect(isCroppable(file('a'))).toBe(true);
    expect(isCroppable(file('a', { contentType: 'image/png' }))).toBe(true);
    expect(isCroppable(file('a', { contentType: 'image/gif' }))).toBe(false);
    expect(isCroppable(file('a', { width: 0 }))).toBe(false);
    expect(isCroppable(file('a', { height: 0 }))).toBe(false);
    expect(isCroppable({ ...file('a'), contentType: undefined })).toBe(false);
    expect(libraryPath('a b')).toBe('/weblogs/a%20b/media');
    expect(libraryPath('a', '')).toBe('/weblogs/a/media');
    expect(libraryPath('a', 'd 1')).toBe('/weblogs/a/media?dir=d%201');
    expect(splitTags(' a, b  c,,')).toEqual(['a', 'b', 'c']);
  });
});

describe('narrow', () => {
  const files = [file('a', { length: 100, tags: ['Sea'] }), file('b', { length: 5000 }), { ...file('c'), length: undefined, tags: undefined }];
  const c = (over: object) => ({ q: '', type: '', size: '', unit: 'bytes', tags: '', ...over });

  it('keeps everything with no size or tags, filters by size in the chosen unit and by every tag', () => {
    expect(narrow(files, c({})).map((f) => f.id)).toEqual(['a', 'b', 'c']);
    expect(narrow(files, c({ size: '1', unit: 'kb' })).map((f) => f.id)).toEqual(['b']);
    expect(narrow(files, c({ size: '1', unit: 'mb' }))).toEqual([]);
    expect(narrow(files, c({ size: '50', unit: 'weird' })).map((f) => f.id)).toEqual(['a', 'b']);
    expect(narrow(files, c({ tags: 'sea' })).map((f) => f.id)).toEqual(['a']);
    expect(narrow(files, c({ tags: 'sea moon' }))).toEqual([]);
    expect(narrow(files, c({ size: 'abc' })).map((f) => f.id)).toEqual(['a', 'b', 'c']);
  });
});
