import { useMemo, type ReactNode } from 'react'
import { useAuth } from '../auth/authContext'
import { CitizenApiContext, StaffApiContext } from './apiContext'
import { createCitizenApi } from './citizenApi'
import { createStaffApi } from './staffApi'
import { ApiClient } from './client'

export function ApiProvider({ children, fetchImpl }: { children: ReactNode; fetchImpl?: typeof fetch }) {
  const { getAccessToken, expireSession } = useAuth()
  // One client (one bearer token: whichever realm this page load signed in to) behind two
  // typed facades. The server decides which routes that token's roles may call.
  const { citizen, staff } = useMemo(() => {
    const client = new ApiClient({
      getToken: getAccessToken,
      onUnauthorized: () => expireSession('Your session has ended. Sign in again to continue.'),
      fetchImpl,
    })
    return { citizen: createCitizenApi(client), staff: createStaffApi(client) }
  }, [getAccessToken, expireSession, fetchImpl])
  return (
    <CitizenApiContext.Provider value={citizen}>
      <StaffApiContext.Provider value={staff}>{children}</StaffApiContext.Provider>
    </CitizenApiContext.Provider>
  )
}
