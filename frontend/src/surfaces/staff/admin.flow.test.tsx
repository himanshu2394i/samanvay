import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { Route } from '../../test/utils'
import { connector } from '../../test/staffFixtures'
import { DEPARTMENTS, mockFetch, renderStaff, SCHOLARSHIP, DRAFT_JOURNEY, staffAuth } from '../../test/utils'

const admin = () => staffAuth(['admin'])

describe('admin: catalog view', () => {
  const routes: Route[] = [
    { method: 'GET', path: '/api/catalog/journeys', reply: { body: [SCHOLARSHIP, DRAFT_JOURNEY] } },
    { method: 'GET', path: '/api/catalog/departments', reply: { body: DEPARTMENTS } },
    { method: 'GET', path: '/api/catalog/connectors', reply: { body: [connector] } },
    { method: 'GET', path: '/api/catalog/data-sources', reply: { body: [] } },
  ]

  it('shows journeys, departments and connectors', async () => {
    const m = mockFetch(routes)
    renderStaff({ route: '/staff/admin/catalog', fetchImpl: m.fetchImpl, auth: admin() })
    const journeys = await screen.findByRole('table', { name: 'Journeys' })
    expect(within(journeys).getByText('POST_MATRIC_SCHOLARSHIP')).toBeInTheDocument()
    expect(within(journeys).getAllByText('72 h')).toHaveLength(2)
    expect(within(journeys).getByText('Published')).toBeInTheDocument()
    expect(within(journeys).getByText('Draft')).toBeInTheDocument()
    expect(within(journeys).getAllByText(/Income certificate/)[0]).toBeInTheDocument()
    expect(within(journeys).getAllByText('(REVENUE)').length).toBeGreaterThan(0)
    // readiness: the published journey is Live; the draft has no connectors wired, so it is Pending
    expect(within(journeys).getByText('Live')).toBeInTheDocument()
    expect(within(journeys).getByText('Pending')).toBeInTheDocument()
    expect(within(journeys).queryByRole('button', { name: 'Publish' })).not.toBeInTheDocument()

    const departments = screen.getByRole('table', { name: 'Departments' })
    expect(within(departments).getByText('Direct Benefit Transfer')).toBeInTheDocument()

    const connectors = screen.getByRole('table', { name: 'Connectors' })
    expect(within(connectors).getByText('rev-conn-1@1')).toBeInTheDocument()
    expect(within(connectors).getByText('3000 ms')).toBeInTheDocument()
    expect(m.unhandled).toEqual([])
  })

  it('computes readiness and lets an admin publish a ready draft', async () => {
    let published = false
    const ready = {
      ...DRAFT_JOURNEY,
      code: 'READY_ONE',
      name: 'Ready service',
      requiredCategories: ['INCOME_CERTIFICATE'],
      policy: { ...DRAFT_JOURNEY.policy, sources: { INCOME_CERTIFICATE: 'REVENUE' } },
    }
    const ds = { code: 'revenue-rest-mock', departmentCode: 'REVENUE', protocol: 'REST', baseHost: 'rev.example.gov', healthStatus: 'GREEN', detail: null }
    const m = mockFetch([
      { method: 'GET', path: '/api/catalog/journeys', reply: () => ({ body: [published ? { ...ready, status: 'PUBLISHED' } : ready] }) },
      { method: 'GET', path: '/api/catalog/departments', reply: { body: DEPARTMENTS } },
      { method: 'GET', path: '/api/catalog/connectors', reply: { body: [connector] } },
      { method: 'GET', path: '/api/catalog/data-sources', reply: { body: [ds] } },
      { method: 'POST', path: '/api/catalog/journeys/READY_ONE/publish', reply: () => { published = true; return { body: { ...ready, status: 'PUBLISHED' } } } },
    ])
    renderStaff({ route: '/staff/admin/catalog', fetchImpl: m.fetchImpl, auth: admin() })
    const journeys = await screen.findByRole('table', { name: 'Journeys' })
    expect(within(journeys).getByText('Ready to publish')).toBeInTheDocument()
    await userEvent.click(within(journeys).getByRole('button', { name: 'Publish' }))
    await waitFor(() => expect(m.find('POST', '/api/catalog/journeys/READY_ONE/publish')).toHaveLength(1))
    // after publish + reload the journey is Live and the Publish button is gone
    expect(await within(await screen.findByRole('table', { name: 'Journeys' })).findByText('Live')).toBeInTheDocument()
    expect(within(screen.getByRole('table', { name: 'Journeys' })).queryByRole('button', { name: 'Publish' })).not.toBeInTheDocument()
    expect(m.unhandled).toEqual([])
  })

  it('surfaces a failure and can retry', async () => {
    let fail = true
    const m = mockFetch([
      { method: 'GET', path: '/api/catalog/journeys', reply: () => (fail ? { status: 500, body: { status: 500 } } : { body: [] }) },
      { method: 'GET', path: '/api/catalog/departments', reply: { body: [] } },
      { method: 'GET', path: '/api/catalog/connectors', reply: { body: [] } },
    ])
    renderStaff({ route: '/staff/admin/catalog', fetchImpl: m.fetchImpl, auth: admin() })
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    fail = false
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('No journeys are registered.')).toBeInTheDocument()
  })
})

describe('admin: OpenAPI onboarding importer', () => {
  const draft = { ...connector, ref: 'conn-1@1', status: 'DRAFT' }
  const suggestions = {
    sourceFields: ['annual_income', 'holder_name'],
    targetFields: ['annualIncome', 'holderName'],
    suggestions: [
      { source: 'annual_income', target: 'annualIncome', confidence: 0.92, rationale: 'same words', approved: false },
      { source: 'holder_name', target: 'holderName', confidence: 0.88, rationale: 'same words', approved: false },
    ],
  }

  function routes(over: Partial<Record<string, Route['reply']>> = {}): Route[] {
    return [
      { method: 'GET', path: '/api/catalog/schemas', reply: { body: ['Credential/IncomeCertificate@1', 'Credential/Marks@1'] } },
      { method: 'POST', path: '/api/catalog/departments', reply: over.department ?? ((c) => ({ body: { code: (c.body as { code: string }).code, name: (c.body as { name: string }).name, status: 'ACTIVE' } })) },
      { method: 'POST', path: '/api/catalog/data-sources', reply: over.source ?? ((c) => ({ body: c.body })) },
      { method: 'POST', path: '/api/catalog/connectors', reply: over.connector ?? ((c) => ({ body: { ...draft, category: (c.body as { category: unknown }).category } })) },
      { method: 'POST', path: '/api/catalog/import/openapi', reply: over.import ?? { body: suggestions } },
      { method: 'POST', path: '/api/catalog/mappings', reply: over.mapping ?? ((c) => ({ body: c.body })) },
      { method: 'POST', path: '/api/catalog/connectors/conn-1%401/test', reply: over.test ?? { body: { passed: true, failures: [] } } },
      { method: 'POST', path: '/api/catalog/connectors/conn-1%401/publish', reply: over.publish ?? { body: { ...draft, status: 'PUBLISHED' } } },
    ]
  }

  async function fillDepartment() {
    const code = await screen.findByLabelText(/Department code/)
    await userEvent.clear(code)
    await userEvent.type(code, 'FIRE')
    await userEvent.type(screen.getByLabelText(/Display name/), 'Fire Services')
    await userEvent.type(screen.getByLabelText(/Identity provider realm/), 'fire')
    await userEvent.type(screen.getByLabelText(/Contact email/), 'fire@example.gov')
    await userEvent.click(screen.getByRole('button', { name: 'Register and continue' }))
  }

  async function fillSource() {
    const code = await screen.findByLabelText(/Source code/)
    await userEvent.clear(code)
    await userEvent.type(code, 'fire-ds')
    await userEvent.type(screen.getByLabelText(/Base host/), 'fire.example.gov')
    await userEvent.click(screen.getByRole('button', { name: 'Register and continue' }))
  }

  async function createConnector() {
    const id = await screen.findByLabelText(/Connector id/)
    await userEvent.clear(id)
    await userEvent.type(id, 'fire-conn')
    await userEvent.click(screen.getByRole('button', { name: 'Create draft and continue' }))
  }

  it('walks department, source, connector, import, test and publish, sending exactly what was entered', async () => {
    const m = mockFetch(routes())
    renderStaff({ route: '/staff/admin/onboarding', fetchImpl: m.fetchImpl, auth: admin() })

    await fillDepartment()
    expect(m.find('POST', '/api/catalog/departments')[0]?.body).toEqual({ code: 'FIRE', name: 'Fire Services', idpRealm: 'fire', contactEmail: 'fire@example.gov', defaultSlaMs: 3000 })

    await fillSource()
    expect(m.find('POST', '/api/catalog/data-sources')[0]?.body).toEqual({
      code: 'fire-ds',
      departmentCode: 'FIRE',
      protocol: 'REST',
      baseHost: 'fire.example.gov',
      authType: 'NONE',
      authConfigRef: 'secret:none',
    })

    await userEvent.selectOptions(await screen.findByLabelText('Document type'), 'MARKS')
    await createConnector()
    const draftBody = m.find('POST', '/api/catalog/connectors')[0]?.body as { capabilitiesJson: string; category: unknown; dataSourceCode: string; connectorId: string }
    expect(draftBody.category).toEqual({ code: 'MARKS' })
    expect(draftBody.dataSourceCode).toBe('fire-ds')
    expect(draftBody.connectorId).toBe('fire-conn')
    expect(JSON.parse(draftBody.capabilitiesJson).FETCH.endpoint).toBe('/marks')

    // import: defaults follow the category; the schema list comes from the catalog
    expect(await screen.findByLabelText(/Operation id/)).toHaveValue('getMarks')
    expect(screen.getByLabelText(/Target schema/)).toHaveValue('Credential/Marks@1')
    await userEvent.click(screen.getByRole('button', { name: 'Suggest matches' }))
    expect(await screen.findByRole('group', { name: /Suggested matches \(2\)/ })).toBeInTheDocument()
    expect(m.find('POST', '/api/catalog/import/openapi')[0]?.body).toMatchObject({ operationId: 'getMarks', targetSchemaRef: 'Credential/Marks@1' })

    // nothing is approved by default; saving needs at least one tick and writes only the ticked rows
    await userEvent.click(screen.getByRole('button', { name: 'Save approved matches' }))
    expect(screen.getByText('Approve at least one suggested match before saving.')).toBeInTheDocument()
    expect(m.find('POST', '/api/catalog/mappings')).toHaveLength(0)
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()

    await userEvent.click(screen.getByRole('checkbox', { name: /annual_income to annualIncome/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Save approved matches' }))
    expect(await screen.findByText(/Saved 1 approved match to/)).toBeInTheDocument()
    expect(m.find('POST', '/api/catalog/mappings')[0]?.body).toEqual({
      ref: expect.stringMatching(/^map-.+@1$/),
      connectorRef: 'conn-1@1',
      rules: [{ source: 'annual_income', target: 'annualIncome', transforms: [] }],
    })
    // the capabilities point at the same mapping ref the mapping is saved under
    expect(JSON.parse(draftBody.capabilitiesJson).FETCH.mapping_ref).toBe((m.find('POST', '/api/catalog/mappings')[0]?.body as { ref: string }).ref)
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))

    // test, then publish with the passing report
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Run test' }))
    expect(await screen.findByText(/Test passed/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))
    await userEvent.click(screen.getByRole('button', { name: 'Publish' }))
    expect(await screen.findByText(/Published\./)).toBeInTheDocument()
    expect(m.find('POST', '/api/catalog/connectors/conn-1%401/publish')[0]?.body).toEqual({ passed: true, failures: [] })
    expect(screen.getByRole('link', { name: 'See it in the catalog' })).toBeInTheDocument()
    expect(m.unhandled).toEqual([])
  })

  it('blocks publishing when the connector test fails, listing why', async () => {
    const m = mockFetch(routes({ test: { body: { passed: false, failures: ['mapping has no rule for annualIncome'] } } }))
    renderStaff({ route: '/staff/admin/onboarding', fetchImpl: m.fetchImpl, auth: admin() })
    await fillDepartment()
    await fillSource()
    await createConnector()
    await userEvent.click(await screen.findByRole('button', { name: 'Suggest matches' }))
    await userEvent.click(await screen.findByRole('checkbox', { name: /holder_name/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Save approved matches' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Continue' }))
    await userEvent.click(screen.getByRole('button', { name: 'Run test' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Test failed')
    expect(alert).toHaveTextContent('mapping has no rule for annualIncome')
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()
    expect(m.find('POST', /publish/)).toHaveLength(0)
  })

  it('keeps the step and shows the API reason when a write is refused', async () => {
    const m = mockFetch(routes({ source: { status: 400, body: { title: 'IllegalHostException', status: 400, detail: 'host not allowed by policy: fire.example.gov' } } }))
    renderStaff({ route: '/staff/admin/onboarding', fetchImpl: m.fetchImpl, auth: admin() })
    await fillDepartment()
    await fillSource()
    expect(await screen.findByRole('alert')).toHaveTextContent('host not allowed by policy')
    expect(screen.getByRole('heading', { name: 'Where does the data live?' })).toBeInTheDocument()
  })

  it('checks JSON and the SLA before calling the API', async () => {
    const m = mockFetch(routes())
    renderStaff({ route: '/staff/admin/onboarding', fetchImpl: m.fetchImpl, auth: admin() })
    await fillDepartment()
    await fillSource()
    const summary = await screen.findByText('Technical detail')
    await userEvent.click(summary)
    fireEvent.change(screen.getByLabelText(/Capabilities JSON/), { target: { value: '{nope' } })
    fireEvent.change(screen.getByLabelText(/^SLA \(ms\)/), { target: { value: '12x' } })
    await userEvent.click(screen.getByRole('button', { name: 'Create draft and continue' }))
    expect(screen.getByText('This is not valid JSON.')).toBeInTheDocument()
    expect(screen.getByText('Enter a whole number of milliseconds.')).toBeInTheDocument()
    expect(m.find('POST', '/api/catalog/connectors')).toHaveLength(0)
  })

  it('tells the admin when the importer finds nothing to suggest, and does not save', async () => {
    const m = mockFetch(routes({ import: { body: { sourceFields: [], targetFields: [], suggestions: [] } } }))
    renderStaff({ route: '/staff/admin/onboarding', fetchImpl: m.fetchImpl, auth: admin() })
    await fillDepartment()
    await fillSource()
    await createConnector()
    await userEvent.click(await screen.findByRole('button', { name: 'Suggest matches' }))
    expect(await screen.findByText(/No matches were suggested/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save approved matches' })).toBeDisabled()
  })

  it('refuses an OpenAPI document that is not JSON without calling the importer', async () => {
    const m = mockFetch(routes())
    renderStaff({ route: '/staff/admin/onboarding', fetchImpl: m.fetchImpl, auth: admin() })
    await fillDepartment()
    await fillSource()
    await createConnector()
    fireEvent.change(await screen.findByLabelText(/OpenAPI document/), { target: { value: 'openapi: 3.0.0' } })
    await userEvent.click(screen.getByRole('button', { name: 'Suggest matches' }))
    expect(screen.getByText('The OpenAPI document is not valid JSON.')).toBeInTheDocument()
    await waitFor(() => expect(m.find('POST', '/api/catalog/import/openapi')).toHaveLength(0))
  })
})

describe('admin: discover a department from its URL', () => {
  const manifest = {
    manifestVersion: 1,
    department: { code: 'SANDBOX', name: 'Sandbox Dept', description: null },
    documents: [
      {
        category: 'INCOME_CERTIFICATE',
        title: 'Income certificate',
        protocol: 'REST',
        method: 'GET',
        path: '/v1/income',
        inputs: [{ name: 'rationCard', in: 'query', required: true, description: null }],
        fields: [],
      },
    ],
    journeys: [
      {
        code: 'SANDBOX_SUBSIDY',
        name: 'Sandbox subsidy',
        description: 'A sample service.',
        referencePrefix: 'SBX',
        slaHours: 96,
        consentPurpose: 'SANDBOX_ELIGIBILITY',
        requester: 'SANDBOX',
        requiredCategories: [{ category: 'INCOME_CERTIFICATE', department: 'SANDBOX' }],
      },
    ],
  }

  it('onboards the documents it holds and the journeys it offers', async () => {
    const m = mockFetch([
      { method: 'POST', path: '/api/catalog/discover', reply: { body: manifest } },
      { method: 'POST', path: '/api/catalog/departments', reply: (c) => ({ body: { ...(c.body as object), status: 'ACTIVE' } }) },
      { method: 'POST', path: '/api/catalog/data-sources', reply: (c) => ({ body: c.body }) },
      { method: 'POST', path: '/api/catalog/connectors', reply: (c) => ({ body: { ...connector, category: (c.body as { category: unknown }).category } }) },
      { method: 'POST', path: '/api/catalog/journeys', reply: (c) => ({ body: c.body }) },
    ])
    renderStaff({ route: '/staff/admin/onboarding', fetchImpl: m.fetchImpl, auth: admin() })

    await userEvent.type(await screen.findByLabelText(/Department base URL/), 'https://sandbox.example.gov')
    await userEvent.click(screen.getByRole('button', { name: 'Discover' }))
    // the enriched journey renders with its provider department, SLA and purpose
    expect(await screen.findByText('Sandbox subsidy')).toBeInTheDocument()
    expect(screen.getByText(/Income certificate \(SANDBOX\)/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /Register Sandbox Dept and draft 1 connector/ }))
    await waitFor(() => expect(m.find('POST', '/api/catalog/journeys')).toHaveLength(1))
    expect(m.find('POST', '/api/catalog/journeys')[0]?.body).toEqual({
      code: 'SANDBOX_SUBSIDY',
      name: 'Sandbox subsidy',
      referencePrefix: 'SBX',
      slaHours: 96,
      consentPurpose: 'SANDBOX_ELIGIBILITY',
      requester: 'SANDBOX',
      requiredCategories: ['INCOME_CERTIFICATE'],
      sources: { INCOME_CERTIFICATE: 'SANDBOX' },
    })
    expect(await screen.findByText(/and 1 journey\./)).toBeInTheDocument()
    expect(m.unhandled).toEqual([])
  })
})

