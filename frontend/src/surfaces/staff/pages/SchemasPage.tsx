import { useState, type FormEvent } from 'react'
import { useStaffApi } from '../../../api/apiContext'
import { Badge } from '../../../ui/Badge'
import { ErrorNotice } from '../../../ui/ErrorNotice'
import { Field } from '../../../ui/Field'
import { humanize } from '../../../ui/format'
import { Loading } from '../../../ui/Loading'
import { TextArea } from '../../../ui/TextArea'
import { useAction } from '../../../ui/useAction'
import { useAsync } from '../../../ui/useAsync'
import type { OverviewDepartment, SchemaSummary } from '../../../api/staffTypes'
import { parseFieldLines } from '../lib/schemas'

/**
 * The central schema: the shared vocabulary every department's fields are mapped onto. Admins read it here and add a new
 * schema or a new version. A schema is never edited in place (published connectors map onto it), so the form only adds.
 */
export function SchemasPage() {
  const api = useStaffApi()
  const [reload, setReload] = useState(0)
  const schemas = useAsync(() => api.listSchemaDetails(), `schemas:${reload}`)
  // The mappings come from the overview; if it fails the schema list still shows.
  const overview = useAsync(() => api.getOverview(), 'overview')
  const documents = schemas.status === 'success' ? schemas.data.filter((s) => s.category) : []
  const add = useAction()
  const [ref, setRef] = useState('')
  const [category, setCategory] = useState('')
  const [fieldText, setFieldText] = useState('')
  const [formError, setFormError] = useState<string | null>(null)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    add.clear()
    const parsed = parseFieldLines(fieldText)
    if ('error' in parsed) {
      setFormError(parsed.error)
      return
    }
    setFormError(null)
    const draft = { ref: ref.trim(), category: category.trim(), fields: parsed.fields }
    const ok = await add.run('add', () => api.addSchema(draft), `Added ${draft.ref}.`)
    if (ok) {
      setRef('')
      setCategory('')
      setFieldText('')
      setReload((n) => n + 1)
    }
  }

  return (
    <section aria-labelledby="schemas-h">
      <h1 id="schemas-h">Central schema</h1>
      <p className="lede">
        For each document: the shared field names it has, and how every onboarded department&rsquo;s own fields are mapped onto
        them. Onboarding proposes matches against the highest version for a document category. A schema is not edited in place:
        to change one, add a new version (<span className="mono">@2</span>).
      </p>

      {schemas.status === 'loading' ? <Loading variant="table" label="Loading the schemas" /> : null}
      {schemas.status === 'error' ? <ErrorNotice error={schemas.error} /> : null}
      {overview.status === 'error' ? (
        <p className="notice warn" role="status">
          Which departments provide each document could not be loaded, so the mappings are not shown.{' '}
          <button type="button" className="btn" onClick={overview.reload}>
            Try again
          </button>
        </p>
      ) : null}
      {schemas.status === 'success' && documents.length === 0 ? <p>No schema describes a document yet. Add one below.</p> : null}
      {schemas.status === 'success' ? (
        <ul className="stack plain">
          {documents.map((s) => (
            <li key={s.ref} className="card">
              <h2 style={{ marginTop: 0 }}>
                {humanize(s.category as string)} <span className="mono hint">{s.ref}</span>
              </h2>
              <table aria-label={`Central fields of ${s.ref}`}>
                <thead>
                  <tr>
                    <th scope="col">Central field</th>
                    <th scope="col">Type</th>
                    <th scope="col">Required</th>
                  </tr>
                </thead>
                <tbody>
                  {s.fields.map((f) => (
                    <tr key={f.name}>
                      <td className="mono">{f.name}</td>
                      <td>{f.type}</td>
                      <td>{f.required ? 'Required' : 'Optional'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {overview.status === 'success' ? <Providers schema={s} departments={overview.data.departments} /> : null}
            </li>
          ))}
        </ul>
      ) : null}

      <form className="card spaced" onSubmit={(e) => void onSubmit(e)} aria-labelledby="add-schema-h">
        <h2 id="add-schema-h" style={{ marginTop: 0 }}>
          Add a schema or a new version
        </h2>
        <Field
          label="Schema ref"
          value={ref}
          onChange={(e) => setRef(e.target.value)}
          required
          hint="Name and version, for example Credential/Marks@2."
        />
        <Field
          label="Document category"
          value={category}
          onChange={(e) => setCategory(e.target.value)}
          required
          hint="Upper case with underscores, for example MARKS. Onboarding finds the schema by this."
        />
        <TextArea
          label="Fields"
          value={fieldText}
          onChange={(e) => setFieldText(e.target.value)}
          required
          rows={5}
          hint={'One per line: name, type (string, integer, number or boolean), and "required" if it must always be present. For example: percentage number required'}
        />
        {formError ? (
          <p role="alert" className="field-error">
            {formError}
          </p>
        ) : null}
        {add.error ? <ErrorNotice error={add.error} /> : null}
        {add.done ? (
          <p className="notice ok" role="status">
            {add.done}
          </p>
        ) : null}
        <button type="submit" className="btn primary" disabled={add.busy !== null} aria-busy={add.busy !== null || undefined}>
          Add schema
        </button>
      </form>
    </section>
  )
}

/** For one central schema: each onboarded department that provides this document and how its fields map onto it. */
function Providers({ schema, departments }: { schema: SchemaSummary; departments: OverviewDepartment[] }) {
  const providers = departments.flatMap((d) => d.documents.filter((x) => x.centralSchemaRef === schema.ref).map((doc) => ({ dept: d, doc })))
  if (providers.length === 0) return <p className="hint">No onboarded department provides this document yet</p>
  return (
    <>
      {providers.map(({ dept, doc }) => (
        <div key={dept.code} className="spaced">
          <h3>
            {dept.name} <span className="mono hint">{dept.code}</span>
          </h3>
          {doc.mappings.length === 0 ? (
            <p>No field is mapped yet.</p>
          ) : (
            <table aria-label={`Mapping from ${dept.code} onto ${schema.ref}`}>
              <thead>
                <tr>
                  <th scope="col">Department field</th>
                  <th scope="col">Central field</th>
                  <th scope="col">Required</th>
                </tr>
              </thead>
              <tbody>
                {doc.mappings.map((m) => (
                  <tr key={`${m.source}:${m.target}`}>
                    <td className="mono">{m.source}</td>
                    <td className="mono">{m.target}</td>
                    <td>{m.required ? <Badge tone="ok">Required</Badge> : null}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {doc.unmappedRequired.length > 0 ? (
            <p className="notice warn" role="status">
              Not mapped yet: {doc.unmappedRequired.join(', ')}. The central schema requires {doc.unmappedRequired.length === 1 ? 'this field' : 'these fields'}.
            </p>
          ) : null}
        </div>
      ))}
    </>
  )
}
