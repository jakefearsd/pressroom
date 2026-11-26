import { useLocation } from 'react-router';

/** Router state carrying a one-shot success message to the next screen (the JSPs' flash messages). */
export interface FlashState {
  flash: string;
}

export const flashState = (flash: string): FlashState => ({ flash });

/** The flash message the current location carries, if any. */
export function useFlash(): string | undefined {
  const state: unknown = useLocation().state;
  if (typeof state === 'object' && state !== null && 'flash' in state && typeof state.flash === 'string') {
    return state.flash;
  }
  return undefined;
}
