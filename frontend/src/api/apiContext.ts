import { createContext, useContext } from 'react'
import type { CitizenApi } from './citizenApi'

export const CitizenApiContext = createContext<CitizenApi | null>(null)

/** The typed citizen API, already wired to the signed-in session's bearer token. */
export function useCitizenApi(): CitizenApi {
  const api = useContext(CitizenApiContext)
  if (!api) throw new Error('useCitizenApi must be used inside <ApiProvider>')
  return api
}
