/** Whether any call has answered 401: the session ended. A tiny external store. */
export interface SessionStore {
  expired(): boolean;
  expire(): void;
  subscribe(listener: () => void): () => void;
}

export function createSessionStore(): SessionStore {
  let expired = false;
  const listeners = new Set<() => void>();
  return {
    expired: () => expired,
    expire() {
      if (!expired) {
        expired = true;
        listeners.forEach((listener) => { listener(); });
      }
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => { listeners.delete(listener); };
    }
  };
}
