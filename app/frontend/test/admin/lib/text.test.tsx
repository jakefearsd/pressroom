import { ApiError } from '../../../src/admin/api/problem';
import { failureText } from '../../../src/admin/lib/errors';

describe('failureText', () => {
  it('prefers a problem\'s detail, then its code, then any error message, then the value', () => {
    expect(failureText(new ApiError(409, 'x.y', 'Detail here', []))).toBe('Detail here');
    expect(failureText(new ApiError(500, 'x.y', '', []))).toBe('x.y (500)');
    expect(failureText(new Error('plain'))).toBe('plain');
    expect(failureText('odd')).toBe('odd');
  });
});
