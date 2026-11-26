import { ApiError } from '../api/problem';

/** One person-readable line for a failed call (the problem's detail, else its code or message). */
export function failureText(error: unknown): string {
  if (error instanceof ApiError) {
    return error.detail !== '' ? error.detail : error.message;
  }
  return error instanceof Error ? error.message : String(error);
}
