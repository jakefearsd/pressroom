import { render } from '@testing-library/react';
import { ApiError } from '../../../src/admin/api/problem';
import { Subtitle, markedText } from '../../../src/admin/components/Subtitle';
import { failureText } from '../../../src/admin/lib/errors';

describe('failureText', () => {
  it('prefers a problem\'s detail, then its code, then any error message, then the value', () => {
    expect(failureText(new ApiError(409, 'x.y', 'Detail here', []))).toBe('Detail here');
    expect(failureText(new ApiError(500, 'x.y', '', []))).toBe('x.y (500)');
    expect(failureText(new Error('plain'))).toBe('plain');
    expect(failureText('odd')).toBe('odd');
  });
});

describe('Subtitle', () => {
  it('turns span and b into elements, decodes entities, and leaves other markup as text', () => {
    const { container } = render(<Subtitle text="Manage &amp; <b>bold</b> in <span>blog</span> <i>x</i>" />);
    expect(container.querySelector('p.subtitle')).toHaveTextContent('Manage & bold in blog <i>x</i>');
    expect(container.querySelector('b')).toHaveTextContent('bold');
    expect(container.querySelector('span')).toHaveTextContent('blog');
    expect(markedText('plain')).toEqual(['plain']);
  });
});
