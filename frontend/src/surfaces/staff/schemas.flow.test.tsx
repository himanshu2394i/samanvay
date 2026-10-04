import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { overview } from '../../test/staffFixtures'
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
const MARKS = {
  ref: 'Credential/Marks@1',
  name: 'Marks',
  version: 1,
  category: 'MARKS',
  fields: [
    { name: 'percentage', type: 'number', required: true },
    { name: 'board', type: 'string', required: false },
  ],
}
const OLD = { ref: 'PropertyRecord@1', name: 'PropertyRecord', version: 1, category: null, fields: [{ name: 'propertyId', type: 'unspecified', required: true }] }

const SCHEMAS = (body: unknown) => ({ method: 'GET', path: '/api/catalog/schema-details', reply: { body } })
const OVERVIEW = { method: 'GET', path: '/api/ops/overview', reply: { body: overview } }

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

describe('admin: central schema, document by document', () => {
  it('shows only schemas that describe a document, each with its fields, types and which are required', async () => {
    const m = mockFetch([SCHEMAS([INCOME, OLD]), OVERVIEW])
    renderStaff({ route: '/staff/admin/schemas', fetchImpl: m.fetchImpl, auth: admin() })

    expect(await screen.findByText('Credential/IncomeCertificate@1')).toBeInTheDocument()
    const fields = screen.getByRole('table', { name: 'Central fields of Credential/IncomeCertificate@1' })
    const annual = within(within(fields).getByText('annualIncome').closest('tr') as HTMLElement)
    expect(annual.getByText('integer')).toBeInTheDocument()
    expect(annual.getByText('Required')).toBeInTheDocument()
    expect(within(within(fields).getByText('holderName').closest('tr') as HTMLElement).getByText('Optional')).toBeInTheDocument()
    // a schema with no document category is not part of this view
    expect(screen.queryByText('PropertyRecord@1')).not.toBeInTheDocument()
    expect(screen.queryByText('No category')).not.toBeInTheDocument()
    expect(m.unhandled).toEqual([])
  })

  it('shows, for each onboarded department that provides the document, how its fields map onto the central schema', async () => {
    const m = mockFetch([SCHEMAS([INCOME, MARKS]), OVERVIEW])
    renderStaff({ route: '/staff/admin/schemas', fetchImpl: m.fetchImpl, auth: admin() })

    const marks = await screen.findByRole('table', { name: 'Mapping from EDUCATION onto Credential/Marks@1' })
    expect(screen.getByText(/State Board of Education/)).toBeInTheDocument()
    const pct = within(within(marks).getByText('pct').closest('tr') as HTMLElement)
    expect(pct.getByText('percentage')).toBeInTheDocument()
    expect(pct.getByText('Required')).toBeInTheDocument()
    const board = within(within(marks).getByText('board_name').closest('tr') as HTMLElement)
    expect(board.getByText('board')).toBeInTheDocument()
    expect(board.queryByText('Required')).not.toBeInTheDocument()

    // the income certificate is provided by Revenue, with one required field nobody fills
    const income = screen.getByRole('table', { name: 'Mapping from REVENUE onto Credential/IncomeCertificate@1' })
    expect(within(income).getByText('income')).toBeInTheDocument()
    expect(screen.getByText(/Not mapped yet: holderName/)).toBeInTheDocument()
  })

  it('says so when no onboarded department provides a document', async () => {
    const none = { generatedAt: overview.generatedAt, departments: [] }
    const m = mockFetch([SCHEMAS([MARKS]), { method: 'GET', path: '/api/ops/overview', reply: { body: none } }])
    renderStaff({ route: '/staff/admin/schemas', fetchImpl: m.fetchImpl, auth: admin() })
    expect(await screen.findByText('No onboarded department provides this document yet')).toBeInTheDocument()
  })

  it('keeps the schema list when the overview cannot be loaded, and says so', async () => {
    const m = mockFetch([SCHEMAS([INCOME]), { method: 'GET', path: '/api/ops/overview', reply: { status: 500, body: { status: 500 } } }])
    renderStaff({ route: '/staff/admin/schemas', fetchImpl: m.fetchImpl, auth: admin() })
    expect(await screen.findByText('Credential/IncomeCertificate@1')).toBeInTheDocument()
    expect(await screen.findByText(/Which departments provide each document could not be loaded/)).toBeInTheDocument()
    expect(screen.queryByText('No onboarded department provides this document yet')).not.toBeInTheDocument()
  })

  it('adds a schema from a ref, a category and one field per line, then shows it', async () => {
    let added = false
    const NEW = { ref: 'Credential/Marks@2', name: 'Marks', version: 2, category: 'MARKS', fields: [{ name: 'percentage', type: 'number', required: true }] }
    const m = mockFetch([
      { method: 'GET', path: '/api/catalog/schema-details', reply: () => ({ body: added ? [INCOME, NEW] : [INCOME] }) },
      OVERVIEW,
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
    await screen.findByText('Credential/IncomeCertificate@1')

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
    expect(await screen.findByRole('table', { name: 'Central fields of Credential/Marks@2' })).toBeInTheDocument()
  })

  it('shows the server refusal (for example an existing ref) and does not pretend it worked', async () => {
    const m = mockFetch([
      SCHEMAS([INCOME]),
      OVERVIEW,
      {
        method: 'POST',
        path: '/api/catalog/schemas',
        reply: { status: 400, body: { title: 'Invalid request', detail: 'Schema Credential/IncomeCertificate@1 already exists. Schemas are not edited in place: add a new version (@2).' } },
      },
    ])
    renderStaff({ route: '/staff/admin/schemas', fetchImpl: m.fetchImpl, auth: admin() })
    await screen.findByText('Credential/IncomeCertificate@1')
    await userEvent.type(screen.getByLabelText(/Schema ref/), 'Credential/IncomeCertificate@1')
    await userEvent.type(screen.getByLabelText(/Document category/), 'INCOME_CERTIFICATE')
    await userEvent.type(screen.getByLabelText(/Fields/), 'annualIncome integer')
    await userEvent.click(screen.getByRole('button', { name: 'Add schema' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/not edited in place/)
    expect(screen.queryByText(/Added Credential/)).not.toBeInTheDocument()
  })

  it('does not call the server when a field line cannot be read', async () => {
    const m = mockFetch([SCHEMAS([INCOME]), OVERVIEW])
    renderStaff({ route: '/staff/admin/schemas', fetchImpl: m.fetchImpl, auth: admin() })
    await screen.findByText('Credential/IncomeCertificate@1')
    await userEvent.type(screen.getByLabelText(/Schema ref/), 'Credential/X@1')
    await userEvent.type(screen.getByLabelText(/Document category/), 'X_DOC')
    await userEvent.type(screen.getByLabelText(/Fields/), 'oops')
    await userEvent.click(screen.getByRole('button', { name: 'Add schema' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/Line 1/)
    expect(m.find('POST', '/api/catalog/schemas')).toHaveLength(0)
  })

  it('is not available to an officer', async () => {
    const m = mockFetch([SCHEMAS([INCOME]), OVERVIEW])
    renderStaff({ route: '/staff/admin/schemas', fetchImpl: m.fetchImpl, auth: staffAuth(['officer']) })
    expect(await screen.findByRole('heading', { name: /not available|do not have access|cannot use/i })).toBeInTheDocument()
    expect(screen.queryByText('Credential/IncomeCertificate@1')).not.toBeInTheDocument()
  })
})
