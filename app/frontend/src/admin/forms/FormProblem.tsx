import { ApiError } from '../api/problem';
import { ProblemBanner } from '../components/ProblemBanner';

/**
 * The banner for a failed form call: the problem's detail, plus the message of any field
 * error whose control this form does not render (those that have one show beside it).
 */
export function FormProblem({ error, controls }: { error: unknown; controls: (field: string) => boolean }) {
  if (error instanceof ApiError) {
    const lines = [
      ...(error.detail === '' ? [] : [error.detail]),
      ...error.fieldErrors.filter((e) => e.field === undefined || !controls(e.field)).map((e) => e.message)
    ].filter((m) => m !== '');
    return <ProblemBanner errors={lines.length === 0 ? [error.message] : lines} />;
  }
  return <ProblemBanner error={error} />;
}

/** The message under a control the server refused (Bootstrap shows it beside `.is-invalid`). */
export function FieldFeedback({ message }: { message: string | undefined }) {
  return message === undefined ? null : <div className="invalid-feedback">{message}</div>;
}
