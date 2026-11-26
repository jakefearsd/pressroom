import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { editorBoot } from '../support';
import { API, fakeServer, handlers, problem, recording, renderPeople, seen, server } from './harness';

const { http, HttpResponse } = handlers;
const profile = { userName: 'jake', screenName: 'Jake', fullName: 'Jake Fear', emailAddress: 'jake@example.com',
  locale: 'en_US', timeZone: 'Europe/London', kind: 'PERSON' };

fakeServer();
beforeEach(() => {
  server.use(
    http.get(`${API}/me/profile`, () => HttpResponse.json(profile)),
    http.get(`${API}/meta/weblog-options`, () => HttpResponse.json({ themes: [],
      locales: [{ id: 'en_US', name: 'English (United States)' }, { id: 'de', name: 'German' }], timeZones: ['Europe/London', 'UTC'] }))
  );
});

describe('Profile', () => {
  it('shows the signed-in account as the JSP form does, for an editor too', async () => {
    renderPeople('/profile', editorBoot);
    expect(await screen.findByText('Your name, email, password and time zone.')).toHaveClass('subtitle');
    expect(await screen.findByLabelText('Username')).toHaveValue('jake');
    expect(screen.getByLabelText('Username')).toHaveAttribute('readonly');
    expect(screen.getByLabelText('Screen name')).toHaveValue('Jake');
    expect(screen.getByLabelText('Full name')).toHaveValue('Jake Fear');
    expect(screen.getByLabelText('Email')).toHaveValue('jake@example.com');
    expect(screen.getByLabelText('Locale')).toHaveValue('en_US');
    expect(screen.getByLabelText('Time zone')).toHaveValue('Europe/London');
    expect(screen.getByText('Needed only to change your password.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Your API tokens' })).toHaveAttribute('href', '/profile/tokens');
    expect(screen.getByRole('link', { name: 'Cancel' })).toHaveAttribute('href', '/');
  });

  it('saves the fields and, with no new password, calls nothing else', async () => {
    const user = userEvent.setup({ delay: null });
    server.use(http.patch(`${API}/me/profile`, recording(() => HttpResponse.json(profile))));
    const { router } = renderPeople('/profile', editorBoot);
    const name = await screen.findByLabelText('Screen name');
    await user.clear(name);
    await user.type(name, 'Jake F');
    await user.selectOptions(screen.getByLabelText('Locale'), 'de');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    // Where the JSP landed: the app home, with its "changes saved" message.
    await waitFor(() => { expect(router.state.location.pathname).toBe('/'); });
    expect(router.state.location.state).toEqual({ message: 'Changes saved' });
    expect(seen.filter((s) => s.method !== 'GET')).toEqual([{ method: 'PATCH', path: '/me/profile', body: {
      screenName: 'Jake F', fullName: 'Jake Fear', emailAddress: 'jake@example.com', locale: 'de', timeZone: 'Europe/London' } }]);
  });

  it('changes the password with the current one after saving the fields', async () => {
    const user = userEvent.setup({ delay: null });
    server.use(
      http.patch(`${API}/me/profile`, recording(() => HttpResponse.json(profile))),
      http.put(`${API}/me/password`, recording(() => new HttpResponse(null, { status: 204 })))
    );
    const { router } = renderPeople('/profile', editorBoot);
    await user.type(await screen.findByLabelText('Current password'), 'oldpassword');
    await user.type(screen.getByLabelText('Password'), 'newpassword1');
    await user.type(screen.getByLabelText('Confirm password'), 'newpassword1');
    expect(screen.getByText('Passwords match')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => { expect(router.state.location.pathname).toBe('/'); });
    expect(seen.filter((s) => s.method !== 'GET').map((s) => s.method)).toEqual(['PATCH', 'PUT']);
    expect(seen.find((s) => s.method === 'PUT')?.body).toEqual({ currentPassword: 'oldpassword', newPassword: 'newpassword1' });
  });

  it('hides the password fields for a single-sign-on account, as the JSP did', async () => {
    server.use(http.get(`${API}/me/profile`, () => HttpResponse.json({ ...profile, sso: true })));
    renderPeople('/profile', editorBoot);
    expect(await screen.findByLabelText('Screen name')).toBeInTheDocument();
    expect(screen.queryByLabelText('Current password')).toBeNull();
    expect(screen.queryByLabelText('Password')).toBeNull();
    expect(screen.queryByLabelText('Confirm password')).toBeNull();
    expect(screen.getByLabelText('Locale')).toBeInTheDocument();
  });

  it('refuses mismatched passwords on the confirm field without calling the API', async () => {
    const user = userEvent.setup({ delay: null });
    renderPeople('/profile', editorBoot);
    await user.type(await screen.findByLabelText('Password'), 'newpassword1');
    await user.type(screen.getByLabelText('Confirm password'), 'different');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    const confirm = screen.getByLabelText('Confirm password');
    await waitFor(() => { expect(confirm).toHaveAttribute('aria-invalid', 'true'); });
    expect(confirm).toHaveClass('is-invalid');
    expect(confirm).toHaveFocus();
    expect(screen.getByRole('alert')).toHaveTextContent('The passwords do not match.');
    expect(seen.filter((s) => s.method !== 'GET')).toEqual([]);
  });

  it('puts a refused email on the email field with the problem in place', async () => {
    const user = userEvent.setup({ delay: null });
    server.use(http.patch(`${API}/me/profile`, () => problem(400, 'user.emailInvalid', 'Enter a valid email address.',
      [{ field: 'emailAddress', message: 'Enter a valid email address.' }])));
    renderPeople('/profile', editorBoot);
    await user.click(await screen.findByRole('button', { name: 'Save' }));
    const email = await screen.findByLabelText('Email');
    await waitFor(() => { expect(email).toHaveAttribute('aria-invalid', 'true'); });
    expect(email).toHaveClass('is-invalid');
    expect(email).toHaveFocus();
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a valid email address.');
  });

  it('shows a wrong current password on its field in the JSP wording, after the fields were saved', async () => {
    const user = userEvent.setup({ delay: null });
    server.use(
      http.patch(`${API}/me/profile`, () => HttpResponse.json(profile)),
      http.put(`${API}/me/password`, () => problem(403, 'profile.currentPasswordWrong', 'The current password is not correct.'))
    );
    renderPeople('/profile', editorBoot);
    await user.type(await screen.findByLabelText('Current password'), 'nope');
    await user.type(screen.getByLabelText('Password'), 'newpassword1');
    await user.type(screen.getByLabelText('Confirm password'), 'newpassword1');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    const current = screen.getByLabelText('Current password');
    await waitFor(() => { expect(current).toHaveAttribute('aria-invalid', 'true'); });
    expect(current).toHaveFocus();
    expect(screen.getByRole('alert')).toHaveTextContent('To change your password, enter your current password correctly.');
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('puts a too-short new password on the password field', async () => {
    const user = userEvent.setup({ delay: null });
    server.use(
      http.patch(`${API}/me/profile`, () => HttpResponse.json(profile)),
      http.put(`${API}/me/password`, () => problem(400, 'password.tooShort', 'A password needs at least 8 characters.',
        [{ field: 'newPassword', message: 'A password needs at least 8 characters.' }]))
    );
    renderPeople('/profile', editorBoot);
    await user.type(await screen.findByLabelText('Current password'), 'oldpassword');
    await user.type(screen.getByLabelText('Password'), 'short');
    await user.type(screen.getByLabelText('Confirm password'), 'short');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => { expect(screen.getByLabelText('Password')).toHaveAttribute('aria-invalid', 'true'); });
    expect(screen.getByLabelText('Password')).toHaveFocus();
  });

  it('shows the single-sign-on refusal in the JSP wording', async () => {
    const user = userEvent.setup({ delay: null });
    server.use(
      http.patch(`${API}/me/profile`, () => HttpResponse.json(profile)),
      http.put(`${API}/me/password`, () => problem(400, 'user.ssoBound', 'sso'))
    );
    renderPeople('/profile', editorBoot);
    await user.type(await screen.findByLabelText('Current password'), 'x');
    await user.type(screen.getByLabelText('Password'), 'newpassword1');
    await user.type(screen.getByLabelText('Confirm password'), 'newpassword1');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('This account signs in with single sign-on and has no password.');
  });

  it('shows a profile that cannot be read', async () => {
    server.use(http.get(`${API}/me/profile`, () => problem(401, 'auth.required', 'Sign in.')));
    renderPeople('/profile', editorBoot);
    expect(await screen.findByRole('alert')).toHaveTextContent('Sign in.');
    expect(screen.queryByRole('button', { name: 'Save' })).toBeNull();
  });
});
