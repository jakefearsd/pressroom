import { readBootstrap } from '../../src/admin/bootstrap';
import { sample, shellDocument } from './fixtures';

describe('readBootstrap', () => {
  it('reads the block, including a screen name that would break out of a script', () => {
    expect(readBootstrap(shellDocument(JSON.stringify(sample)))).toEqual(sample);
  });

  it('fails when the block is missing', () => {
    expect(() => readBootstrap(shellDocument(null))).toThrow('Missing #pressroom-bootstrap');
  });

  it.each([
    ['not an object', '"x"'],
    ['a missing string', JSON.stringify({ ...sample, version: undefined })],
    ['a bad csrf', JSON.stringify({ ...sample, csrf: { headerName: 'X' } })],
    ['a bad user', JSON.stringify({ ...sample, user: null })],
    ['an unknown permission', JSON.stringify({ ...sample, user: { ...sample.user, globalPermission: 'root' } })]
  ])('rejects %s', (_name, json) => {
    expect(() => readBootstrap(shellDocument(json))).toThrow('Malformed');
  });
});
