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
import { parseFieldLines } from '../lib/schemas'

/**
 * The central schema: the shared vocabulary every department's fields are mapped onto. Admins read it here and add a new
 * schema or a new version. A schema is never edited in place (published connectors map onto it), so the form only adds.
 */
export function SchemasPage() {
  const api = useStaffApi()
  const [reload, setReload] = useState(0)
  const schemas = useAsync(() => api.listSchemaDetails(), `schemas:${reload}`)
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
        The shared field names every department&rsquo;s documents are matched onto. Onboarding proposes matches against the
        highest version for a document category. A schema is not edited in place: to change one, add a new version
        (<span className="mono">@2</span>).
      </p>

      {schemas.status === 'loading' ? <Loading variant="table" label="Loading the schemas" /> : null}
      {schemas.status === 'error' ? <ErrorNotice error={schemas.error} /> : null}
      {schemas.status === 'success' ? (
        <table className="table" aria-label="Central schemas">
          <thead>
            <tr>
              <th scope="col">Schema</th>
              <th scope="col">Document</th>
              <th scope="col">Fields</th>
            </tr>
          </thead>
          <tbody>
            {schemas.data.map((s) => (
              <tr key={s.ref}>
                <td className="mono">{s.ref}</td>
                <td>{s.category ? humanize(s.category) : <Badge tone="neutral">No category</Badge>}</td>
                <td>
                  <ul className="plain">
                    {s.fields.map((f) => (
                      <li key={f.name}>
                        <span className="mono">{f.name}</span> ({f.type}
                        {f.required ? ', required' : ''})
                      </li>
                    ))}
                  </ul>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
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
