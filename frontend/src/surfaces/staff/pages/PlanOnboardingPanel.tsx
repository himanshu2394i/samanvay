import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useStaffApi } from '../../../api/apiContext'
import type { OnboardingPlan, OnboardingResult, PendingStep, TrialResult } from '../../../api/staffTypes'
import { Badge } from '../../../ui/Badge'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { Field } from '../../../ui/Field'
import { Loading } from '../../../ui/Loading'
import { humanize } from '../../../ui/format'
import { useAction } from '../../../ui/useAction'

/**
 * ADMIN: onboard a department in one go. Enter its base URL; the server reads its manifest and returns a PLAN (nothing is
 * changed). Tick the documents to onboard and approve the proposed field matches (they are only proposals); the server then
 * creates the department, data sources, connectors, mappings and journeys, all as drafts, in one transaction. It refuses if
 * the manifest changed since this plan, so what is created is exactly what was reviewed.
 */
export function PlanOnboardingPanel() {
  const api = useStaffApi()
  const review = useAction()
  const onboard = useAction()
  const [url, setUrl] = useState('')
  const [plan, setPlan] = useState<OnboardingPlan | null>(null)
  const [ticked, setTicked] = useState<Set<string>>(new Set())
  const [approved, setApproved] = useState(false)
  const [keyConfirmed, setKeyConfirmed] = useState(false)
  const [result, setResult] = useState<OnboardingResult | null>(null)

  async function runReview(e: FormEvent) {
    e.preventDefault()
    setPlan(null)
    setResult(null)
    setTicked(new Set())
    setApproved(false)
    setKeyConfirmed(false)
    let p: OnboardingPlan | undefined
    const ok = await review.run('review', async () => {
      p = await api.onboardPlan(url.trim())
    }, '')
    if (ok && p) setPlan(p)
  }

  function toggle(category: string) {
    setTicked((prev) => {
      const next = new Set(prev)
      if (next.has(category)) next.delete(category)
      else next.add(category)
      return next
    })
  }

  async function runOnboard() {
    if (!plan) return
    let out: OnboardingResult | undefined
    const ok = await onboard.run('onboard', async () => {
      out = await api.onboard({
        baseUrl: url.trim(),
        manifestDigest: plan.manifestDigest,
        categories: plan.documents.filter((d) => ticked.has(d.category)).map((d) => d.category),
        acceptSuggestedMappings: approved,
        mappings: {},
        ...(needsKeyApproval && keyConfirmed && plan.manifestKeyThumbprint ? { approvedManifestKey: plan.manifestKeyThumbprint } : {}),
      })
    }, '')
    if (ok && out) setResult(out)
  }

  const count = ticked.size
  // A signing key the department has not been approved for yet (first time, or changed) must be confirmed by the admin.
  const needsKeyApproval = !!plan?.manifestKeyThumbprint && plan.manifestKeyThumbprint !== plan.pinnedKeyThumbprint
  const keyChanged = needsKeyApproval && !!plan?.pinnedKeyThumbprint
  const canOnboard = count > 0 && approved && (!needsKeyApproval || keyConfirmed) && onboard.busy === null

  return (
    <div className="card spaced" aria-labelledby="plan-h">
      <h2 id="plan-h" style={{ marginTop: 0 }}>
        Onboard a department in one go
      </h2>
      <p className="hint">
        Enter the department&rsquo;s base URL. Samanvay reads what it publishes and shows a plan: the documents, the data
        sources and connectors it would create, the field matches it proposes, and what an operator must still set up.
        Nothing is changed until you onboard.
      </p>
      <form className="inline-form" onSubmit={(e) => void runReview(e)}>
        <Field
          label="Onboard from a URL"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          required
          autoComplete="off"
          placeholder="https://revenue.example.gov"
          hint="Samanvay reads {URL}/.well-known/samanvay/manifest"
        />
        <button type="submit" className="btn primary" disabled={review.busy !== null}>
          {review.busy ? 'Reading…' : 'Review plan'}
        </button>
      </form>
      {review.busy ? <Loading variant="table" label="Reading the manifest" /> : null}
      {review.error ? <ErrorNotice error={review.error} /> : null}

      {plan ? (
        <div className="spaced">
          <dl className="facts">
            <div className="fact">
              <dt>Department</dt>
              <dd>
                <strong>{plan.departmentName}</strong> <span className="mono">{plan.departmentCode}</span>
              </dd>
            </div>
          </dl>
          {plan.changedSinceOnboarding ? (
            <div className="notice warn" role="alert">
              <p>
                This department has changed what it publishes since you last onboarded it. Review the documents below
                and onboard again to pick up the change (existing connectors get a new version).
              </p>
            </div>
          ) : plan.onboardedFromManifest ? (
            <div className="notice" role="status">
              <p>This department is already onboarded from this manifest and nothing has changed since.</p>
            </div>
          ) : null}

          {plan.manifestKeyThumbprint ? (
            <div className={keyChanged ? 'notice warn' : 'notice'} role={keyChanged ? 'alert' : 'status'}>
              <p>
                <strong>Manifest signing key.</strong> The department signs its manifest. Key fingerprint:{' '}
                <span className="mono">{plan.manifestKeyThumbprint}</span>
              </p>
              {keyChanged ? (
                <p>
                  The department&rsquo;s signing key has changed since you approved one (<span className="mono">{plan.pinnedKeyThumbprint}</span>
                  ). Continue only if the department told you it replaced its key.
                </p>
              ) : null}
              {needsKeyApproval ? (
                <label className="check">
                  <input type="checkbox" checked={keyConfirmed} onChange={(e) => setKeyConfirmed(e.target.checked)} />{' '}
                  I confirmed this key fingerprint with the department
                </label>
              ) : (
                <p>This matches the key you approved earlier.</p>
              )}
            </div>
          ) : (
            <div className="notice warn" role="note">
              <p>This manifest is not signed, so Samanvay cannot tell that it really came from the department.</p>
            </div>
          )}

          <h3 className="spaced">Documents ({plan.documents.length})</h3>
          <table className="table" aria-label="Documents in the plan">
            <thead>
              <tr>
                <th scope="col">Onboard</th>
                <th scope="col">Document</th>
                <th scope="col">Protocol</th>
                <th scope="col">Data source</th>
                <th scope="col">Central schema</th>
                <th scope="col">Field matches</th>
              </tr>
            </thead>
            <tbody>
              {plan.documents.map((d) => (
                <tr key={d.category}>
                  <td>
                    <input
                      type="checkbox"
                      aria-label={`Onboard ${d.title}`}
                      checked={ticked.has(d.category)}
                      disabled={!d.ready || onboard.busy !== null}
                      onChange={() => toggle(d.category)}
                    />
                  </td>
                  <td>
                    {d.title} <Badge tone="neutral">{humanize(d.category)}</Badge>
                    {d.newVersionOfExisting ? (
                      <p className="hint">New version of an existing connector</p>
                    ) : null}
                    {d.problems.map((p) => (
                      <p key={p} className="hint" role="note">
                        {p}
                      </p>
                    ))}
                    {d.unmappedRequired.length ? (
                      <p className="hint">No field match yet for required: {d.unmappedRequired.join(', ')}</p>
                    ) : null}
                  </td>
                  <td>{d.protocol}</td>
                  <td className="mono">{d.dataSourceCode}</td>
                  <td className="mono">{d.centralSchemaRef ?? 'Not seeded'}</td>
                  <td>
                    {d.suggestions.length ? (
                      <details>
                        <summary>
                          {d.suggestions.length} field match{d.suggestions.length === 1 ? '' : 'es'} proposed
                        </summary>
                        <ul className="plain">
                          {d.suggestions.map((s) => (
                            <li key={s.target}>
                              <span className="mono">{s.source}</span> → <span className="mono">{s.target}</span>
                            </li>
                          ))}
                        </ul>
                      </details>
                    ) : (
                      '—'
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {plan.journeys.length ? (
            <>
              <h3 className="spaced">Journeys it offers ({plan.journeys.length})</h3>
              <ul className="stack">
                {plan.journeys.map((j) => (
                  <li key={j.code} className="card">
                    <h4 style={{ marginTop: 0 }}>
                      {j.name} <span className="mono">{j.code}</span>{' '}
                      {j.exists ? <Badge tone="neutral">Already in the catalog</Badge> : null}
                    </h4>
                    <p className="hint">Needs: {j.requiredCategories.map(humanize).join(', ')}</p>
                  </li>
                ))}
              </ul>
            </>
          ) : null}

          <h3 className="spaced">Before it can work, an operator must</h3>
          <StepList steps={plan.pendingSteps} label="Steps for an operator" />

          {result ? <Done result={result} plan={plan} /> : (
            <div className="spaced">
              <label className="check">
                <input type="checkbox" checked={approved} onChange={(e) => setApproved(e.target.checked)} />{' '}
                I have reviewed the proposed field matches and accept them
              </label>
              {onboard.error ? <ErrorNotice error={onboard.error} /> : null}
              <div className="actions">
                <button type="button" className="btn primary" disabled={!canOnboard} onClick={() => void runOnboard()}>
                  {onboard.busy ? 'Onboarding…' : `Onboard ${count} document${count === 1 ? '' : 's'}`}
                </button>
              </div>
            </div>
          )}
        </div>
      ) : null}
    </div>
  )
}

function StepList({ steps, label }: { steps: PendingStep[]; label: string }) {
  if (steps.length === 0) return <p className="hint">Nothing further.</p>
  return (
    <ul className="stack" aria-label={label}>
      {steps.map((s) => (
        <li key={s.kind + s.subject} className="card">
          <p>
            <Badge tone="neutral">{humanize(s.kind)}</Badge> <span className="mono">{s.subject}</span>
          </p>
          <p className="hint">{s.detail}</p>
          <dl className="facts">
            {Object.entries(s.data).map(([k, v]) => (
              <div key={k} className="fact">
                <dt>{k}</dt>
                <dd className="mono">{v}</dd>
              </div>
            ))}
          </dl>
        </li>
      ))}
    </ul>
  )
}

function Done({ result, plan }: { result: OnboardingResult; plan: OnboardingPlan }) {
  return (
    <div className="notice ok spaced" role="status">
      <p>
        <strong>Onboarded {plan.departmentName}.</strong> Everything is a draft: nothing is live until each connector is tested
        and published. <Link to="/staff/admin/catalog">See it in the catalog</Link>.
      </p>
      <ul className="plain">
        {result.dataSources.map((c) => (
          <li key={c}>
            Data source <span className="mono">{c}</span>
          </li>
        ))}
        {result.connectorRefs.map((c) => (
          <li key={c}>
            Connector draft <span className="mono">{c}</span> <TrialButton connectorRef={c} />
          </li>
        ))}
        {result.journeysCreated.map((c) => (
          <li key={c}>
            Journey draft <span className="mono">{c}</span> <Link to={`/staff/admin/journeys/${encodeURIComponent(c)}`}>Status</Link>
          </li>
        ))}
        {result.skipped.map((c) => (
          <li key={c} className="hint">
            Skipped: {c}
          </li>
        ))}
      </ul>
      <h4>Still to do</h4>
      <StepList steps={result.pendingSteps} label="Steps still to do" />
    </div>
  )
}

/**
 * Runs a trial fetch of one connector draft for the department's published FAKE sample person and shows what came back (or why
 * it did not work). It is the real call through the connector, with the real credentials the operator provisioned.
 */
function TrialButton({ connectorRef }: { connectorRef: string }) {
  const api = useStaffApi()
  const trial = useAction()
  const [result, setResult] = useState<TrialResult | null>(null)

  async function run() {
    let r: TrialResult | undefined
    const ok = await trial.run('trial', async () => {
      r = await api.trialConnector(connectorRef)
    }, '')
    if (ok && r) setResult(r)
  }

  return (
    <>
      <button type="button" className="btn" disabled={trial.busy !== null} onClick={() => void run()}>
        {trial.busy ? 'Trying…' : `Run trial fetch for ${connectorRef}`}
      </button>
      {trial.error ? <ErrorNotice error={trial.error} /> : null}
      {result ? (
        <section className="card spaced" role="region" aria-label={`Trial result for ${connectorRef}`}>
          <p>
            <Badge tone={result.ok ? 'ok' : 'warn'}>{result.ok ? 'Worked' : 'Did not work'}</Badge> for sample person{' '}
            <span className="mono">{result.personId}</span> ({humanize(result.outcome)})
          </p>
          {result.detail ? <p className="hint">{result.detail}</p> : null}
          {result.fields ? (
            <dl className="facts">
              {Object.entries(result.fields).map(([k, v]) => (
                <div key={k} className="fact">
                  <dt>{k}</dt>
                  <dd className="mono">{String(v)}</dd>
                </div>
              ))}
            </dl>
          ) : null}
        </section>
      ) : null}
    </>
  )
}
