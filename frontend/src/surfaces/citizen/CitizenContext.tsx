import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { useAuth } from '../../auth/authContext'
import { readCitizenId, writeCitizenId } from './lib/citizenStore'

interface CitizenContextValue {
  /** The citizen record bound to the signed-in token, once known. */
  citizenId: string | null
  setCitizenId: (id: string | null) => void
}

const CitizenContext = createContext<CitizenContextValue | null>(null)

export function CitizenProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  const sub = user?.sub ?? ''
  const [ids, setIds] = useState<Record<string, string | null>>({})
  const citizenId = sub in ids ? (ids[sub] ?? null) : readCitizenId(sub)

  const setCitizenId = useCallback(
    (id: string | null) => {
      writeCitizenId(sub, id)
      setIds((prev) => ({ ...prev, [sub]: id }))
    },
    [sub],
  )

  const value = useMemo(() => ({ citizenId, setCitizenId }), [citizenId, setCitizenId])
  return <CitizenContext.Provider value={value}>{children}</CitizenContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useCitizen(): CitizenContextValue {
  const ctx = useContext(CitizenContext)
  if (!ctx) throw new Error('useCitizen must be used inside <CitizenProvider>')
  return ctx
}
