import { render, screen } from '@testing-library/react';
import { ApiError } from '../../../src/admin/api/problem';
import { useFieldErrors } from '../../../src/admin/forms/useFieldErrors';

function Form({ error, idFor }: { error: unknown; idFor?: (field: string) => string }) {
  const errors = useFieldErrors(error, idFor);
  return (
    <form>
      <input aria-label="Title" {...errors.props('title', 'form-control')} />
      <input aria-label="Slug" {...errors.props('slug')} />
      <p>{errors.message('title') ?? 'no title message'}</p>
      <p>{errors.invalid('slug') ? 'slug bad' : 'slug ok'}</p>
    </form>
  );
}

const problem = new ApiError(422, 'request.invalidFields', 'Fix it.', [
  { field: 'slug', message: 'Taken' }, { field: 'title', message: 'Too long' }, { field: 'title', message: 'Second' },
  { field: 'absent', message: 'Not on this form' }, { message: 'No field' }
]);

describe('useFieldErrors', () => {
  it('marks each named control invalid for the eye and for assistive tech, and focuses the first', () => {
    render(<Form error={problem} />);
    const title = screen.getByLabelText('Title');
    expect(title).toHaveClass('form-control', 'is-invalid');
    expect(title).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Slug')).toHaveClass('is-invalid');
    expect(screen.getByLabelText('Slug')).toHaveFocus();
    expect(screen.getByText('Too long')).toBeInTheDocument();
    expect(screen.getByText('slug bad')).toBeInTheDocument();
  });

  it('leaves controls alone without field errors, and ignores a non-problem error', () => {
    const { rerender } = render(<Form error={null} />);
    expect(screen.getByLabelText('Title')).not.toHaveClass('is-invalid');
    expect(screen.getByLabelText('Title')).not.toHaveAttribute('aria-invalid');
    expect(screen.getByLabelText('Slug')).toHaveAttribute('class', '');
    expect(screen.getByText('no title message')).toBeInTheDocument();
    rerender(<Form error={new Error('boom')} />);
    expect(screen.getByLabelText('Title')).not.toHaveClass('is-invalid');
  });

  it('maps request fields to control ids and skips a field with no control', () => {
    render(<Form error={new ApiError(422, 'c', '', [{ field: 'gone', message: 'x' }, { field: 'title', message: 'y' }])}
                 idFor={(field) => `bean.${field}`} />);
    expect(document.getElementById('bean.title')).toHaveFocus();
    expect(document.getElementById('bean.title')).toHaveClass('is-invalid');
  });
});
