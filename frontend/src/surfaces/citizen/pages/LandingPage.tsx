import { Link } from 'react-router-dom'
import { useAuth } from '../../../auth/authContext'
import { useT } from '../../../i18n'

const STEP_KEYS = [
  ['landing.step1.title', 'landing.step1.text'],
  ['landing.step2.title', 'landing.step2.text'],
  ['landing.step3.title', 'landing.step3.text'],
  ['landing.step4.title', 'landing.step4.text'],
  ['landing.step5.title', 'landing.step5.text'],
] as const

export function LandingPage() {
  const { status, signIn, notice } = useAuth()
  const t = useT()
  return (
    <>
      <section className="hero">
        <span className="eyebrow">{t('landing.eyebrow')}</span>
        <h1>{t('landing.title')}</h1>
        <p className="lede">{t('landing.lede')}</p>
        {notice ? (
          <p className="notice warn" role="alert">
            {notice}
          </p>
        ) : null}
        {status === 'authenticated' ? (
          <Link className="btn primary" to="/services">
            {t('landing.browseServices')}
          </Link>
        ) : (
          <>
            <button type="button" className="btn primary" onClick={() => void signIn('/services')}>
              {t('landing.signInToStart')}
            </button>
            <p className="hint">{t('landing.signInHint')}</p>
          </>
        )}
      </section>
      <section aria-labelledby="how">
        <h2 id="how">{t('landing.howItWorks')}</h2>
        <ol className="how">
          {STEP_KEYS.map(([titleKey, textKey]) => (
            <li key={titleKey}>
              <strong>{t(titleKey)}</strong>
              <span>{t(textKey)}</span>
            </li>
          ))}
        </ol>
      </section>
    </>
  )
}
