import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockFetch, renderStaff, staffAuth } from '../../test/utils'

const admin = () => staffAuth(['admin'])

const readyDoc = {
  category: 'BANK_ACCOUNT',
  title: 'Bank account for DBT',
  protocol: 'REST',
  dataSourceCode: 'dbt-rest',
  connectorId: 'dbt-bank',
  newVersionOfExisting: true,
  centralSchemaRef: 'Credential/BankAccount@1',
  suggestions: [
    { source: 'accountRef', target: 'accountRef', confidence: 1, rationale: 'lexical', approved: false },
    { source: 'holderName', target: 'holderName', confidence: 1, rationale: 'lexical', approved: false },
  ],
  unmappedRequired: [],
  problems: [],
  ready: true,
}

const blockedDoc = {
  category: 'WEATHER',
  title: 'Weather report',
  protocol: 'REST',
  dataSourceCode: 'dbt-rest',
  connectorId: 'dbt-weather',
  newVersionOfExisting: false,
  centralSchemaRef: null,
  suggestions: [],
  unmappedRequired: [],
  problems: ['No central schema is seeded for category WEATHER: seed it first, then onboard.'],
  ready: false,
}

const plan = {
  departmentCode: 'DBT',
  departmentName: 'Direct Benefit Transfer',
  manifestDigest: 'a'.repeat(64),
  departmentExists: false,
  changedSinceOnboarding: false,
  documents: [readyDoc, blockedDoc],
  journeys: [
    { code: 'DBT_ACCOUNT_SEEDING', name: 'DBT bank account seeding', exists: false, requiredCategories: ['BANK_ACCOUNT'] },
    { code: 'FARMER_SUBSIDY', name: 'Farmer subsidy', exists: true, requiredCategories: ['LAND_PARCEL'] },
  ],
  pendingSteps: [
    {
      kind: 'PROVISION_SECRET',
      subject: 'dbt-rest',
      detail: 'Provision the credential the department gave you into the SecretStore.',
      data: { secretKey: 'source-dbt-rest-credential', parameters: 'client_id, client_secret' },
    },
  ],
}

const result = {
  departmentCode: 'DBT',
  dataSources: ['dbt-rest'],
  connectorRefs: ['dbt-bank@2'],
  mappingRefs: ['map-dbt-bank@2'],
  journeysCreated: ['DBT_ACCOUNT_SEEDING'],
  skipped: ['journey FARMER_SUBSIDY already exists'],
  pendingSteps: plan.pendingSteps,
}

async function review(m: ReturnType<typeof mockFetch>) {
  renderStaff({ route: '/staff/admin/onboarding', fetchImpl: m.fetchImpl, auth: admin() })
  await userEvent.type(await screen.findByLabelText(/Onboard from a URL/), 'https://dbt.example.gov')
  await userEvent.click(screen.getByRole('button', { name: 'Review plan' }))
  return screen.findByRole('table', { name: 'Documents in the plan' })
}

describe('admin: review a plan, then onboard a department in one go', () => {
  it('asks the server for a plan and shows each document with its protocol, source, schema and proposed matches', async () => {
    const m = mockFetch([{ method: 'POST', path: '/api/catalog/onboard/plan', reply: { body: plan } }])
    const table = await review(m)

    expect(m.find('POST', '/api/catalog/onboard/plan')[0]?.body).toEqual({ baseUrl: 'https://dbt.example.gov' })
    expect(within(table).getByText('Bank account for DBT')).toBeInTheDocument()
    expect(within(table).getAllByText('dbt-rest')).toHaveLength(2)
    expect(within(table).getByText('Credential/BankAccount@1')).toBeInTheDocument()
    expect(within(table).getByText(/2 field matches proposed/)).toBeInTheDocument()
    expect(within(table).getByText('New version of an existing connector')).toBeInTheDocument()
    expect(screen.getByText('Direct Benefit Transfer')).toBeInTheDocument()
    expect(m.unhandled).toEqual([])
  })

  it('lets the admin tick a ready document but not one that is blocked, and says why it is blocked', async () => {
    const m = mockFetch([{ method: 'POST', path: '/api/catalog/onboard/plan', reply: { body: plan } }])
    const table = await review(m)

    expect(within(table).getByRole('checkbox', { name: /Onboard Bank account for DBT/ })).toBeEnabled()
    expect(within(table).getByRole('checkbox', { name: /Onboard Weather report/ })).toBeDisabled()
    expect(within(table).getByText(/seed it first/)).toBeInTheDocument()
  })

  it('shows the journeys it offers (flagging existing ones) and the steps an operator must do before it can work', async () => {
    const m = mockFetch([{ method: 'POST', path: '/api/catalog/onboard/plan', reply: { body: plan } }])
    await review(m)

    expect(screen.getByText('DBT bank account seeding')).toBeInTheDocument()
    expect(screen.getByText('Farmer subsidy')).toBeInTheDocument()
    expect(screen.getByText('Already in the catalog')).toBeInTheDocument()
    const steps = screen.getByRole('list', { name: 'Steps for an operator' })
    expect(within(steps).getByText(/source-dbt-rest-credential/)).toBeInTheDocument()
    expect(within(steps).getByText(/client_id, client_secret/)).toBeInTheDocument()
  })

  it('will not onboard until a document is ticked AND the proposed field matches are approved', async () => {
    const m = mockFetch([{ method: 'POST', path: '/api/catalog/onboard/plan', reply: { body: plan } }])
    const table = await review(m)

    const button = screen.getByRole('button', { name: /Onboard 0 documents/ })
    expect(button).toBeDisabled()
    await userEvent.click(within(table).getByRole('checkbox', { name: /Onboard Bank account for DBT/ }))
    expect(screen.getByRole('button', { name: /Onboard 1 document$/ })).toBeDisabled() // matches not yet approved
    await userEvent.click(screen.getByRole('checkbox', { name: /I have reviewed the proposed field matches/ }))
    expect(screen.getByRole('button', { name: /Onboard 1 document$/ })).toBeEnabled()
  })

  it('sends the reviewed digest, the ticked categories and the approval, then shows what was created and what to do next', async () => {
    const m = mockFetch([
      { method: 'POST', path: '/api/catalog/onboard/plan', reply: { body: plan } },
      { method: 'POST', path: '/api/catalog/onboard', reply: { body: result } },
    ])
    const table = await review(m)

    await userEvent.click(within(table).getByRole('checkbox', { name: /Onboard Bank account for DBT/ }))
    await userEvent.click(screen.getByRole('checkbox', { name: /I have reviewed the proposed field matches/ }))
    await userEvent.click(screen.getByRole('button', { name: /Onboard 1 document$/ }))

    await waitFor(() => expect(m.find('POST', '/api/catalog/onboard')).toHaveLength(1))
    expect(m.find('POST', '/api/catalog/onboard')[0]?.body).toEqual({
      baseUrl: 'https://dbt.example.gov',
      manifestDigest: 'a'.repeat(64),
      categories: ['BANK_ACCOUNT'],
      acceptSuggestedMappings: true,
      mappings: {},
    })
    const done = await screen.findByRole('status')
    expect(within(done).getByText(/Onboarded Direct Benefit Transfer/)).toBeInTheDocument()
    expect(within(done).getByText('dbt-bank@2')).toBeInTheDocument()
    expect(within(done).getByText(/journey FARMER_SUBSIDY already exists/)).toBeInTheDocument()
    expect(within(done).getByText(/Everything is a draft/)).toBeInTheDocument()
    expect(m.unhandled).toEqual([])
  })

  describe('the key that signed the manifest', () => {
    const KEY = 'Qm9ndXNUaHVtYnByaW50Rm9yVGVzdGluZ09ubHkwMTIzNDU'
    const signed = (extra: Record<string, unknown> = {}) => ({ ...plan, manifestKeyThumbprint: KEY, pinnedKeyThumbprint: null, ...extra })

    async function tickAndApproveMatches(table: HTMLElement) {
      await userEvent.click(within(table).getByRole('checkbox', { name: /Onboard Bank account for DBT/ }))
      await userEvent.click(screen.getByRole('checkbox', { name: /I have reviewed the proposed field matches/ }))
    }

    it('shows the key fingerprint, will not onboard until the admin confirms it with the department, then sends that exact key', async () => {
      const m = mockFetch([
        { method: 'POST', path: '/api/catalog/onboard/plan', reply: { body: signed() } },
        { method: 'POST', path: '/api/catalog/onboard', reply: { body: result } },
      ])
      const table = await review(m)
      expect(screen.getByText(KEY)).toBeInTheDocument()
      await tickAndApproveMatches(table)
      expect(screen.getByRole('button', { name: /Onboard 1 document$/ })).toBeDisabled() // key not yet confirmed

      await userEvent.click(screen.getByRole('checkbox', { name: /I confirmed this key fingerprint with the department/ }))
      await userEvent.click(screen.getByRole('button', { name: /Onboard 1 document$/ }))
      await waitFor(() => expect(m.find('POST', '/api/catalog/onboard')).toHaveLength(1))
      expect(m.find('POST', '/api/catalog/onboard')[0]?.body).toMatchObject({ approvedManifestKey: KEY })
    })

    it('needs no confirmation for the key that was approved before, and says it matches', async () => {
      const m = mockFetch([
        { method: 'POST', path: '/api/catalog/onboard/plan', reply: { body: signed({ pinnedKeyThumbprint: KEY }) } },
        { method: 'POST', path: '/api/catalog/onboard', reply: { body: result } },
      ])
      const table = await review(m)
      expect(screen.getByText(/matches the key you approved earlier/i)).toBeInTheDocument()
      expect(screen.queryByRole('checkbox', { name: /I confirmed this key fingerprint/ })).not.toBeInTheDocument()
      await tickAndApproveMatches(table)
      await userEvent.click(screen.getByRole('button', { name: /Onboard 1 document$/ }))
      await waitFor(() => expect(m.find('POST', '/api/catalog/onboard')).toHaveLength(1))
      expect(m.find('POST', '/api/catalog/onboard')[0]?.body).not.toHaveProperty('approvedManifestKey')
    })

    it('warns loudly when the department signs with a different key than the one approved, and needs a fresh confirmation', async () => {
      const m = mockFetch([{ method: 'POST', path: '/api/catalog/onboard/plan', reply: { body: signed({ pinnedKeyThumbprint: 'SomeOlderApprovedKey' }) } }])
      const table = await review(m)
      expect(screen.getByRole('alert')).toHaveTextContent(/signing key has changed/i)
      expect(screen.getByText('SomeOlderApprovedKey')).toBeInTheDocument()
      await tickAndApproveMatches(table)
      expect(screen.getByRole('button', { name: /Onboard 1 document$/ })).toBeDisabled()
      await userEvent.click(screen.getByRole('checkbox', { name: /I confirmed this key fingerprint with the department/ }))
      expect(screen.getByRole('button', { name: /Onboard 1 document$/ })).toBeEnabled()
    })

    it('needs an explicit acknowledgement when the manifest would replace an existing department identity, and sends it', async () => {
      const identityChange = { warning: 'The login address would change.', changes: ['login address: https://old.example to https://new.example'] }
      const m = mockFetch([
        { method: 'POST', path: '/api/catalog/onboard/plan', reply: { body: signed({ pinnedKeyThumbprint: KEY, identityChange }) } },
        { method: 'POST', path: '/api/catalog/onboard', reply: { body: result } },
      ])
      const table = await review(m)
      expect(screen.getByRole('alert')).toHaveTextContent(/changes an existing department/i)
      expect(screen.getByText(/login address: https:\/\/old\.example/)).toBeInTheDocument()
      await tickAndApproveMatches(table)
      expect(screen.getByRole('button', { name: /Onboard 1 document$/ })).toBeDisabled()
      await userEvent.click(screen.getByRole('checkbox', { name: /I understand and accept this change/ }))
      await userEvent.click(screen.getByRole('button', { name: /Onboard 1 document$/ }))
      await waitFor(() => expect(m.find('POST', '/api/catalog/onboard')).toHaveLength(1))
      expect(m.find('POST', '/api/catalog/onboard')[0]?.body).toMatchObject({ acknowledgeIdentityChange: true })
    })

    it('says plainly when the manifest is not signed', async () => {
      const m = mockFetch([{ method: 'POST', path: '/api/catalog/onboard/plan', reply: { body: plan } }])
      await review(m)
      expect(screen.getByText(/this manifest is not signed/i)).toBeInTheDocument()
    })
  })

  it('warns when a department already onboarded has changed what it publishes', async () => {
    const m = mockFetch([
      { method: 'POST', path: '/api/catalog/onboard/plan', reply: { body: { ...plan, departmentExists: true, changedSinceOnboarding: true } } },
    ])
    await review(m)
    expect(screen.getByText(/changed what it publishes since you last onboarded it/)).toBeInTheDocument()
  })

  it('says so when the department was onboarded from this very manifest and nothing changed', async () => {
    const m = mockFetch([
      { method: 'POST', path: '/api/catalog/onboard/plan', reply: { body: { ...plan, departmentExists: true, onboardedFromManifest: true, changedSinceOnboarding: false } } },
    ])
    await review(m)
    expect(screen.getByText(/already onboarded from this manifest/i)).toBeInTheDocument()
    expect(screen.queryByText(/changed what it publishes/)).not.toBeInTheDocument()
  })

  it('shows the server refusal and does not pretend it worked', async () => {
    const m = mockFetch([
      { method: 'POST', path: '/api/catalog/onboard/plan', reply: { body: plan } },
      { method: 'POST', path: '/api/catalog/onboard', reply: { status: 400, body: { title: 'Invalid request', detail: "The department's manifest has changed since you reviewed it." } } },
    ])
    const table = await review(m)
    await userEvent.click(within(table).getByRole('checkbox', { name: /Onboard Bank account for DBT/ }))
    await userEvent.click(screen.getByRole('checkbox', { name: /I have reviewed the proposed field matches/ }))
    await userEvent.click(screen.getByRole('button', { name: /Onboard 1 document$/ }))

    expect(await screen.findByText(/manifest has changed since you reviewed it/)).toBeInTheDocument()
    expect(screen.queryByText(/Onboarded Direct Benefit Transfer/)).not.toBeInTheDocument()
  })

  it('shows the error when the department cannot be read', async () => {
    const m = mockFetch([
      { method: 'POST', path: '/api/catalog/onboard/plan', reply: { status: 400, body: { title: 'Invalid request', detail: 'No Samanvay manifest at https://x (HTTP 404)' } } },
    ])
    renderStaff({ route: '/staff/admin/onboarding', fetchImpl: m.fetchImpl, auth: admin() })
    await userEvent.type(await screen.findByLabelText(/Onboard from a URL/), 'https://x')
    await userEvent.click(screen.getByRole('button', { name: 'Review plan' }))
    expect(await screen.findByText(/No Samanvay manifest/)).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'Documents in the plan' })).not.toBeInTheDocument()
  })

  it('lets the admin run a trial fetch for each connector draft and shows the real fields that came back', async () => {
    const m = mockFetch([
      { method: 'POST', path: '/api/catalog/onboard/plan', reply: { body: plan } },
      { method: 'POST', path: '/api/catalog/onboard', reply: { body: result } },
      {
        method: 'POST',
        path: '/api/connector/trial/dbt-bank%402',
        reply: { body: { ok: true, outcome: 'SUCCESS', personId: 'DBT-1001', fields: { accountRef: 'XXXXXX1234', holderName: 'Asha Patil' }, detail: null } },
      },
    ])
    const table = await review(m)
    await userEvent.click(within(table).getByRole('checkbox', { name: /Onboard Bank account for DBT/ }))
    await userEvent.click(screen.getByRole('checkbox', { name: /I have reviewed the proposed field matches/ }))
    await userEvent.click(screen.getByRole('button', { name: /Onboard 1 document$/ }))
    const done = await screen.findByRole('status')

    await userEvent.click(within(done).getByRole('button', { name: 'Run trial fetch for dbt-bank@2' }))

    await waitFor(() => expect(m.find('POST', '/api/connector/trial/dbt-bank%402')).toHaveLength(1))
    expect(m.find('POST', '/api/connector/trial/dbt-bank%402')[0]?.body).toEqual({})
    const trial = await within(done).findByRole('region', { name: 'Trial result for dbt-bank@2' })
    expect(within(trial).getByText(/Worked/)).toBeInTheDocument()
    expect(within(trial).getByText('DBT-1001')).toBeInTheDocument()
    expect(within(trial).getByText('XXXXXX1234')).toBeInTheDocument()
    expect(within(trial).getByText('holderName')).toBeInTheDocument()
    expect(m.unhandled).toEqual([])
  })

  it('shows why a trial failed (not found, refused, misconfigured) instead of pretending it worked', async () => {
    const m = mockFetch([
      { method: 'POST', path: '/api/catalog/onboard/plan', reply: { body: plan } },
      { method: 'POST', path: '/api/catalog/onboard', reply: { body: result } },
      {
        method: 'POST',
        path: '/api/connector/trial/dbt-bank%402',
        reply: { body: { ok: false, outcome: 'ERROR', personId: 'DBT-1001', fields: null, detail: "source 'dbt-rest' is missing credential parameter 'client_secret'" } },
      },
    ])
    const table = await review(m)
    await userEvent.click(within(table).getByRole('checkbox', { name: /Onboard Bank account for DBT/ }))
    await userEvent.click(screen.getByRole('checkbox', { name: /I have reviewed the proposed field matches/ }))
    await userEvent.click(screen.getByRole('button', { name: /Onboard 1 document$/ }))
    const done = await screen.findByRole('status')
    await userEvent.click(within(done).getByRole('button', { name: 'Run trial fetch for dbt-bank@2' }))

    const trial = await within(done).findByRole('region', { name: 'Trial result for dbt-bank@2' })
    expect(within(trial).getByText(/Did not work/)).toBeInTheDocument()
    expect(within(trial).getByText(/missing credential parameter 'client_secret'/)).toBeInTheDocument()
    expect(within(trial).queryByText('accountRef')).not.toBeInTheDocument()
  })

  it('shows the server error when the trial request itself is refused', async () => {
    const m = mockFetch([
      { method: 'POST', path: '/api/catalog/onboard/plan', reply: { body: plan } },
      { method: 'POST', path: '/api/catalog/onboard', reply: { body: result } },
      { method: 'POST', path: '/api/connector/trial/dbt-bank%402', reply: { status: 400, body: { title: 'Invalid request', detail: 'This connector has no sample person: name one in personId' } } },
    ])
    const table = await review(m)
    await userEvent.click(within(table).getByRole('checkbox', { name: /Onboard Bank account for DBT/ }))
    await userEvent.click(screen.getByRole('checkbox', { name: /I have reviewed the proposed field matches/ }))
    await userEvent.click(screen.getByRole('button', { name: /Onboard 1 document$/ }))
    const done = await screen.findByRole('status')
    await userEvent.click(within(done).getByRole('button', { name: 'Run trial fetch for dbt-bank@2' }))
    expect(await within(done).findByText(/has no sample person/)).toBeInTheDocument()
  })
})
