import { useEffect, useRef, type RefObject } from 'react';

/**
 * A ref that always holds the value of the latest committed render, for callbacks that outlive
 * one render (an editor created once, a document listener, a debounced push) and must not call
 * a stale closure. Updated in an effect, never during render.
 */
export function useLatest<T>(value: T): RefObject<T> {
  const ref = useRef(value);
  useEffect(() => {
    ref.current = value;
  });
  return ref;
}
