import { useState, type FormEvent, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useStaffApi } from '../../../api/apiContext'
import { PlanOnboardingPanel } from './PlanOnboardingPanel'
import type {
  ConnectorDefinition,
  ConnectorTestReport,
  DataSourceDefinition,
  ImportPreview,
} from '../../../api/staffTypes'
import type { Department } from '../../../api/types'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { Field } from '../../../ui/Field'
import { TextArea } from '../../../ui/TextArea'
import { useAction } from '../../../ui/useAction'
import { useAsync } from '../../../ui/useAsync'
import {
  approvedRules,
  categoryDefaults,
  CATEGORIES,
  defaultCapabilities,
  defaultSpec,
  idSuffix,
  jsonError,
  parseSlaMs,
  PROTOCOLS,
} from '../lib/onboarding'

const STEPS = [
  { id: 'department', label: 'Department' },
  { id: 'source', label: 'Data source' },
  { id: 'connector', label: 'Connector' },
  { id: 'mapping', label: 'Field matches' },
  { id: 'test', label: 'Test' },
  { id: 'publish', label: 'Publish' },
] as const

interface Wizard {
  department: Department | null
  dataSource: DataSourceDefinition | null
  connector: ConnectorDefinition | null
  savedRules: number | null
  report: ConnectorTestReport | null
  published: ConnectorDefinition | null
}

const EMPTY: Wizard = { department: null, dataSource: null, connector: null, savedRules: null, report: null, published: null }

/**
 * ADMIN: onboard a department's data source as a draft connector, get OpenAPI-based field
 * suggestions, approve the rows you trust, test, and publish. Each step is one catalog
 * write; the importer only PREVIEWS (nothing is saved or published from a suggestion), and
 * publishing needs the passing test report from the step before.
 */
export function OnboardingPage() {
  const [step, setStep] = useState(0)
  const [w, setW] = useState<Wizard>(EMPTY)
  // Stable per page load: keeps generated demo ids unique between runs without re-rolling on edits.
  const [suffix] = useState(() => idSuffix())
  const [mappingRef, setMappingRef] = useState(`map-${suffix}@1`)
  const next = () => setStep((s) => Math.min(s + 1, STEPS.length - 1))
  const back = () => setStep((s) => Math.max(s - 1, 0))

  return (
    <section aria-labelledby="onb-h">
      <h1 id="onb-h">Onboarding</h1>
      <p className="lede">
        Register a department and its data source, describe what the connector fetches, approve the field matches, then test and publish.
        Onboarded departments, documents and journeys are listed under <Link to="/staff/admin/departments">Departments</Link> and <Link to="/staff/admin/journeys">Journeys</Link>.
      </p>

      <PlanOnboardingPanel />

      <h2 className="spaced">Or onboard step by step</h2>
      <ol className="steps" aria-label="Onboarding steps">
        {STEPS.map((s, i) => (
          <li key={s.id} className={i < step ? 'done' : i === step ? 'now' : ''} aria-current={i === step ? 'step' : undefined}>
            <span className="n">{i + 1}</span> {s.label}
          </li>
        ))}
      </ol>

      {step === 0 ? <DepartmentStep suffix={suffix} done={w.department} onDone={(department) => setW({ ...w, department })} onNext={next} /> : null}
      {step === 1 && w.department ? (
        <DataSourceStep suffix={suffix} department={w.department} done={w.dataSource} onDone={(dataSource) => setW({ ...w, dataSource })} onNext={next} onBack={back} />
      ) : null}
      {step === 2 && w.dataSource ? (
        <ConnectorStep
          suffix={suffix}
          dataSource={w.dataSource}
          mappingRef={mappingRef}
          onMappingRef={setMappingRef}
          done={w.connector}
          onDone={(connector) => setW({ ...w, connector })}
          onNext={next}
          onBack={back}
        />
      ) : null}
      {step === 3 && w.connector ? (
        <MappingStep connector={w.connector} mappingRef={mappingRef} saved={w.savedRules} onSaved={(n) => setW({ ...w, savedRules: n })} onNext={next} onBack={back} />
      ) : null}
      {step === 4 && w.connector ? (
        <TestStep connector={w.connector} report={w.report} onReport={(report) => setW({ ...w, report })} onNext={next} onBack={back} />
      ) : null}
      {step === 5 && w.connector && w.report ? (
        <PublishStep connector={w.connector} report={w.report} published={w.published} onPublished={(published) => setW({ ...w, published })} onBack={back} />
      ) : null}
    </section>
  )
}

function StepShell({ title, help, children }: { title: string; help: string; children: ReactNode }) {
  return (
    <div className="card">
      <h2 tabIndex={-1}>{title}</h2>
      <p className="hint">{help}</p>
      {children}
    </div>
  )
}

function Nav({ onBack, children }: { onBack?: () => void; children: ReactNode }) {
  return (
    <div className="actions">
      {onBack ? (
        <button type="button" className="btn" onClick={onBack}>
          Back
        </button>
      ) : null}
      {children}
    </div>
  )
}

// --- 1. department -------------------------------------------------------------------------
function DepartmentStep({
  suffix,
  done,
  onDone,
  onNext,
}: {
  suffix: string
  done: Department | null
  onDone: (d: Department) => void
  onNext: () => void
}) {
  const api = useStaffApi()
  const action = useAction()
  const [code, setCode] = useState(`DEPT${suffix.toUpperCase()}`)
  const [name, setName] = useState('')
  const [idpRealm, setIdpRealm] = useState('')
  const [email, setEmail] = useState('')
  const [sla, setSla] = useState('3000')
  const [slaError, setSlaError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const parsed = parseSlaMs(sla)
    setSlaError(parsed.error ?? null)
    if (parsed.error) return
    let created: Department | undefined
    const ok = await action.run(
      'department',
      async () => {
        created = await api.registerDepartment({ code: code.trim(), name: name.trim(), idpRealm: idpRealm.trim(), contactEmail: email.trim(), defaultSlaMs: parsed.value })
      },
      'Department registered.',
    )
    if (ok && created) {
      onDone(created)
      onNext()
    }
  }

  if (done) {
    return (
      <StepShell title="Department registered" help="This step is done; the department cannot be edited here.">
        <p>
          <strong>{done.name}</strong> (<span className="mono">{done.code}</span>)
        </p>
        <Nav>
          <button type="button" className="btn primary" onClick={onNext}>
            Continue
          </button>
        </Nav>
      </StepShell>
    )
  }
  return (
    <StepShell title="Which department are you onboarding?" help="Register the government department that owns the data source.">
      <form onSubmit={(e) => void submit(e)}>
        <Field label="Department code" value={code} onChange={(e) => setCode(e.target.value)} required maxLength={60} autoComplete="off" hint="Short and unique, for example FIRE." />
        <Field label="Display name" value={name} onChange={(e) => setName(e.target.value)} required maxLength={200} autoComplete="organization" />
        <Field label="Identity provider realm" value={idpRealm} onChange={(e) => setIdpRealm(e.target.value)} required maxLength={100} autoComplete="off" />
        <Field label="Contact email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required maxLength={200} autoComplete="email" />
        <Field label="Default SLA (ms)" inputMode="numeric" value={sla} onChange={(e) => setSla(e.target.value)} error={slaError} />
        {action.error ? <ErrorNotice error={action.error} /> : null}
        <Nav>
          <button type="submit" className="btn primary" disabled={action.busy !== null}>
            {action.busy ? 'Registering…' : 'Register and continue'}
          </button>
        </Nav>
      </form>
    </StepShell>
  )
}

// --- 2. data source ------------------------------------------------------------------------
function DataSourceStep({
  suffix,
  department,
  done,
  onDone,
  onNext,
  onBack,
}: {
  suffix: string
  department: Department
  done: DataSourceDefinition | null
  onDone: (d: DataSourceDefinition) => void
  onNext: () => void
  onBack: () => void
}) {
  const api = useStaffApi()
  const action = useAction()
  const [code, setCode] = useState(`ds-${suffix}`)
  const [protocol, setProtocol] = useState<string>('REST')
  const [baseHost, setBaseHost] = useState('')
  const [authConfigRef, setAuthConfigRef] = useState('secret:none')

  async function submit(e: FormEvent) {
    e.preventDefault()
    let created: DataSourceDefinition | undefined
    const ok = await action.run(
      'source',
      async () => {
        created = await api.registerDataSource({
          code: code.trim(),
          departmentCode: department.code,
          protocol,
          baseHost: baseHost.trim(),
          authType: 'NONE',
          authConfigRef: authConfigRef.trim(),
        })
      },
      'Data source registered.',
    )
    if (ok && created) {
      onDone(created)
      onNext()
    }
  }

  if (done) {
    return (
      <StepShell title="Data source registered" help="This step is done.">
        <p>
          <span className="mono">{done.code}</span> at <span className="mono">{done.baseHost}</span> ({done.protocol})
        </p>
        <Nav onBack={onBack}>
          <button type="button" className="btn primary" onClick={onNext}>
            Continue
          </button>
        </Nav>
      </StepShell>
    )
  }
  return (
    <StepShell title="Where does the data live?" help={`How Samanvay reaches ${department.name}'s system. The host must be allowed by policy.`}>
      <form onSubmit={(e) => void submit(e)}>
        <Field label="Source code" value={code} onChange={(e) => setCode(e.target.value)} required maxLength={60} autoComplete="off" />
        <div className="field">
          <label htmlFor="protocol">Protocol</label>
          <select id="protocol" value={protocol} onChange={(e) => setProtocol(e.target.value)}>
            {PROTOCOLS.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
        </div>
        <Field label="Base host" value={baseHost} onChange={(e) => setBaseHost(e.target.value)} required maxLength={300} autoComplete="off" hint="Hostname only, for example dept.example.gov." />
        <Field label="Where credentials are stored" value={authConfigRef} onChange={(e) => setAuthConfigRef(e.target.value)} required maxLength={200} autoComplete="off" hint="A secret reference, for example secret:none. Auth type is NONE." />
        {action.error ? <ErrorNotice error={action.error} /> : null}
        <Nav onBack={onBack}>
          <button type="submit" className="btn primary" disabled={action.busy !== null}>
            {action.busy ? 'Registering…' : 'Register and continue'}
          </button>
        </Nav>
      </form>
    </StepShell>
  )
}

// --- 3. connector draft --------------------------------------------------------------------
function ConnectorStep({
  suffix,
  dataSource,
  mappingRef,
  onMappingRef,
  done,
  onDone,
  onNext,
  onBack,
}: {
  suffix: string
  dataSource: DataSourceDefinition
  mappingRef: string
  onMappingRef: (v: string) => void
  done: ConnectorDefinition | null
  onDone: (c: ConnectorDefinition) => void
  onNext: () => void
  onBack: () => void
}) {
  const api = useStaffApi()
  const action = useAction()
  const [connectorId, setConnectorId] = useState(`conn-${suffix}`)
  const [category, setCategory] = useState<string>(CATEGORIES[0].code)
  // The capabilities text follows the category and mapping ref until the admin edits it by hand.
  const [caps, setCaps] = useState<string | null>(null)
  const [inputs, setInputs] = useState('[]')
  const [sla, setSla] = useState('3000')
  const [errors, setErrors] = useState<{ caps?: string; inputs?: string; sla?: string }>({})
  const capsText = caps ?? defaultCapabilities(category, mappingRef)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const parsed = parseSlaMs(sla)
    const found = { caps: jsonError(capsText) ?? undefined, inputs: jsonError(inputs) ?? undefined, sla: parsed.error }
    setErrors(found)
    if (found.caps || found.inputs || found.sla) return
    let created: ConnectorDefinition | undefined
    const ok = await action.run(
      'connector',
      async () => {
        created = await api.createConnectorDraft({
          connectorId: connectorId.trim(),
          dataSourceCode: dataSource.code,
          category: { code: category },
          capabilitiesJson: capsText.trim(),
          inputsJson: inputs.trim(),
          slaMs: parsed.value,
        })
      },
      'Draft connector created.',
    )
    if (ok && created) {
      onDone(created)
      onNext()
    }
  }

  if (done) {
    return (
      <StepShell title="Draft connector created" help="It stays a draft until it is tested and published.">
        <p>
          <span className="mono">{done.ref}</span> for {done.category.code.toLowerCase().replace(/_/g, ' ')}
        </p>
        <Nav onBack={onBack}>
          <button type="button" className="btn primary" onClick={onNext}>
            Continue
          </button>
        </Nav>
      </StepShell>
    )
  }
  return (
    <StepShell title="What will this connector fetch?" help="Create a draft connector. It is not usable until it is tested and published.">
      <form onSubmit={(e) => void submit(e)}>
        <Field label="Connector id" value={connectorId} onChange={(e) => setConnectorId(e.target.value)} required maxLength={60} autoComplete="off" />
        <div className="field">
          <label htmlFor="category">Document type</label>
          <select
            id="category"
            value={category}
            onChange={(e) => {
              setCategory(e.target.value)
              setCaps(null)
            }}
          >
            {CATEGORIES.map((c) => (
              <option key={c.code} value={c.code}>
                {c.label} ({c.code})
              </option>
            ))}
          </select>
        </div>
        <Field label="Mapping ref" value={mappingRef} onChange={(e) => onMappingRef(e.target.value)} required maxLength={120} autoComplete="off" hint="The field mapping saved in the next step; the capabilities below point at it." />
        <p className="hint">Data source: <span className="mono">{dataSource.code}</span></p>
        <details className="tech">
          <summary>Technical detail</summary>
          <TextArea label="Capabilities JSON" value={capsText} onChange={(e) => setCaps(e.target.value)} rows={3} required error={errors.caps} spellCheck={false} />
          <TextArea label="Inputs JSON" value={inputs} onChange={(e) => setInputs(e.target.value)} rows={2} required error={errors.inputs} spellCheck={false} />
          <Field label="SLA (ms)" inputMode="numeric" value={sla} onChange={(e) => setSla(e.target.value)} error={errors.sla} />
        </details>
        {action.error ? <ErrorNotice error={action.error} /> : null}
        <Nav onBack={onBack}>
          <button type="submit" className="btn primary" disabled={action.busy !== null}>
            {action.busy ? 'Creating…' : 'Create draft and continue'}
          </button>
        </Nav>
      </form>
    </StepShell>
  )
}

// --- 4. OpenAPI import + approve field matches ---------------------------------------------
function MappingStep({
  connector,
  mappingRef,
  saved,
  onSaved,
  onNext,
  onBack,
}: {
  connector: ConnectorDefinition
  mappingRef: string
  saved: number | null
  onSaved: (n: number) => void
  onNext: () => void
  onBack: () => void
}) {
  const api = useStaffApi()
  const suggest = useAction()
  const save = useAction()
  const category = connector.category.code
  const defaults = categoryDefaults(category)
  const [spec, setSpec] = useState(() => defaultSpec(category))
  const [operationId, setOperationId] = useState(defaults.operationId)
  const [schemaRef, setSchemaRef] = useState(defaults.schemaRef)
  const [preview, setPreview] = useState<ImportPreview | null>(null)
  const [approved, setApproved] = useState<ReadonlySet<number>>(new Set())
  const [local, setLocal] = useState<string | null>(null)
  // The importer's target schemas; the field stays free text if the list cannot be loaded.
  const schemas = useAsync(() => api.listSchemas().catch(() => [] as string[]), 'schemas')
  const schemaOptions = schemas.status === 'success' ? schemas.data : []

  async function suggestMatches() {
    setLocal(null)
    const err = jsonError(spec)
    if (err) return setLocal('The OpenAPI document is not valid JSON.')
    let out: ImportPreview | undefined
    const ok = await suggest.run(
      'suggest',
      async () => {
        out = await api.importOpenApi({ spec, operationId: operationId.trim(), targetSchemaRef: schemaRef.trim() })
      },
      'Suggestions loaded.',
    )
    if (ok && out) {
      setPreview(out)
      setApproved(new Set())
    }
  }

  function toggle(i: number) {
    const n = new Set(approved)
    if (n.has(i)) n.delete(i)
    else n.add(i)
    setApproved(n)
  }

  async function saveApproved() {
    if (!preview) return
    const rules = approvedRules(preview.suggestions, approved)
    if (rules.length === 0) return setLocal('Approve at least one suggested match before saving.')
    setLocal(null)
    const ok = await save.run('save', () => api.saveMapping({ ref: mappingRef.trim(), connectorRef: connector.ref, rules }), `Saved ${rules.length} approved match${rules.length === 1 ? '' : 'es'}.`)
    if (ok) onSaved(rules.length)
  }

  return (
    <StepShell
      title="Approve field matches"
      help="Suggestions are lexical guesses from an OpenAPI import. Tick only the rows you are sure about; unticked rows are not saved. Importing never publishes anything."
    >
      <details className="tech" open>
        <summary>OpenAPI import</summary>
        <TextArea label="OpenAPI document (JSON)" value={spec} onChange={(e) => setSpec(e.target.value)} rows={8} spellCheck={false} />
        <Field label="Operation id" value={operationId} onChange={(e) => setOperationId(e.target.value)} required autoComplete="off" />
        <Field
          label="Target schema"
          value={schemaRef}
          onChange={(e) => setSchemaRef(e.target.value)}
          required
          autoComplete="off"
          list="schema-refs"
          hint={schemaOptions.length ? 'Pick a known schema or type its ref.' : 'The catalog schema this data maps onto, for example Credential/IncomeCertificate@1.'}
        />
        <datalist id="schema-refs">
          {schemaOptions.map((s) => (
            <option key={s} value={s} />
          ))}
        </datalist>
      </details>
      <div className="actions">
        <button type="button" className="btn primary" onClick={() => void suggestMatches()} disabled={suggest.busy !== null}>
          {suggest.busy ? 'Suggesting…' : 'Suggest matches'}
        </button>
      </div>
      {suggest.error ? <ErrorNotice error={suggest.error} /> : null}

      {preview ? (
        preview.suggestions.length === 0 ? (
          <div className="notice warn">
            <p>No matches were suggested. The importer only proposes lexical matches; adjust the spec, operation id or target schema and try again.</p>
          </div>
        ) : (
          <fieldset className="checklist">
            <legend>Suggested matches ({preview.suggestions.length})</legend>
            {preview.suggestions.map((s, i) => (
              <label key={`${s.source}->${s.target}`} className="check">
                <input type="checkbox" checked={approved.has(i)} onChange={() => toggle(i)} />
                <span>
                  <span className="mono">{s.source}</span> to <span className="mono">{s.target}</span>{' '}
                  <span className="hint">
                    (confidence {s.confidence.toFixed(2)}: {s.rationale})
                  </span>
                </span>
              </label>
            ))}
          </fieldset>
        )
      ) : null}

      {local ? (
        <p className="field-error" role="alert">
          {local}
        </p>
      ) : null}
      {save.error ? <ErrorNotice error={save.error} /> : null}
      {saved !== null ? (
        <div className="notice ok" role="status">
          <p>
            Saved {saved} approved match{saved === 1 ? '' : 'es'} to <span className="mono">{mappingRef}</span>. Unticked suggestions were not written.
          </p>
        </div>
      ) : null}

      <Nav onBack={onBack}>
        <button type="button" className="btn" onClick={() => void saveApproved()} disabled={!preview || preview.suggestions.length === 0 || save.busy !== null}>
          {save.busy ? 'Saving…' : 'Save approved matches'}
        </button>
        <button type="button" className="btn primary" onClick={onNext} disabled={saved === null}>
          Continue
        </button>
      </Nav>
    </StepShell>
  )
}

// --- 5. test -------------------------------------------------------------------------------
function TestStep({
  connector,
  report,
  onReport,
  onNext,
  onBack,
}: {
  connector: ConnectorDefinition
  report: ConnectorTestReport | null
  onReport: (r: ConnectorTestReport) => void
  onNext: () => void
  onBack: () => void
}) {
  const api = useStaffApi()
  const action = useAction()

  async function run() {
    let out: ConnectorTestReport | undefined
    const ok = await action.run(
      'test',
      async () => {
        out = await api.testConnector(connector.ref)
      },
      'Test finished.',
    )
    if (ok && out) onReport(out)
  }

  return (
    <StepShell title="Test the connector" help="A dry check before anything goes live. Publishing is blocked until it passes.">
      <div className="actions">
        <button type="button" className="btn primary" onClick={() => void run()} disabled={action.busy !== null}>
          {action.busy ? 'Testing…' : report ? 'Run the test again' : 'Run test'}
        </button>
      </div>
      {action.error ? <ErrorNotice error={action.error} /> : null}
      {report ? (
        report.passed ? (
          <div className="notice ok" role="status">
            <p>
              <strong>Test passed.</strong> You can publish <span className="mono">{connector.ref}</span>.
            </p>
          </div>
        ) : (
          <div className="notice bad" role="alert">
            <p>
              <strong>Test failed.</strong> Publishing stays blocked.
            </p>
            {report.failures.length ? (
              <ul>
                {report.failures.map((f) => (
                  <li key={f}>{f}</li>
                ))}
              </ul>
            ) : null}
          </div>
        )
      ) : null}
      <Nav onBack={onBack}>
        <button type="button" className="btn primary" onClick={onNext} disabled={!report?.passed}>
          Continue
        </button>
      </Nav>
    </StepShell>
  )
}

// --- 6. publish ----------------------------------------------------------------------------
function PublishStep({
  connector,
  report,
  published,
  onPublished,
  onBack,
}: {
  connector: ConnectorDefinition
  report: ConnectorTestReport
  published: ConnectorDefinition | null
  onPublished: (c: ConnectorDefinition) => void
  onBack: () => void
}) {
  const api = useStaffApi()
  const action = useAction()

  async function publish() {
    let out: ConnectorDefinition | undefined
    const ok = await action.run(
      'publish',
      async () => {
        out = await api.publishConnector(connector.ref, report)
      },
      'Connector published.',
    )
    if (ok && out) onPublished(out)
  }

  return (
    <StepShell title="Publish the connector" help="This makes the connector available to the control plane. It is a human decision and needs the passing test report from the last step.">
      {published ? (
        <div className="notice ok" role="status">
          <p>
            <strong>Published.</strong> <span className="mono">{published.ref}</span> is available. <Link to="/staff/admin/departments">See it under Departments</Link>.
          </p>
        </div>
      ) : (
        <>
          {action.error ? <ErrorNotice error={action.error} /> : null}
          <Nav onBack={onBack}>
            <button type="button" className="btn primary" onClick={() => void publish()} disabled={!report.passed || action.busy !== null}>
              {action.busy ? 'Publishing…' : 'Publish'}
            </button>
          </Nav>
        </>
      )}
    </StepShell>
  )
}
