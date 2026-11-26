import { readBootstrap } from '../../src/admin/bootstrap';
import { sample, shellDocument } from './fixtures';

describe('readBootstrap', () => {
  it('reads the block, including a screen name that would break out of a script', () => {
    expect(readBootstrap(shellDocument(JSON.stringify(sample)))).toEqual(sample);
  });

  it('reads every colophon fact the server knows', () => {
    const full = { ...sample, colophon: { copyrightHolder: 'Acme', copyrightYear: '2026', privacyUrl: 'https://x/p',
      shortRevision: 'abc1234', releaseDate: '2026-11-20' } };
    expect(readBootstrap(shellDocument(JSON.stringify(full)))).toEqual(full);
  });

  it('fails when the block is missing', () => {
    expect(() => readBootstrap(shellDocument(null))).toThrow('Missing #pressroom-bootstrap');
  });

  it.each([
    ['not an object', '"x"'],
    ['a missing string', JSON.stringify({ ...sample, version: undefined })],
    ['a bad csrf', JSON.stringify({ ...sample, csrf: { headerName: 'X' } })],
    ['no session timeout', JSON.stringify({ ...sample, sessionTimeoutSeconds: undefined })],
    ['a session timeout that is not a number', JSON.stringify({ ...sample, sessionTimeoutSeconds: '1800' })],
    ['a bad user', JSON.stringify({ ...sample, user: null })],
    ['no site block', JSON.stringify({ ...sample, site: undefined })],
    ['a site name that is not text', JSON.stringify({ ...sample, site: { ...sample.site, name: 1 } })],
    ['no colophon', JSON.stringify({ ...sample, colophon: undefined })],
    ['a colophon fact that is not text', JSON.stringify({ ...sample, colophon: { copyrightYear: '2026', releaseDate: 5 } })],
    ['an unknown permission', JSON.stringify({ ...sample, user: { ...sample.user, globalPermission: 'root' } })]
  ])('rejects %s', (_name, json) => {
    expect(() => readBootstrap(shellDocument(json))).toThrow('Malformed');
  });
});
