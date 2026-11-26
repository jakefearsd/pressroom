import { act, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { call } from '../../../src/admin/api/client';
import { useApi, useSessionExpired } from '../../../src/admin/context';
import { SessionExpiredBar, signInHref } from '../../../src/admin/session/SessionExpiredBar';
import { createSessionStore } from '../../../src/admin/session/store';
import { renderInApp, editorBoot } from '../support';

const server = setupServer(
  http.get('http://pressroom.test/pressroom/api/v1/me', () => HttpResponse.json({ code: 'auth.notAuthenticated' }, { status: 401 }))
);
beforeAll(() => { server.listen({ onUnhandledFrame: 'error' }); });
afterAll(() => { server.close(); });

describe('createSessionStore', () => {
  it('notifies once on expiry and not after unsubscribing', () => {
    const store = createSessionStore();
    const listener = vi.fn();
    const off = store.subscribe(listener);
    store.expire();
    store.expire();
    expect(store.expired()).toBe(true);
    expect(listener).toHaveBeenCalledTimes(1);
    off();
    expect(createSessionStore().expired()).toBe(false);
  });
});

describe('signInHref', () => {
  it('returns to the current URL, path query and hash, under the context path', () => {
    expect(signInHref('/pressroom', { pathname: '/pressroom/pressroom-ui/app/weblogs/x', search: '?a=1', hash: '#h' }))
      .toBe('/pressroom/pressroom-ui/login.rol?return=%2Fpressroom%2Fpressroom-ui%2Fapp%2Fweblogs%2Fx%3Fa%3D1%23h');
  });
});

function Caller() {
  const api = useApi();
  const expired = useSessionExpired();
  return (
    <>
      <button onClick={() => { call(api.GET('/v1/me')).catch(() => undefined); }}>go</button>
      <p>{expired ? 'expired' : 'live'}</p>
      <SessionExpiredBar />
    </>
  );
}

describe('SessionExpiredBar', () => {
  it('stays away until a call answers 401, then offers sign-in back to this URL', async () => {
    window.history.replaceState({}, '', '/pressroom/pressroom-ui/app/weblogs/x/traffic?q=1');
    renderInApp(<Caller />, { boot: { ...editorBoot, contextPath: 'http://pressroom.test/pressroom' } });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    await act(async () => { screen.getByText('go').click(); });

    await waitFor(() => { expect(screen.getByText('expired')).toBeInTheDocument(); });
    const bar = screen.getByRole('alert');
    expect(bar).toHaveClass('draft-bar');
    expect(bar).toHaveTextContent('You have been signed out.');
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href',
      'http://pressroom.test/pressroom/pressroom-ui/login.rol?return=' +
      encodeURIComponent('/pressroom/pressroom-ui/app/weblogs/x/traffic?q=1'));
  });
});
