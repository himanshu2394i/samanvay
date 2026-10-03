import type { SchemaField } from '../../../api/staffTypes'

const TYPES = ['string', 'integer', 'number', 'boolean']

/**
 * Reads the "Fields" box of the add-schema form: one field per line, "name type" or "name type required".
 * Blank lines are ignored. A line it cannot read is reported by number rather than guessed at; the server checks
 * the names and types again.
 */
export function parseFieldLines(text: string): { fields: SchemaField[] } | { error: string } {
  const fields: SchemaField[] = []
  const lines = text.split('\n')
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!.trim()
    if (!line) continue
    const parts = line.split(/\s+/)
    if (parts.length < 2) {
      return { error: `Line ${i + 1}: write the field name and its type, for example "annualIncome integer required".` }
    }
    const [name, type, flag, ...rest] = parts as [string, string, string | undefined, ...string[]]
    if (!TYPES.includes(type)) {
      return { error: `Line ${i + 1}: the type must be one of ${TYPES.join(', ')}.` }
    }
    if ((flag !== undefined && flag !== 'required') || rest.length > 0) {
      return { error: `Line ${i + 1}: after the type only "required" is allowed.` }
    }
    fields.push({ name, type, required: flag === 'required' })
  }
  if (fields.length === 0) return { error: 'Add at least one field.' }
  return { fields }
}
