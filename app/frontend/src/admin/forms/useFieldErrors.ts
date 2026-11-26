import { useEffect, useMemo } from 'react';
import { ApiError } from '../api/problem';

export interface FieldErrors {
  /** Whether the server refused this request field. */
  invalid(field: string): boolean;
  /** The server's message for the field, if any. */
  message(field: string): string | undefined;
  /**
   * Props for the control: its id, `is-invalid` appended to `baseClass` and `aria-invalid`
   * together (a sighted reader's red border and a screen reader's state are one fact).
   */
  props(field: string, baseClass?: string): { id: string; className: string; 'aria-invalid'?: true };
}

/**
 * Field errors from a problem+json: marks each named control and focuses the first one
 * (what data-invalid-fields did for the JSP forms). `idFor` maps a request field name to a
 * control id; by default they are the same.
 */
export function useFieldErrors(error: unknown, idFor: (field: string) => string = (field) => field): FieldErrors {
  const failed = useMemo(() => {
    const byField = new Map<string, string>();
    if (error instanceof ApiError) {
      for (const e of error.fieldErrors) {
        if (e.field !== undefined && !byField.has(e.field)) {
          byField.set(e.field, e.message);
        }
      }
    }
    return byField;
  }, [error]);

  useEffect(() => {
    // A field with no control on this page is not an error: the form may not render it.
    for (const field of failed.keys()) {
      const control = document.getElementById(idFor(field));
      if (control !== null) {
        control.focus();
        return;
      }
    }
    // idFor is a pure mapping; the focus is wanted when the error changes, not when the caller re-renders.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [failed]);

  return {
    invalid: (field) => failed.has(field),
    message: (field) => failed.get(field),
    props: (field, baseClass = '') => ({
      id: idFor(field),
      className: failed.has(field) ? `${baseClass} is-invalid`.trim() : baseClass,
      ...(failed.has(field) ? { 'aria-invalid': true as const } : {})
    })
  };
}
