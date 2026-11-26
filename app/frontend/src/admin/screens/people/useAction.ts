import { useCallback, useState } from 'react';

export type Outcome<R> = { ok: true; value: R } | { ok: false };

export interface Action<A extends unknown[], R> {
  /** Runs the call; resolves to its value, or `{ ok: false }` when it failed (the failure is in `error`). */
  run: (...args: A) => Promise<Outcome<R>>;
  pending: boolean;
  error: unknown;
  reset: () => void;
}

/**
 * One write: pending flag and the failure to show in place. Deliberately not a TanStack
 * mutation, whose result stays in the mutation cache after the screen is left; a token's
 * secret comes back through here and must live only in the component that shows it.
 */
export function useAction<A extends unknown[], R>(fn: (...args: A) => Promise<R>): Action<A, R> {
  const [state, setState] = useState<{ pending: boolean; error: unknown }>({ pending: false, error: undefined });
  const run = useCallback(async (...args: A) => {
    setState({ pending: true, error: undefined });
    try {
      const value = await fn(...args);
      setState({ pending: false, error: undefined });
      return { ok: true as const, value };
    } catch (error) {
      setState({ pending: false, error });
      return { ok: false as const };
    }
  }, [fn]);
  const reset = useCallback(() => { setState({ pending: false, error: undefined }); }, []);
  return { run, pending: state.pending, error: state.error, reset };
}
