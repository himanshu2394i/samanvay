import { useMemo, type ReactNode } from 'react'
import { useAuth } from '../auth/authContext'
import { CitizenApiContext } from './apiContext'
import { createCitizenApi } from './citizenApi'
import { ApiClient } from './client'

export function ApiProvider({ children, fetchImpl }: { children: ReactNode; fetchImpl?: typeof fetch }) {
  const { getAccessToken, expireSession } = useAuth()
  const api = useMemo(
    () =>
      createCitizenApi(
        new ApiClient({
          getToken: getAccessToken,
          onUnauthorized: () => expireSession('Your session has ended. Sign in again to continue.'),
          fetchImpl,
        }),
      ),
    [getAccessToken, expireSession, fetchImpl],
  )
  return <CitizenApiContext.Provider value={api}>{children}</CitizenApiContext.Provider>
}
