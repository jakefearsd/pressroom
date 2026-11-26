import { useLocation } from 'react-router';

/**
 * The ONE way a screen hands a one-shot success message to the next: router state `{message}`
 * (the JSPs' flash attributes). The frame reads it with `useFlash` and shows it above the
 * screen; a screen never renders it itself. The text is plain, already translated.
 */
export interface FlashState {
  message: string;
}

export const flashState = (message: string): FlashState => ({ message });

/** The message the current location carries, if any. */
export function useFlash(): string | undefined {
  const state: unknown = useLocation().state;
  if (typeof state === 'object' && state !== null && 'message' in state && typeof state.message === 'string') {
    return state.message;
  }
  return undefined;
}
