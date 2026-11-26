import { useCallback, useEffect, useRef, useState } from 'react';
import { useBoot } from '../context';
import { APP_DRAFT_PREFIX, readDraft, removeDraft, writeDraft, type StoredDraft } from './drafts';

export const DRAFT_DEBOUNCE_MS = 2000;

export interface DraftKeeper {
  /** Unsaved text found from an earlier visit that differs from what the editor loaded; null when none. */
  offer: StoredDraft | null;
  /** Takes the offered text (and stops offering it); null when there is none. */
  restore(): string | null;
  /** Throws the offered text away. */
  discard(): void;
  /** Call after a successful save: nothing is left to recover. */
  clear(): void;
}

/**
 * Keeps an editor's unsaved text in localStorage and offers it back after a lost session
 * or a closed tab (spec A3). Local on purpose, like the JSP editors' pressroom-draft.js:
 * a server autosave would multiply revisions. Nothing is written until the text differs
 * from what the editor loaded, so an offer that has not been answered is never overwritten.
 */
export function useDraftKeeper(key: string, value: string): DraftKeeper {
  const { user } = useBoot();
  const storageKey = `${APP_DRAFT_PREFIX}${user.userName}:${key}`;
  const initial = useRef(value);
  const [offer, setOffer] = useState<StoredDraft | null>(() => {
    const found = readDraft(storageKey, Date.now());
    return found !== null && found.text !== value ? found : null;
  });
  const pending = offer !== null;

  useEffect(() => {
    if (pending || value === initial.current) {
      return undefined;
    }
    const timer = setTimeout(() => {
      // clear() after a save moves `initial` to the saved text: a timer from before it must not write.
      if (value !== initial.current) {
        writeDraft(storageKey, { text: value, at: Date.now() });
      }
    }, DRAFT_DEBOUNCE_MS);
    return () => { clearTimeout(timer); };
  }, [pending, value, storageKey]);

  const restore = useCallback(() => {
    const text = offer?.text ?? null;
    setOffer(null);
    return text;
  }, [offer]);
  const discard = useCallback(() => {
    removeDraft(storageKey);
    setOffer(null);
  }, [storageKey]);
  const clear = useCallback(() => {
    removeDraft(storageKey);
    initial.current = value;
    setOffer(null);
  }, [storageKey, value]);

  return { offer, restore, discard, clear };
}
