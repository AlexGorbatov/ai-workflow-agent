'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Loads data now and again every {@code everyMs} while the tab is visible. Returns the last good value, the last
 * error, and a reload function (for after a decision).
 */
export function usePoll<T>(load: () => Promise<T>, everyMs: number, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const loadRef = useRef(load);
  loadRef.current = load;

  const reload = useCallback(async () => {
    try {
      setData(await loadRef.current());
      setError(null);
    } catch (e) {
      setError(e as Error);
    }
  }, []);

  useEffect(() => {
    setData(null);
    reload();
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible') reload();
    }, everyMs);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [everyMs, reload, ...deps]);

  return { data, error, reload };
}

/** Re-renders every second, for "2 min ago" and SLA countdowns. */
export function useNow(everyMs = 1000) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), everyMs);
    return () => clearInterval(timer);
  }, [everyMs]);
  return now;
}
