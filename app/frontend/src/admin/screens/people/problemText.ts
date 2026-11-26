import { ApiError } from '../../api/problem';
import type { Translate } from '../../i18n/messages';

/** Problem codes whose JSP wording differs from the API's detail: the screen keeps showing the JSP's. */
const JSP_WORDING: Record<string, string> = {
  'user.cantChangeOwnRole': 'userAdmin.cantChangeOwnRole',
  'user.cantDisableSelf': 'userAdmin.error.cantDisableSelf',
  'user.ssoAdmin': 'userAdmin.error.ssoAdmin',
  'user.ssoBound': 'userAdmin.error.ssoPassword',
  'user.mailNotConfigured': 'userAdmin.mailNotConfigured'
};

/** The failure with its detail swapped for the JSP's message where the JSP had one. */
export function inJspWords(error: unknown, t: Translate): unknown {
  if (!(error instanceof ApiError)) {
    return error;
  }
  const key = JSP_WORDING[error.code];
  return key === undefined ? error : new ApiError(error.status, error.code, t(key), error.fieldErrors);
}
