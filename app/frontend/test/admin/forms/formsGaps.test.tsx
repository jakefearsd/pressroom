import { render, screen } from '@testing-library/react';
import { ApiError, toApiError } from '../../../src/admin/api/problem';
import { FieldFeedback, FormProblem } from '../../../src/admin/forms/FormProblem';
import { useFieldErrors } from '../../../src/admin/forms/useFieldErrors';
import { renderInApp } from '../support';

describe('ApiError.messages and toApiError', () => {
  it('lists the detail first, then each field message, skipping blanks', () => {
    expect(new ApiError(400, 'c', 'Detail', [{ field: 'a', message: '' }, { field: 'b', message: 'B bad' }]).messages)
      .toEqual(['Detail', 'B bad']);
    expect(new ApiError(400, 'c', '', [{ field: 'a', message: '' }]).messages).toEqual([]);
  });

  it('names itself, and builds from a body that is not an object', () => {
    const error = toApiError(502, null);
    expect(error.name).toBe('ApiError');
    expect(error.code).toBe('http.502');
    expect(toApiError(500, 'text').code).toBe('http.500');
    expect(toApiError(409, { code: 'x.y', detail: 'D' })).toMatchObject({ code: 'x.y', detail: 'D', status: 409 });
  });
});

describe('FormProblem', () => {
  const show = (error: unknown, controls: (field: string) => boolean = () => false) => renderInApp(<FormProblem error={error} controls={controls} />);

  it('shows the detail and the message of every field this form has no control for', () => {
    show(new ApiError(400, 'c', 'Fix these', [{ field: 'known', message: 'Shown beside it' }, { field: 'orphan', message: 'No control' }, { message: 'General' }]),
      (field) => field === 'known');
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Fix these');
    expect(alert).toHaveTextContent('No control');
    expect(alert).toHaveTextContent('General');
    expect(alert).not.toHaveTextContent('Shown beside it');
  });

  it('falls back to the error message when there is nothing else to say, and drops blank lines', () => {
    show(new ApiError(500, 'server.error', '', [{ field: 'x', message: '' }]));
    expect(screen.getByRole('alert')).toHaveTextContent('server.error (500)');
  });

  it('passes any other failure to the banner', () => {
    show(new Error('network down'));
    expect(screen.getByRole('alert')).toHaveTextContent('network down');
  });

  it('shows nothing for a field with no message, and the message in feedback markup otherwise', () => {
    const { container, rerender } = render(<FieldFeedback message={undefined} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<FieldFeedback message="Too short" />);
    expect(container.querySelector('.invalid-feedback')).toHaveTextContent('Too short');
  });
});

describe('useFieldErrors', () => {
  function Form({ error }: { error: unknown }) {
    const errors = useFieldErrors(error);
    return (
      <form>
        <input aria-label="Title" {...errors.props('title')} />
        <p>{errors.message('title') ?? 'none'}</p>
      </form>
    );
  }

  it('marks a bare field with exactly is-invalid and keeps the first message when a field repeats', () => {
    render(<Form error={new ApiError(400, 'c', '', [{ field: 'title', message: 'First' }, { field: 'title', message: 'Second' }])} />);
    expect(screen.getByLabelText('Title').className).toBe('is-invalid');
    expect(screen.getByText('First')).toBeInTheDocument();
    expect(screen.queryByText('Second')).toBeNull();
  });

  it('ignores a field error that names no field', () => {
    render(<Form error={new ApiError(400, 'c', '', [{ message: 'General' }])} />);
    expect(screen.getByLabelText('Title').className).toBe('');
    expect(screen.getByText('none')).toBeInTheDocument();
  });
});
