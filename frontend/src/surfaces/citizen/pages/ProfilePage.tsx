import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useCitizenApi } from '../../../api/apiContext'
import type { ProfileDraft } from '../../../api/types'
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
  const { setCitizenId } = useCitizen()
  const profile = useAsync(() => api.getProfile(citizenId), citizenId)

  return (
    <section className="card narrow" aria-labelledby="profile-h">
      <h1 id="profile-h">My details</h1>
      {profile.status === 'loading' ? <Loading label="Loading your details" /> : null}
      {profile.status === 'error' ? (
        <>
          <ErrorNotice error={profile.error} onRetry={profile.reload} />
          <p className="hint">
            If this record no longer exists (for example after the development database was reset), forget it here and
            enter your details again.
          </p>
          <button type="button" className="btn" onClick={() => setCitizenId(null)}>
            Forget saved record
          </button>
        </>
      ) : null}
      {profile.status === 'success' ? (
        <dl className="facts">
          <dt>Name</dt>
          <dd>{profile.data.nameLatin}</dd>
          {profile.data.nameDevanagari ? (
            <>
              <dt>Name (Devanagari)</dt>
              <dd lang="mr">{profile.data.nameDevanagari}</dd>
            </>
          ) : null}
          {profile.data.fatherName ? (
            <>
              <dt>Father&rsquo;s name</dt>
              <dd>{profile.data.fatherName}</dd>
            </>
          ) : null}
          <dt>Date of birth</dt>
          <dd>{formatDate(profile.data.dob)}</dd>
        </dl>
      ) : null}
    </section>
  )
}

function RegisterForm() {
  const api = useCitizenApi()
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
      setDobError('Date of birth cannot be in the future.')
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
      <h1 id="register-h">Your details</h1>
      <p>
        Tell us who you are once. Departments use these details to match your records, and never to decide for you.
        If you have registered before, submitting this form finds your existing record.
      </p>
      <form onSubmit={(e) => void onSubmit(e)}>
        <Field label="Given name" value={given} onChange={(e) => setGiven(e.target.value)} required autoComplete="given-name" />
        <Field label="Family name" value={family} onChange={(e) => setFamily(e.target.value)} required autoComplete="family-name" />
        <Field label="Father's name" value={father} onChange={(e) => setFather(e.target.value)} hint="Optional. Helps match older records." />
        <Field
          label="Name in Devanagari"
          value={devanagari}
          onChange={(e) => setDevanagari(e.target.value)}
          lang="mr"
          hint="Optional."
        />
        <Field
          label="Date of birth"
          type="date"
          value={dob}
          onChange={(e) => setDob(e.target.value)}
          required
          autoComplete="bday"
          error={dobError}
        />
        <div className="field">
          <label htmlFor="gender">Gender</label>
          <select id="gender" value={gender} onChange={(e) => setGender(e.target.value)}>
            <option value="">Prefer not to say</option>
            <option value="F">Female</option>
            <option value="M">Male</option>
            <option value="O">Other</option>
          </select>
        </div>
        {error ? <ErrorNotice error={error} /> : null}
        <button type="submit" className="btn primary" disabled={busy} aria-busy={busy || undefined}>
          {busy ? 'Saving…' : 'Save and continue'}
        </button>
      </form>
    </section>
  )
}
