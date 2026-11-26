import { toApiError, ApiError } from '../../../src/admin/api/problem';

describe('toApiError', () => {
  it('parses code, detail and field errors from problem+json', () => {
    const error = toApiError(422, {
      code: 'request.invalidFields', detail: 'Fix these.',
      errors: [{ field: 'title', message: 'Too long', code: 'x' }, { message: 'General' }, 'junk', null]
    });
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(422);
    expect(error.code).toBe('request.invalidFields');
    expect(error.detail).toBe('Fix these.');
    expect(error.fieldErrors).toEqual([{ field: 'title', message: 'Too long', code: 'x' }, { message: 'General' }]);
    expect(error.messages).toEqual(['Fix these.', 'Too long', 'General']);
    expect(error.message).toBe('Fix these.');
  });

  it('survives a body that is not a problem', () => {
    const error = toApiError(502, undefined);
    expect(error.code).toBe('http.502');
    expect(error.fieldErrors).toEqual([]);
    expect(error.messages).toEqual([]);
    expect(error.message).toBe('http.502 (502)');
    expect(toApiError(500, { errors: 'x' }).fieldErrors).toEqual([]);
  });
});
