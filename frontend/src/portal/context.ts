import { createContext, useContext } from 'react'
import type { Branding, PortalApi, Session } from './api'

export interface PortalContextValue {
  api: PortalApi
  /** null until loaded (or if it could not be loaded: the neutral look is used). */
  branding: Branding | null
  /** undefined while we find out, null when signed out. */
  session: Session | null | undefined
  setSession: (s: Session | null) => void
  /** Leave the app for another address (the other department's login). */
  goTo: (url: string) => void
}

export const PortalContext = createContext<PortalContextValue | null>(null)

export function usePortal(): PortalContextValue {
  const v = useContext(PortalContext)
  if (!v) throw new Error('usePortal must be used inside PortalApp')
  return v
}
