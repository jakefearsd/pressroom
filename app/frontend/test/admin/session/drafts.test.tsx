import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { AppProviders } from '../../../src/admin/context';
import { APP_DRAFT_PREFIX, clearAllDrafts, DRAFT_MAX_AGE_MS, readDraft, removeDraft, storage, writeDraft } from '../../../src/admin/session/drafts';
import { DRAFT_DEBOUNCE_MS, useDraftKeeper } from '../../../src/admin/session/useDraftKeeper';
import { editorBoot } from '../support';

const KEY = `${APP_DRAFT_PREFIX}jake:entry-1`;

function wrapper({ children }: { children: ReactNode }) {
  return <AppProviders boot={editorBoot}>{children}</AppProviders>;
}

beforeEach(() => { window.localStorage.clear(); });
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

describe('useDraftKeeper', () => {
  it('writes nothing until the text differs, then saves it after the debounce', () => {
    vi.useFakeTimers();
    const { rerender } = renderHook(({ value }) => useDraftKeeper('entry-1', value), { wrapper, initialProps: { value: 'loaded' } });
    act(() => { vi.advanceTimersByTime(DRAFT_DEBOUNCE_MS * 2); });
    expect(window.localStorage.getItem(KEY)).toBeNull();

    rerender({ value: 'typed' });
    act(() => { vi.advanceTimersByTime(DRAFT_DEBOUNCE_MS - 1); });
    expect(window.localStorage.getItem(KEY)).toBeNull();
    act(() => { vi.advanceTimersByTime(1); });
    expect(JSON.parse(window.localStorage.getItem(KEY) ?? '{}')).toMatchObject({ text: 'typed' });
  });

  it('offers a stored draft that differs, restores it and then keeps saving', () => {
    vi.useFakeTimers();
    window.localStorage.setItem(KEY, JSON.stringify({ text: 'lost work', at: Date.now() }));
    const { result, rerender } = renderHook(({ value }) => useDraftKeeper('entry-1', value), { wrapper, initialProps: { value: 'loaded' } });
    expect(result.current.offer?.text).toBe('lost work');

    let restored: string | null = null;
    act(() => { restored = result.current.restore(); });
    expect(restored).toBe('lost work');
    expect(result.current.offer).toBeNull();

    rerender({ value: 'lost work and more' });
    act(() => { vi.advanceTimersByTime(DRAFT_DEBOUNCE_MS); });
    expect(JSON.parse(window.localStorage.getItem(KEY) ?? '{}').text).toBe('lost work and more');
  });

  it('does not overwrite an unanswered offer', () => {
    vi.useFakeTimers();
    window.localStorage.setItem(KEY, JSON.stringify({ text: 'lost work', at: Date.now() }));
    const { rerender } = renderHook(({ value }) => useDraftKeeper('entry-1', value), { wrapper, initialProps: { value: 'loaded' } });
    rerender({ value: 'edited meanwhile' });
    act(() => { vi.advanceTimersByTime(DRAFT_DEBOUNCE_MS * 2); });
    expect(JSON.parse(window.localStorage.getItem(KEY) ?? '{}').text).toBe('lost work');
  });

  it('discard removes the stored draft; restore with no offer returns null', () => {
    window.localStorage.setItem(KEY, JSON.stringify({ text: 'lost work', at: Date.now() }));
    const { result } = renderHook(() => useDraftKeeper('entry-1', 'loaded'), { wrapper });
    act(() => { result.current.discard(); });
    expect(window.localStorage.getItem(KEY)).toBeNull();
    expect(result.current.offer).toBeNull();
    let restored: string | null = 'x';
    act(() => { restored = result.current.restore(); });
    expect(restored).toBeNull();
  });

  it('does not offer what the editor already loaded, or a draft older than 30 days', () => {
    window.localStorage.setItem(KEY, JSON.stringify({ text: 'same', at: Date.now() }));
    expect(renderHook(() => useDraftKeeper('entry-1', 'same'), { wrapper }).result.current.offer).toBeNull();
    window.localStorage.setItem(KEY, JSON.stringify({ text: 'old', at: Date.now() - DRAFT_MAX_AGE_MS - 1 }));
    expect(renderHook(() => useDraftKeeper('entry-1', 'new'), { wrapper }).result.current.offer).toBeNull();
  });

  it('clear after a save removes the draft, and a timer from before it cannot bring it back', () => {
    vi.useFakeTimers();
    const { result, rerender } = renderHook(({ value }) => useDraftKeeper('entry-1', value), { wrapper, initialProps: { value: 'loaded' } });
    rerender({ value: 'typed' });
    act(() => { vi.advanceTimersByTime(DRAFT_DEBOUNCE_MS); });
    expect(window.localStorage.getItem(KEY)).not.toBeNull();
    rerender({ value: 'typed more' });
    act(() => { result.current.clear(); });
    act(() => { vi.advanceTimersByTime(DRAFT_DEBOUNCE_MS * 2); });
    expect(window.localStorage.getItem(KEY)).toBeNull();
  });
});

describe('draft storage', () => {
  it('reads nothing for junk and clears only draft keys on sign-out', () => {
    window.localStorage.setItem(KEY, 'not json');
    expect(readDraft(KEY, Date.now())).toBeNull();
    window.localStorage.setItem(KEY, '"a string"');
    expect(readDraft(KEY, Date.now())).toBeNull();
    window.localStorage.setItem(KEY, JSON.stringify({ text: 1, at: 'x' }));
    expect(readDraft(KEY, Date.now())).toBeNull();

    writeDraft(KEY, { text: 't', at: 1 });
    window.localStorage.setItem('pressroom.draft.v1:legacy', '{}');
    window.localStorage.setItem('other', 'keep');
    clearAllDrafts();
    expect(window.localStorage.length).toBe(1);
    expect(window.localStorage.getItem('other')).toBe('keep');
    removeDraft('other');
    expect(window.localStorage.length).toBe(0);
  });

  it('degrades to nothing where storage is blocked', () => {
    vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => { throw new Error('blocked'); });
    expect(storage()).toBeNull();
    expect(readDraft(KEY, 0)).toBeNull();
    expect(() => { writeDraft(KEY, { text: 't', at: 1 }); removeDraft(KEY); clearAllDrafts(); }).not.toThrow();
  });

  it('survives a store that throws on read, write and remove', () => {
    const broken = {
      length: 1, key: () => { throw new Error('no'); }, getItem: () => { throw new Error('no'); },
      setItem: () => { throw new Error('full'); }, removeItem: () => { throw new Error('no'); }
    };
    vi.spyOn(window, 'localStorage', 'get').mockReturnValue(broken as unknown as Storage);
    expect(readDraft(KEY, 0)).toBeNull();
    expect(() => { writeDraft(KEY, { text: 't', at: 1 }); removeDraft(KEY); clearAllDrafts(); }).not.toThrow();
  });
});
