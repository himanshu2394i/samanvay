import { useCallback, useEffect, useRef, useState } from 'react'

export type AsyncState<T> =
  | { status: 'loading'; data: undefined; error: undefined; refreshing: false }
  | { status: 'success'; data: T; error: undefined; refreshing: boolean }
  | { status: 'error'; data: undefined; error: unknown; refreshing: false }

type Outcome<T> = { ok: true; data: T } | { ok: false; error: unknown }

/**
 * Runs `load` on mount and again whenever `key` changes; a stale response for an older
 * key is ignored. `reload()` re-runs it and, if there is already data for this key, keeps
 * showing it (`refreshing: true`) instead of flashing a spinner.
 *
 * `key` is a string the caller derives from whatever the request depends on (an id, a
 * code), so the effect never depends on a closure identity.
 */
export function useAsync<T>(load: () => Promise<T>, key: string): AsyncState<T> & { reload: () => void } {
  const [nonce, setNonce] = useState(0)
  const [result, setResult] = useState<{ key: string; nonce: number; outcome: Outcome<T> } | null>(null)
  const loadRef = useRef(load)
  useEffect(() => {
    loadRef.current = load
  })

  useEffect(() => {
    let cancelled = false
    loadRef.current().then(
      (data) => {
        if (!cancelled) setResult({ key, nonce, outcome: { ok: true, data } })
      },
      (error: unknown) => {
        if (!cancelled) setResult({ key, nonce, outcome: { ok: false, error } })
      },
    )
    return () => {
      cancelled = true
    }
  }, [key, nonce])

  const reload = useCallback(() => setNonce((n) => n + 1), [])

  let state: AsyncState<T> = { status: 'loading', data: undefined, error: undefined, refreshing: false }
  if (result && result.key === key) {
    const { outcome } = result
    const current = result.nonce === nonce
    if (outcome.ok) state = { status: 'success', data: outcome.data, error: undefined, refreshing: !current }
    else if (current) state = { status: 'error', data: undefined, error: outcome.error, refreshing: false }
  }
  return { ...state, reload }
}
