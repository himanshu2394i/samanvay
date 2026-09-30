import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../../auth/authContext'
import { useLanguage, useT } from '../../i18n'
import type { Lang } from '../../i18n'

const NAV = [
  { to: '/services', key: 'nav.services' },
  { to: '/applications', key: 'nav.applications' },
  { to: '/consents', key: 'nav.consents' },
  { to: '/profile', key: 'nav.profile' },
] as const

function LanguageToggle() {
  const { lang, setLang, t } = useLanguage()
  const options: { value: Lang; label: string; aria: string }[] = [
    { value: 'en', label: t('language.english'), aria: t('language.englishAria') },
    { value: 'mr', label: t('language.marathi'), aria: t('language.marathiAria') },
  ]
  return (
    <div className="lang-toggle" role="group" aria-label={t('language.label')}>
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          className={o.value === lang ? 'active' : undefined}
          aria-pressed={o.value === lang}
          lang={o.value}
          onClick={() => setLang(o.value)}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function CitizenLayout() {
  const { status, user, signIn, signOut } = useAuth()
  const t = useT()
  return (
    <>
      <a className="skip-link" href="#main">
        {t('layout.skipToMain')}
      </a>
      <header className="site-header">
        <div className="wrap identity">
          <span className="eyebrow">{t('layout.govOfMaharashtra')}</span>
          <span className="demo-tag">{t('layout.demoBuild')}</span>
          <LanguageToggle />
        </div>
        <div className="wrap bar">
          <NavLink to="/" className="brand" end>
            Samanvay <span>{t('layout.citizenServices')}</span>
          </NavLink>
          {status === 'authenticated' ? (
            <nav aria-label={t('layout.navMain')}>
              <ul>
                {NAV.map((n) => (
                  <li key={n.to}>
                    <NavLink to={n.to}>{t(n.key)}</NavLink>
                  </li>
                ))}
              </ul>
            </nav>
          ) : null}
          <div className="session">
            {status === 'authenticated' ? (
              <>
                <span className="who">{user?.name}</span>
                <button type="button" className="btn" onClick={() => void signOut()}>
                  {t('layout.signOut')}
                </button>
              </>
            ) : (
              <button type="button" className="btn primary" onClick={() => void signIn('/services')}>
                {t('layout.signIn')}
              </button>
            )}
          </div>
        </div>
      </header>
      <main id="main" className="wrap" tabIndex={-1}>
        <Outlet />
      </main>
      <footer className="site-footer wrap">
        <p>{t('layout.footerConsent')}</p>
        <p>
          <a href="#/staff">{t('layout.footerStaffLink')}</a> {t('layout.footerStaffRest')}
        </p>
      </footer>
    </>
  )
}
