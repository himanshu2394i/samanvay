import { useMemo, type ReactNode } from 'react'
import { useAuth } from '../auth/authContext'
import { StaffApiContext } from './apiContext'
import { createStaffApi } from './staffApi'
import { ApiClient } from './client'

export function ApiProvider({ children, fetchImpl }: { children: ReactNode; fetchImpl?: typeof fetch }) {
  const { getAccessToken, expireSession } = useAuth()
  const staff = useMemo(() => {
    const client = new ApiClient({
      getToken: getAccessToken,
      onUnauthorized: () => expireSession('Your session has ended. Sign in again to continue.'),
      fetchImpl,
    })
    return createStaffApi(client)
  }, [getAccessToken, expireSession, fetchImpl])
  return <StaffApiContext.Provider value={staff}>{children}</StaffApiContext.Provider>
}
