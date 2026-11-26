/** One field's problem, from problem+json `errors`. */
export interface FieldError {
  field?: string;
  message: string;
  code?: string;
}

/** A failed /api/v1 call: the problem+json (RFC 9457) body as a typed error. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly detail: string;
  readonly fieldErrors: readonly FieldError[];

  constructor(status: number, code: string, detail: string, fieldErrors: readonly FieldError[]) {
    super(detail === '' ? `${code} (${String(status)})` : detail);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.detail = detail;
    this.fieldErrors = fieldErrors;
  }

  /** Every message a person should read: the detail, then each field's message. */
  get messages(): string[] {
    const fields = this.fieldErrors.map((e) => e.message).filter((m) => m !== '');
    return [...(this.detail === '' ? [] : [this.detail]), ...fields];
  }
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function fieldErrors(value: unknown): FieldError[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.flatMap((item: unknown) => {
    if (typeof item !== 'object' || item === null) {
      return [];
    }
    const record = item as Record<string, unknown>;
    const field = text(record['field']);
    const code = text(record['code']);
    return [{
      message: text(record['message']),
      ...(field === '' ? {} : { field }),
      ...(code === '' ? {} : { code })
    }];
  });
}

/** Builds an ApiError from a response status and its (possibly absent or non-problem) JSON body. */
export function toApiError(status: number, body: unknown): ApiError {
  const record = typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {};
  return new ApiError(status, text(record['code']) || `http.${String(status)}`, text(record['detail']),
    fieldErrors(record['errors']));
}
