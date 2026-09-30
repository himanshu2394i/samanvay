import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useCitizenApi } from '../../../api/apiContext'
import type { ProfileDraft } from '../../../api/types'
import { useT } from '../../../i18n'
import { Field } from '../../../ui/Field'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { Loading } from '../../../ui/Loading'
import { useAsync } from '../../../ui/useAsync'
import { useCitizen } from '../CitizenContext'
import { formatDate } from '../lib/format'

export function ProfilePage() {
  const { citizenId } = useCitizen()
  return citizenId ? <ProfileView citizenId={citizenId} /> : <RegisterForm />
}

function ProfileView({ citizenId }: { citizenId: string }) {
  const api = useCitizenApi()
  const t = useT()
  const { setCitizenId } = useCitizen()
  const profile = useAsync(() => api.getProfile(citizenId), citizenId)

  return (
    <section className="card narrow" aria-labelledby="profile-h">
      <h1 id="profile-h">{t('profile.myDetails')}</h1>
      {profile.status === 'loading' ? <Loading label={t('profile.loading')} /> : null}
      {profile.status === 'error' ? (
        <>
          <ErrorNotice error={profile.error} onRetry={profile.reload} />
          <p className="hint">{t('profile.recordGoneHint')}</p>
          <button type="button" className="btn" onClick={() => setCitizenId(null)}>
            {t('profile.forgetRecord')}
          </button>
        </>
      ) : null}
      {profile.status === 'success' ? (
        <dl className="facts">
          <dt>{t('profile.name')}</dt>
          <dd>{profile.data.nameLatin}</dd>
          {profile.data.nameDevanagari ? (
            <>
              <dt>{t('profile.nameDevanagari')}</dt>
              <dd lang="mr">{profile.data.nameDevanagari}</dd>
            </>
          ) : null}
          {profile.data.fatherName ? (
            <>
              <dt>{t('profile.fatherName')}</dt>
              <dd>{profile.data.fatherName}</dd>
            </>
          ) : null}
          <dt>{t('profile.dob')}</dt>
          <dd>{formatDate(profile.data.dob)}</dd>
        </dl>
      ) : null}
    </section>
  )
}

function RegisterForm() {
  const api = useCitizenApi()
  const t = useT()
  const { setCitizenId } = useCitizen()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from ?? '/services'

  const [given, setGiven] = useState('')
  const [family, setFamily] = useState('')
  const [father, setFather] = useState('')
  const [devanagari, setDevanagari] = useState('')
  const [dob, setDob] = useState('')
  const [gender, setGender] = useState('')
  const [dobError, setDobError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    if (dob && new Date(dob).getTime() > Date.now()) {
      setDobError(t('register.dobFuture'))
      return
    }
    setDobError(null)
    const draft: ProfileDraft = {
      nameLatin: `${given.trim()} ${family.trim()}`,
      nameDevanagari: devanagari.trim() || undefined,
      givenName: given.trim(),
      familyName: family.trim(),
      fatherName: father.trim() || undefined,
      dob,
      dobPrecision: 'DAY',
      gender: gender || undefined,
    }
    setBusy(true)
    try {
      const id = await api.registerSelf(draft)
      setCitizenId(id)
      void navigate(from, { replace: true })
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="card narrow" aria-labelledby="register-h">
      <h1 id="register-h">{t('register.title')}</h1>
      <p>{t('register.intro')}</p>
      <form onSubmit={(e) => void onSubmit(e)}>
        <Field label={t('register.givenName')} value={given} onChange={(e) => setGiven(e.target.value)} required autoComplete="given-name" />
        <Field label={t('register.familyName')} value={family} onChange={(e) => setFamily(e.target.value)} required autoComplete="family-name" />
        <Field label={t('register.fatherName')} value={father} onChange={(e) => setFather(e.target.value)} hint={t('register.fatherNameHint')} />
        <Field
          label={t('register.nameDevanagari')}
          value={devanagari}
          onChange={(e) => setDevanagari(e.target.value)}
          lang="mr"
          hint={t('register.optional')}
        />
        <Field
          label={t('register.dob')}
          type="date"
          value={dob}
          onChange={(e) => setDob(e.target.value)}
          required
          autoComplete="bday"
          error={dobError}
        />
        <div className="field">
          <label htmlFor="gender">{t('register.gender')}</label>
          <select id="gender" value={gender} onChange={(e) => setGender(e.target.value)}>
            <option value="">{t('register.genderPreferNot')}</option>
            <option value="F">{t('register.genderFemale')}</option>
            <option value="M">{t('register.genderMale')}</option>
            <option value="O">{t('register.genderOther')}</option>
          </select>
        </div>
        {error ? <ErrorNotice error={error} /> : null}
        <button type="submit" className="btn primary" disabled={busy} aria-busy={busy || undefined}>
          {busy ? t('register.saving') : t('register.saveContinue')}
        </button>
      </form>
    </section>
  )
}
