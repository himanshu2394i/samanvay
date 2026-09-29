import { useCallback, useState } from 'react'

/**
 * Runs one write at a time and tracks it: which action is in flight (`busy`, a caller
 * chosen key such as a row id), its error and its success message. `run` resolves true on
 * success so the caller can reload.
 */
export function useAction() {
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [done, setDone] = useState<string | null>(null)

  const run = useCallback(async (key: string, fn: () => Promise<unknown>, doneMessage: string): Promise<boolean> => {
    setBusy(key)
    setError(null)
    setDone(null)
    try {
      await fn()
      setDone(doneMessage)
      return true
    } catch (e) {
      setError(e)
      return false
    } finally {
      setBusy(null)
    }
  }, [])

  const clear = useCallback(() => {
    setError(null)
    setDone(null)
  }, [])

  return { busy, error, done, run, clear }
}
