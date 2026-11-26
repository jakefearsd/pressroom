/**
 * Unsaved-text storage shared by useDraftKeeper and sign-out. The JSP editors'
 * pressroom-draft.js sweeps every "pressroom.draft.v1:" key whose snapshot is not
 * its own shape, so the app keeps its drafts under its own prefix; sign-out clears both.
 */
export const APP_DRAFT_PREFIX = 'pressroom.appdraft.v1:';
const LEGACY_DRAFT_PREFIX = 'pressroom.draft.v1:';

export const DRAFT_MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000;

export interface StoredDraft {
  text: string;
  at: number;
}

/** localStorage, or null where reading it throws (blocked storage). */
export function storage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

export function readDraft(key: string, now: number): StoredDraft | null {
  try {
    const raw = storage()?.getItem(key);
    if (raw === null || raw === undefined) {
      return null;
    }
    const value: unknown = JSON.parse(raw);
    if (typeof value !== 'object' || value === null) {
      return null;
    }
    const { text, at } = value as Record<string, unknown>;
    if (typeof text !== 'string' || typeof at !== 'number' || now - at > DRAFT_MAX_AGE_MS) {
      return null;
    }
    return { text, at };
  } catch {
    return null;
  }
}

export function writeDraft(key: string, draft: StoredDraft): void {
  try {
    storage()?.setItem(key, JSON.stringify(draft));
  } catch {
    /* storage full or blocked: no autosave, as the JSP editors' */
  }
}

export function removeDraft(key: string): void {
  try {
    storage()?.removeItem(key);
  } catch {
    /* blocked: nothing was stored */
  }
}

/** Deliberate sign-out: a shared machine's next user must not find this one's unsaved text. */
export function clearAllDrafts(): void {
  try {
    const store = storage();
    if (store === null) {
      return;
    }
    const doomed: string[] = [];
    for (let i = 0; i < store.length; i++) {
      const key = store.key(i);
      if (key !== null && (key.startsWith(APP_DRAFT_PREFIX) || key.startsWith(LEGACY_DRAFT_PREFIX))) {
        doomed.push(key);
      }
    }
    doomed.forEach((key) => { store.removeItem(key); });
  } catch {
    /* blocked: nothing was ever stored */
  }
}
