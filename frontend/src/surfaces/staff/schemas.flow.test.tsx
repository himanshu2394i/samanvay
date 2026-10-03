import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockFetch, renderStaff, staffAuth } from '../../test/utils'
import { parseFieldLines } from './lib/schemas'

const admin = () => staffAuth(['admin'])

const INCOME = {
  ref: 'Credential/IncomeCertificate@1',
  name: 'IncomeCertificate',
  version: 1,
  category: 'INCOME_CERTIFICATE',
  fields: [
    { name: 'annualIncome', type: 'integer', required: true },
    { name: 'holderName', type: 'string', required: false },
  ],
}
const OLD = { ref: 'PropertyRecord@1', name: 'PropertyRecord', version: 1, category: null, fields: [{ name: 'propertyId', type: 'unspecified', required: true }] }

describe('parseFieldLines', () => {
  it('reads one field per line: name, type, optional "required"', () => {
    expect(parseFieldLines('annualIncome integer required\nholderName string\n\n  district   string  ')).toEqual({
      fields: [
        { name: 'annualIncome', type: 'integer', required: true },
        { name: 'holderName', type: 'string', required: false },
        { name: 'district', type: 'string', required: false },
      ],
    })
  })

  it('explains a line it cannot read instead of guessing', () => {
    expect(parseFieldLines('annualIncome')).toEqual({ error: 'Line 1: write the field name and its type, for example "annualIncome integer required".' })
    expect(parseFieldLines('a string maybe')).toEqual({ error: 'Line 1: after the type only "required" is allowed.' })
    expect(parseFieldLines('')).toEqual({ error: 'Add at least one field.' })
  })
})

describe('admin: central schema', () => {
  it('lists every schema with its category, fields, types and which are required', async () => {
    const m = mockFetch([{ method: 'GET', path: '/api/catalog/schema-details', reply: { body: [INCOME, OLD] } }])
    renderStaff({ route: '/staff/admin/schemas', fetchImpl: m.fetchImpl, auth: admin() })

    const table = await screen.findByRole('table', { name: 'Central schemas' })
    const row = within(table).getByText('Credential/IncomeCertificate@1').closest('tr') as HTMLElement
    expect(within(row).getByText('Income certificate')).toBeInTheDocument()
    expect(within(row).getByText(/annualIncome/)).toBeInTheDocument()
    expect(within(row).getByText(/integer/)).toBeInTheDocument()
    expect(within(row).getByText(/required/)).toBeInTheDocument()
    // an older schema with no category says so rather than showing nothing
    const old = within(table).getByText('PropertyRecord@1').closest('tr') as HTMLElement
    expect(within(old).getByText('No category')).toBeInTheDocument()
    expect(m.unhandled).toEqual([])
  })

  it('adds a schema from a ref, a category and one field per line, then shows it', async () => {
    let added = false
    const NEW = { ref: 'Credential/Marks@2', name: 'Marks', version: 2, category: 'MARKS', fields: [{ name: 'percentage', type: 'number', required: true }] }
    const m = mockFetch([
      { method: 'GET', path: '/api/catalog/schema-details', reply: () => ({ body: added ? [INCOME, NEW] : [INCOME] }) },
      {
        method: 'POST',
        path: '/api/catalog/schemas',
        reply: () => {
          added = true
          return { body: NEW }
        },
      },
    ])
    renderStaff({ route: '/staff/admin/schemas', fetchImpl: m.fetchImpl, auth: admin() })
    await screen.findByRole('table', { name: 'Central schemas' })

    await userEvent.type(screen.getByLabelText(/Schema ref/), 'Credential/Marks@2')
    await userEvent.type(screen.getByLabelText(/Document category/), 'MARKS')
    await userEvent.type(screen.getByLabelText(/Fields/), 'percentage number required')
    await userEvent.click(screen.getByRole('button', { name: 'Add schema' }))

    expect(await screen.findByText(/Added Credential\/Marks@2/)).toBeInTheDocument()
    expect(m.find('POST', '/api/catalog/schemas')[0]?.body).toEqual({
      ref: 'Credential/Marks@2',
      category: 'MARKS',
      fields: [{ name: 'percentage', type: 'number', required: true }],
    })
    expect(within(await screen.findByRole('table', { name: 'Central schemas' })).getByText('Credential/Marks@2')).toBeInTheDocument()
  })

  it('shows the server refusal (for example an existing ref) and does not pretend it worked', async () => {
    const m = mockFetch([
      { method: 'GET', path: '/api/catalog/schema-details', reply: { body: [INCOME] } },
      {
        method: 'POST',
        path: '/api/catalog/schemas',
        reply: { status: 400, body: { title: 'Invalid request', detail: 'Schema Credential/IncomeCertificate@1 already exists. Schemas are not edited in place: add a new version (@2).' } },
      },
    ])
    renderStaff({ route: '/staff/admin/schemas', fetchImpl: m.fetchImpl, auth: admin() })
    await screen.findByRole('table', { name: 'Central schemas' })
    await userEvent.type(screen.getByLabelText(/Schema ref/), 'Credential/IncomeCertificate@1')
    await userEvent.type(screen.getByLabelText(/Document category/), 'INCOME_CERTIFICATE')
    await userEvent.type(screen.getByLabelText(/Fields/), 'annualIncome integer')
    await userEvent.click(screen.getByRole('button', { name: 'Add schema' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/not edited in place/)
    expect(screen.queryByText(/Added Credential/)).not.toBeInTheDocument()
  })

  it('does not call the server when a field line cannot be read', async () => {
    const m = mockFetch([{ method: 'GET', path: '/api/catalog/schema-details', reply: { body: [INCOME] } }])
    renderStaff({ route: '/staff/admin/schemas', fetchImpl: m.fetchImpl, auth: admin() })
    await screen.findByRole('table', { name: 'Central schemas' })
    await userEvent.type(screen.getByLabelText(/Schema ref/), 'Credential/X@1')
    await userEvent.type(screen.getByLabelText(/Document category/), 'X_DOC')
    await userEvent.type(screen.getByLabelText(/Fields/), 'oops')
    await userEvent.click(screen.getByRole('button', { name: 'Add schema' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/Line 1/)
    expect(m.find('POST', '/api/catalog/schemas')).toHaveLength(0)
  })

  it('is not available to an officer', async () => {
    const m = mockFetch([{ method: 'GET', path: '/api/catalog/schema-details', reply: { body: [INCOME] } }])
    renderStaff({ route: '/staff/admin/schemas', fetchImpl: m.fetchImpl, auth: staffAuth(['officer']) })
    expect(await screen.findByRole('heading', { name: /not available|do not have access|cannot use/i })).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'Central schemas' })).not.toBeInTheDocument()
  })
})
