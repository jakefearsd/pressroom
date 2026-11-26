import { useParams } from 'react-router';
import { ApiError } from '../../api/problem';
import { useBoot } from '../../context';

/** The `:handle` of a weblog-scoped route. */
export function useHandle(): string {
  return useParams()['handle'] ?? '';
}

/** Whether the signed-in user is this account (the JSP's selfRow). */
export function useIsSelf(userName: string): boolean {
  return useBoot().user.userName === userName;
}

/**
 * The error the banner should print for a failed call. A refusal naming fields is printed by
 * its field messages alone: the detail joins the same messages and would say each twice.
 */
export function bannerError(error: unknown): unknown {
  return error instanceof ApiError && error.fieldErrors.length > 0
    ? new ApiError(error.status, error.code, '', error.fieldErrors)
    : error;
}

/** One line for a failure in a batch: "<what>: <why>". */
export function failureLine(label: string, error: unknown): string {
  return `${label}: ${error instanceof Error ? (error instanceof ApiError ? (error.messages[0] ?? error.message) : error.message) : String(error)}`;
}

/** The weblog's public address with a trailing slash: the server's own (`absoluteUrl`: custom domain, virtual host or site url). */
export function weblogAddress(absoluteUrl: string | undefined): string | undefined {
  if (absoluteUrl === undefined || absoluteUrl === '') {
    return undefined;
  }
  return absoluteUrl.endsWith('/') ? absoluteUrl : `${absoluteUrl}/`;
}
