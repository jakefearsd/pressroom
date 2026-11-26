import { act, fireEvent, renderHook, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import type { ReactNode } from 'react';
import { AppProviders } from '../../../src/admin/context';
import { APP_DRAFT_PREFIX, clearAllDrafts, DRAFT_MAX_AGE_MS, readDraft } from '../../../src/admin/session/drafts';
import { DraftBar } from '../../../src/admin/session/DraftBar';
import { flashState, useFlash } from '../../../src/admin/session/flash';
import { SessionExpiryBar } from '../../../src/admin/session/SessionExpiryBar';
import { createSessionStore } from '../../../src/admin/session/store';
import { DRAFT_DEBOUNCE_MS, useDraftKeeper, type DraftKeeper } from '../../../src/admin/session/useDraftKeeper';
import { editorBoot, renderInApp } from '../support';

const KEY = `${APP_DRAFT_PREFIX}jake:entry-1`;
const wrapper = ({ children }: { children: ReactNode }) => <AppProviders boot={editorBoot}>{children}</AppProviders>;

beforeEach(() => { window.localStorage.clear(); });
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

describe('readDraft: what counts as a draft', () => {
  const put = (value: unknown) => { window.localStorage.setItem(KEY, JSON.stringify(value)); };

  it('keeps a draft exactly at the age limit and drops one a millisecond older', () => {
    put({ text: 'x', at: 1000 });
    expect(readDraft(KEY, 1000 + DRAFT_MAX_AGE_MS)).toEqual({ text: 'x', at: 1000 });
    expect(readDraft(KEY, 1000 + DRAFT_MAX_AGE_MS + 1)).toBeNull();
  });

  it.each([
    ['without text', { at: 5 }],
    ['with text that is not a string', { text: 7, at: 5 }],
    ['without a time', { text: 'x' }],
    ['with a time that is not a number', { text: 'x', at: '5' }]
  ])('refuses a stored value %s', (_name, value) => {
    put(value);
    expect(readDraft(KEY, 6)).toBeNull();
  });

  it('refuses stored text that is not an object, and nothing at all', () => {
    put('just a string');
    expect(readDraft(KEY, 6)).toBeNull();
    put(null);
    expect(readDraft(KEY, 6)).toBeNull();
    window.localStorage.removeItem(KEY);
    expect(readDraft(KEY, 6)).toBeNull();
  });

  it('removes every draft key on sign-out, including the last one stored, and nothing else', () => {
    window.localStorage.setItem(`${APP_DRAFT_PREFIX}a`, '1');
    window.localStorage.setItem('unrelated', '2');
    window.localStorage.setItem(`${APP_DRAFT_PREFIX}z`, '3');
    clearAllDrafts();
    expect(window.localStorage.getItem(`${APP_DRAFT_PREFIX}a`)).toBeNull();
    expect(window.localStorage.getItem(`${APP_DRAFT_PREFIX}z`)).toBeNull();
    expect(window.localStorage.getItem('unrelated')).toBe('2');
  });
});

describe('useFlash', () => {
  const read = (state: unknown) => {
    const router = createMemoryRouter([{ path: '/', element: <Probe /> }], { initialEntries: [{ pathname: '/', state }] });
    return renderInApp(<RouterProvider router={router} />);
  };
  function Probe() {
    return <p>[{useFlash() ?? 'none'}]</p>;
  }

  it('reads the message a redirect carried', () => {
    read(flashState('Saved it'));
    expect(screen.getByText('[Saved it]')).toBeInTheDocument();
  });

  it.each([['no state', null], ['a string', 'oops'], ['an object without a message', {}], ['a message that is not text', { message: 5 }]])(
    'finds nothing in %s', (_name, state) => {
      read(state);
      expect(screen.getByText('[none]')).toBeInTheDocument();
    });
});

describe('createSessionStore', () => {
  it('stops telling a listener once it has unsubscribed', () => {
    const store = createSessionStore();
    const kept = vi.fn();
    const dropped = vi.fn();
    store.subscribe(kept);
    store.subscribe(dropped)();
    store.expire();
    expect(kept).toHaveBeenCalledTimes(1);
    expect(dropped).not.toHaveBeenCalled();
  });
});

describe('useDraftKeeper: the cases around an unanswered offer and a save', () => {
  it('does not write while an offer waits, even for edits, and writes once it is answered', () => {
    vi.useFakeTimers();
    window.localStorage.setItem(KEY, JSON.stringify({ text: 'old work', at: Date.now() }));
    const { result, rerender } = renderHook(({ value }) => useDraftKeeper('entry-1', value), { wrapper, initialProps: { value: 'loaded' } });
    rerender({ value: 'edited' });
    act(() => { vi.advanceTimersByTime(DRAFT_DEBOUNCE_MS * 2); });
    expect(JSON.parse(window.localStorage.getItem(KEY) ?? '{}').text).toBe('old work');
    expect(result.current.savedAt).toBeNull();
    act(() => { result.current.discard(); });
    act(() => { vi.advanceTimersByTime(DRAFT_DEBOUNCE_MS); });
    expect(JSON.parse(window.localStorage.getItem(KEY) ?? '{}').text).toBe('edited');
  });

  it('flush writes at once, reports when, and does nothing for unchanged text or while an offer waits', () => {
    vi.useFakeTimers({ now: 5_000_000 });
    const { result, rerender } = renderHook(({ value }) => useDraftKeeper('entry-1', value), { wrapper, initialProps: { value: 'loaded' } });
    act(() => { result.current.flush(); });
    expect(window.localStorage.getItem(KEY)).toBeNull();
    rerender({ value: 'typed' });
    act(() => { result.current.flush(); });
    expect(JSON.parse(window.localStorage.getItem(KEY) ?? '{}')).toEqual({ text: 'typed', at: 5_000_000 });
    expect(result.current.savedAt).toBe(5_000_000);
  });

  it('flush leaves an unanswered offer alone', () => {
    window.localStorage.setItem(KEY, JSON.stringify({ text: 'old work', at: Date.now() }));
    const { result, rerender } = renderHook(({ value }) => useDraftKeeper('entry-1', value), { wrapper, initialProps: { value: 'loaded' } });
    rerender({ value: 'typed' });
    act(() => { result.current.flush(); });
    expect(JSON.parse(window.localStorage.getItem(KEY) ?? '{}').text).toBe('old work');
    expect(result.current.savedAt).toBeNull();
  });

  it('clear drops a waiting offer and forgets when the text was last stored', () => {
    vi.useFakeTimers();
    window.localStorage.setItem(KEY, JSON.stringify({ text: 'old work', at: Date.now() }));
    const waiting = renderHook(() => useDraftKeeper('entry-1', 'loaded'), { wrapper });
    expect(waiting.result.current.offer).not.toBeNull();
    act(() => { waiting.result.current.clear(); });
    expect(waiting.result.current.offer).toBeNull();
    waiting.unmount();

    const { result, rerender } = renderHook(({ value }) => useDraftKeeper('entry-1', value), { wrapper, initialProps: { value: 'loaded' } });
    rerender({ value: 'typed' });
    act(() => { vi.advanceTimersByTime(DRAFT_DEBOUNCE_MS); });
    expect(result.current.savedAt).not.toBeNull();
    act(() => { result.current.clear(); });
    expect(result.current.savedAt).toBeNull();
  });

  it('keys the stored text by the key it was given: a new key starts from its own storage', () => {
    window.localStorage.setItem(`${APP_DRAFT_PREFIX}jake:entry-2`, JSON.stringify({ text: 'two', at: Date.now() }));
    const { result, rerender } = renderHook(({ key }) => useDraftKeeper(key, 'loaded'), { wrapper, initialProps: { key: 'entry-1' } });
    expect(result.current.offer).toBeNull();
    act(() => { result.current.discard(); });
    rerender({ key: 'entry-2' });
    act(() => { result.current.discard(); });
    expect(window.localStorage.getItem(`${APP_DRAFT_PREFIX}jake:entry-2`)).toBeNull();
  });
});

describe('DraftBar', () => {
  it('does not hand the editor anything when there is nothing to restore', () => {
    const keeper: DraftKeeper = { offer: { text: 'x', at: Date.now() }, restore: () => null, discard: vi.fn(), clear: vi.fn(), flush: vi.fn(), savedAt: null };
    const onRestore = vi.fn();
    renderInApp(<DraftBar keeper={keeper} onRestore={onRestore} />);
    fireEvent.click(screen.getByRole('button', { name: 'Restore' }));
    expect(onRestore).not.toHaveBeenCalled();
  });
});

describe('SessionExpiryBar: restarting and cleaning up', () => {
  const boot = { boot: { ...editorBoot, sessionTimeoutSeconds: 1800 } };
  const bar = () => document.getElementById('sessionExpiringBar');

  it('cancels the pending warning when activity restarts the clock', () => {
    vi.useFakeTimers();
    renderInApp(<SessionExpiryBar />, boot);
    act(() => { vi.advanceTimersByTime(1500 * 1000); });
    act(() => { fireEvent.keyDown(document.body); });
    act(() => { vi.advanceTimersByTime(300 * 1000); });
    expect(bar()).toHaveAttribute('hidden');
  });

  it('hears activity even when a control stops the event from bubbling', () => {
    vi.useFakeTimers();
    renderInApp(<><SessionExpiryBar /><button type="button" onClick={(e) => { e.stopPropagation(); }}>Stop</button></>, boot);
    act(() => { vi.advanceTimersByTime(1700 * 1000); });
    expect(bar()).not.toHaveAttribute('hidden');
    act(() => { fireEvent.click(screen.getByRole('button', { name: 'Stop' })); });
    expect(bar()).toHaveAttribute('hidden');
  });

  it('removes exactly the listeners it added', () => {
    const added = vi.spyOn(document, 'addEventListener');
    const removed = vi.spyOn(document, 'removeEventListener');
    const { unmount } = renderInApp(<SessionExpiryBar />, boot);
    const mine = added.mock.calls.filter(([type]) => ['keydown', 'click', 'input'].includes(type));
    expect(mine).toHaveLength(3);
    unmount();
    for (const call of mine) {
      expect(removed).toHaveBeenCalledWith(call[0], call[1], call[2]);
    }
  });
});
