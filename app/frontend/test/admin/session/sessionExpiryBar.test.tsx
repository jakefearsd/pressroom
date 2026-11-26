import { act, fireEvent, screen } from '@testing-library/react';
import { SessionExpiryBar } from '../../../src/admin/session/SessionExpiryBar';
import { editorBoot, renderInApp } from '../support';

/** SessionExpiryBar.jsp's arithmetic: warn two minutes before the container's inactivity timeout, restart on input. */
const withTimeout = (sessionTimeoutSeconds: number) => ({ boot: { ...editorBoot, sessionTimeoutSeconds } });
const bar = () => document.getElementById('sessionExpiringBar');

beforeEach(() => { vi.useFakeTimers(); });
afterEach(() => { vi.useRealTimers(); });

describe('SessionExpiryBar', () => {
  it('is hidden at first and appears two minutes before the session would end', () => {
    renderInApp(<SessionExpiryBar />, withTimeout(1800));
    expect(bar()).toHaveAttribute('hidden');
    act(() => { vi.advanceTimersByTime((1800 - 120) * 1000 - 1); });
    expect(bar()).toHaveAttribute('hidden');
    act(() => { vi.advanceTimersByTime(2); });
    expect(bar()).not.toHaveAttribute('hidden');
    expect(bar()).toHaveClass('draft-bar');
    expect(screen.getByRole('status')).toHaveTextContent('Your session expires soon');
  });

  it('measures inactivity: a key press, click or input restarts the clock and hides the bar', () => {
    renderInApp(<SessionExpiryBar />, withTimeout(1800));
    for (const fire of [() => fireEvent.keyDown(document.body), () => fireEvent.click(document.body), () => fireEvent.input(document.body)]) {
      act(() => { vi.advanceTimersByTime(1700 * 1000); });
      expect(bar()).not.toHaveAttribute('hidden');
      act(() => { fire(); });
      expect(bar()).toHaveAttribute('hidden');
      act(() => { vi.advanceTimersByTime(1500 * 1000); });
      expect(bar()).toHaveAttribute('hidden');
    }
  });

  it.each([0, 180, 60])('never warns for a session of %i seconds (a bar that is always up teaches people to ignore it)', (seconds) => {
    renderInApp(<SessionExpiryBar />, withTimeout(seconds));
    act(() => { vi.advanceTimersByTime(10_000_000); });
    expect(bar()).toHaveAttribute('hidden');
  });

  it('stops listening when the editor goes away', () => {
    const { unmount } = renderInApp(<SessionExpiryBar />, withTimeout(1800));
    unmount();
    expect(() => { act(() => { vi.advanceTimersByTime(10_000_000); fireEvent.click(document.body); }); }).not.toThrow();
  });
});
