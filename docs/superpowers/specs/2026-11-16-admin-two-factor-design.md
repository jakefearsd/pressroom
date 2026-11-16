# Admin two-factor sign-in — design (parked)

**Status: parked on 2026-11-16.** The approach below was agreed with the
owner, who chose to wait on building it. Nothing has been built and no plan
has been written. To pick this up: re-read the "Open when resumed" section,
confirm the approach still holds, then write the implementation plan.

## Why

Signing in to the admin UI takes a password and nothing else. A market
comparison on 2026-11-15
(`reports/Pressroom self hosted blogging gaps.md`) ranked this as the
largest remaining gap against other self-hosted blogging software: readers
already sign in through OAuth2/OIDC, but the accounts that can publish,
change themes and manage users have a single factor.

## What was agreed

- **Second factor: authenticator-app codes (TOTP, RFC 6238).** Six digits,
  30-second step, one step of clock drift accepted either way.
- **Enrolment from the profile page.** The account scans a QR code (with the
  secret shown as text beside it), then confirms with a current code before
  the secret is saved. Enrolment is not complete until that code verifies.
- **Recovery codes.** Ten one-time codes shown once at enrolment, stored
  hashed, each usable once in place of an authenticator code. The profile
  page can replace the whole set.
- **One global setting turns it on.** Off by default. When on, every
  password sign-in to the admin UI needs the second step. An account with no
  second factor yet is taken through enrolment straight after its password
  is accepted, and reaches nothing else until it finishes.
- **Scope: every account that signs in to the admin UI**, not only global
  admins. An author's account can publish, so it is worth the same
  protection. (Assistant's call; the owner agreed to the approach as a
  whole.)
- **Not affected:** API tokens and the MCP endpoint (a token is already a
  separate credential), and reader sign-in.
- **With the setting off**, an account may still enrol and is then asked for
  its code at sign-in. The setting makes the second step mandatory; it does
  not make it available.
- **Lost device:** recovery codes first. With none left, the operator clears
  the account's second factor from the host with the admin account tool
  (`deploy/admin-account.sh`, see `docs/dev/passwords.md`), and the account
  enrols again at next sign-in. A global admin can do the same for another
  account from the user edit screen.

## Approaches considered

1. **TOTP with recovery codes (chosen).** No outside service, works offline,
   every password manager and authenticator app supports it, and it fits the
   existing form sign-in as one extra step.
2. **Passkeys (WebAuthn).** Stronger against phishing, but needs a stable
   origin per sign-in host. Pressroom serves admin screens on several custom
   domains, which makes the relying-party setup the hard part. Worth doing
   later as an addition to TOTP, not in place of it.
3. **Staff sign-in through an outside identity provider (OIDC).** Moves the
   second factor to the provider. It adds a dependency the admin UI does not
   have today and leaves the password path needing protection anyway.

## Where it touches the code

- `app/src/main/java/com/wikantik/pressroom/boot/SecurityConfig.java`: form
  sign-in at `/pressroom-ui/login.rol`, posting to
  `/pressroom_j_security_check`. The second step sits between an accepted
  password and a fully signed-in session.
- **Remember-me** (`PressroomRememberMeServices`): a remembered session must
  not skip the second step for an account that never completed it. Decide
  when resumed whether a remember-me cookie issued after a full two-step
  sign-in is enough on its own (assistant's suggestion: yes, as today, and
  it is revoked when the second factor changes).
- `CredentialStampFilter`: enrolling, removing or resetting a second factor
  should end the account's other sessions, the way a password change does.
- `ProfileController` and `UserEditController`: enrolment, recovery codes,
  and the admin reset.
- `runtimeConfigDefs.xml`, `userSettings` display group: the global setting.
- A new migration for the per-account secret, its confirmed-at time, and the
  hashed recovery codes. The secret must be stored encrypted or otherwise
  kept out of a plain database dump; decide the key source when resumed.
- `deploy/admin-account.sh`: a command to clear an account's second factor.
- No TOTP library is on the classpath. The algorithm is small (HMAC-SHA1
  over a time counter; `commons-codec` is already present for Base32), so
  the builder may implement it directly with the RFC 6238 test vectors, or
  propose a dependency.

## Non-goals

- Passkeys, SMS or email codes.
- A second factor for API tokens or for readers.
- Per-weblog or per-role policy. The setting is one switch for the site.
- "Trust this device for 30 days" beyond what remember-me already does.

## Acceptance criteria

1. With the setting off and no enrolment, sign-in behaves exactly as today.
2. An enrolled account that gives a correct password reaches no admin page
   until it gives a valid code or an unused recovery code.
3. A wrong, expired or replayed code is refused; the same code is not
   accepted twice inside its time window.
4. A recovery code works once and is refused the second time.
5. With the setting on, an account with no second factor is sent to
   enrolment after its password is accepted, and every other admin URL
   redirects back there until enrolment completes.
6. Enrolment is saved only after a current code verifies.
7. A remember-me cookie never signs in an account past an incomplete second
   step.
8. Repeated wrong codes are rate-limited per account.
9. A request with a valid API token succeeds with the setting on, with no
   second step.
10. Reader sign-in is unchanged with the setting on.
11. The host tool clears an account's second factor, and with the setting on
    that account is taken through enrolment at its next sign-in.
12. Enrolling, removing or resetting a second factor ends the account's
    other sessions.
13. If the second-step check cannot run (for example the secret cannot be
    read), sign-in is refused, not allowed.

## Open when resumed

- Where the key that protects stored secrets comes from (an environment
  variable in `.env` is the likely answer on an image-only deployment).
- Remember-me behaviour, as noted above.
- Whether turning the setting on should warn the admin how many accounts
  have not enrolled yet.
- Expected size: one release, small. One migration, one sign-in step, two
  screens, one host-tool command.
