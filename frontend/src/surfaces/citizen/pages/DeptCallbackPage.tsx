import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useCitizenApi } from '../../../api/apiContext'
import { useT } from '../../../i18n'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { Loading } from '../../../ui/Loading'
import { readCallback, takePending } from '../lib/deptLogin'

/**
 * Where a department sends the citizen after its own login (docs/contracts/login-assertion.md), carrying a signed assertion.
 * It completes the link through Samanvay, which verifies the signature, the citizen, the state and the freshness, and then
 * returns the citizen to where they were. On any failure it says so plainly and offers a way back.
 */
export function DeptCallbackPage() {
  const api = useCitizenApi()
  const t = useT()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [failure, setFailure] = useState<unknown>(null)
  const started = useRef(false)

  useEffect(() => {
    // A department login is used once, so this must run once (React may run effects twice in development).
    if (started.current) return
    started.current = true
    void (async () => {
      const pending = takePending()
      if (!pending) {
        setFailure(new Error(t('deptCallback.notStarted')))
        return
      }
      const result = readCallback(`?${params.toString()}`)
      if ('error' in result) {
        setFailure(new Error(result.error))
        return
      }
      try {
        await api.assertLink({
          citizenId: pending.citizenId,
          departmentCode: pending.departmentCode,
          localIdType: '',
          localId: '',
          provider: 'DEPT_ASSERTION',
          proof: result.assertion,
        })
        navigate(pending.returnPath, { replace: true })
      } catch (err) {
        setFailure(err)
      }
    })()
  }, [api, navigate, params, t])

  return (
    <section className="card narrow" aria-labelledby="dept-callback-h">
      <h1 id="dept-callback-h">{failure ? t('deptCallback.failedHeading') : t('deptCallback.heading')}</h1>
      {failure ? (
        <>
          <ErrorNotice error={failure} />
          <Link className="btn" to="/services">
            {t('deptCallback.backToServices')}
          </Link>
        </>
      ) : (
        <Loading label={t('deptCallback.working')} />
      )}
    </section>
  )
}
