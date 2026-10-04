import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { overview } from '../../test/staffFixtures'
import { mockFetch, renderStaff, staffAuth } from '../../test/utils'

const ROUTE = '/staff/admin/departments'
const PATH = '/api/ops/overview'
const admin = () => staffAuth(['admin'])

const card = (name: string) => screen.getByRole('heading', { name }).closest('li') as HTMLElement

describe('staff: onboarded departments', () => {
  it('shows one card per onboarded department with its key, login address, sources, documents and journeys', async () => {
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: overview } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })

    await screen.findByRole('heading', { name: 'State Board of Education' })
    const edu = within(card('State Board of Education'))
    expect(edu.getByText('EDUCATION')).toBeInTheDocument()
    expect(edu.getByText('JHRVn3yLkey')).toBeInTheDocument()
    expect(edu.getByText('https://education.example/login')).toBeInTheDocument()
    const sources = edu.getByRole('table', { name: 'Data sources of EDUCATION' })
    expect(within(sources).getByText('education-soap')).toBeInTheDocument()
    expect(within(sources).getByText('Green')).toBeInTheDocument()
    const docs = edu.getByRole('table', { name: 'Documents of EDUCATION' })
    expect(within(docs).getByText('edu-marks@2')).toBeInTheDocument()
    expect(within(docs).getByText('Published')).toBeInTheDocument()
    expect(within(docs).getByText('Yes')).toBeInTheDocument()
    expect(within(docs).getByText(/Worked/)).toBeInTheDocument()
    const link = edu.getByRole('link', { name: 'Status and log' })
    expect(link).toHaveAttribute('href', '/staff/admin/journeys/EDUCATION_SCHOLARSHIP')

    // the second department: no pinned key, no login address, a draft connector, a source that is down
    const rev = within(card('Revenue Department'))
    expect(rev.getByText(/No key pinned/)).toBeInTheDocument()
    expect(rev.getByText(/No login address/)).toBeInTheDocument()
    const revDocs = rev.getByRole('table', { name: 'Documents of REVENUE' })
    expect(within(revDocs).getByText('Draft')).toBeInTheDocument()
    expect(within(revDocs).getByText('No')).toBeInTheDocument()
    expect(within(revDocs).getByText(/still a draft/)).toBeInTheDocument()
    expect(within(revDocs).getByText('Not run yet')).toBeInTheDocument()
    expect(rev.getByText(/connection refused/)).toBeInTheDocument()
    expect(rev.getAllByRole('link', { name: 'Status and log' })).toHaveLength(2)
    expect(m.unhandled).toEqual([])
  })

  it('shows "Not checked yet", not "Yes", for a document whose source was never probed', async () => {
    const unchecked = {
      ...overview,
      departments: overview.departments.map((d) => ({
        ...d,
        dataSources: d.dataSources.map((s) => ({ ...s, health: 'UNKNOWN', healthDetail: null })),
        documents: d.documents.map((x) => ({ ...x, sourceHealth: 'UNKNOWN', working: true, connectorStatus: 'PUBLISHED' })),
      })),
    }
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: unchecked } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })
    await screen.findByRole('heading', { name: 'State Board of Education' })
    const docs = within(card('State Board of Education')).getByRole('table', { name: 'Documents of EDUCATION' })
    expect(within(docs).getByText('Not checked yet')).toBeInTheDocument()
    expect(within(docs).queryByText('Yes')).not.toBeInTheDocument()
    expect(within(docs).queryByText('No')).not.toBeInTheDocument()
    const sources = within(card('State Board of Education')).getByRole('table', { name: 'Data sources of EDUCATION' })
    expect(within(sources).getByText('Not checked yet')).toBeInTheDocument()
  })

  it('shows the document title once when the humanized category is the same text', async () => {
    const same = {
      ...overview,
      departments: overview.departments.map((d) => ({
        ...d,
        documents: d.documents.map((x) => (x.category === 'MARKS' ? { ...x, title: 'Marks' } : x)),
      })),
    }
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: same } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })
    await screen.findByRole('heading', { name: 'State Board of Education' })
    const docs = within(card('State Board of Education')).getByRole('table', { name: 'Documents of EDUCATION' })
    expect(within(docs).getAllByText('Marks')).toHaveLength(1)
  })

  it('lets an admin check a source and run a trial, then reloads', async () => {
    let trialDone = false
    const m = mockFetch([
      {
        method: 'GET',
        path: PATH,
        reply: () => ({
          body: {
            ...overview,
            departments: overview.departments.map((d) =>
              d.code === 'EDUCATION' && trialDone
                ? { ...d, documents: d.documents.map((x) => ({ ...x, lastTrial: { at: '2026-10-04T11:00:00Z', outcome: 'NOT_FOUND' } })) }
                : d,
            ),
          },
        }),
      },
      {
        method: 'POST',
        path: '/api/catalog/data-sources/education-soap/probe',
        reply: { body: { code: 'education-soap', departmentCode: 'EDUCATION', protocol: 'SOAP', baseHost: 'education.example', healthStatus: 'GREEN', detail: null } },
      },
      {
        method: 'POST',
        path: '/api/connector/trial/edu-marks%402',
        reply: () => {
          trialDone = true
          return { body: { ok: true, outcome: 'SUCCESS', personId: 'EDU-1001', fields: { percentage: 91 }, detail: null } }
        },
      },
    ])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })
    await screen.findByRole('heading', { name: 'State Board of Education' })
    const edu = within(card('State Board of Education'))

    await userEvent.click(edu.getByRole('button', { name: 'Check source' }))
    expect(await screen.findByText(/Source education-soap is Green/)).toBeInTheDocument()
    expect(m.find('POST', '/api/catalog/data-sources/education-soap/probe')).toHaveLength(1)

    await userEvent.click(edu.getByRole('button', { name: 'Run trial' }))
    expect(await screen.findByText(/Trial for Marks: worked/)).toBeInTheDocument()
    expect(m.find('POST', '/api/connector/trial/edu-marks%402')).toHaveLength(1)
    expect(await within(card('State Board of Education')).findByText(/Did not work \(Not found\)/)).toBeInTheDocument()
    expect(m.unhandled).toEqual([])
  })

  it('says plainly when a trial did not work', async () => {
    const m = mockFetch([
      { method: 'GET', path: PATH, reply: { body: overview } },
      { method: 'POST', path: '/api/connector/trial/rev-income%401', reply: { body: { ok: false, outcome: 'UNAVAILABLE', personId: 'RV-1', fields: null, detail: 'TIMEOUT' } } },
    ])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })
    await screen.findByRole('heading', { name: 'Revenue Department' })
    await userEvent.click(within(card('Revenue Department')).getByRole('button', { name: 'Run trial' }))
    expect(await screen.findByText(/Trial for Income certificate: did not work \(Unavailable\)/)).toBeInTheDocument()
  })

  it('shows an officer the same cards without the admin-only buttons', async () => {
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: overview } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: staffAuth(['officer']) })
    await screen.findByRole('heading', { name: 'State Board of Education' })
    expect(screen.queryByRole('button', { name: 'Check source' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Run trial' })).not.toBeInTheDocument()
  })

  it('lets an admin test a draft connector and publish it when the test passes', async () => {
    let published = false
    const m = mockFetch([
      {
        method: 'GET',
        path: PATH,
        reply: () => ({
          body: {
            ...overview,
            departments: overview.departments.map((d) =>
              d.code === 'REVENUE' && published ? { ...d, documents: d.documents.map((x) => ({ ...x, connectorStatus: 'PUBLISHED' })) } : d,
            ),
          },
        }),
      },
      { method: 'POST', path: '/api/catalog/connectors/rev-income%401/test', reply: { body: { passed: true, failures: [] } } },
      {
        method: 'POST',
        path: '/api/catalog/connectors/rev-income%401/publish',
        reply: () => {
          published = true
          return { body: { ref: 'rev-income@1', status: 'PUBLISHED' } }
        },
      },
    ])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })
    await screen.findByRole('heading', { name: 'Revenue Department' })
    await userEvent.click(within(card('Revenue Department')).getByRole('button', { name: 'Test and publish' }))
    expect(await screen.findByText(/rev-income@1 passed its test and is published/)).toBeInTheDocument()
    expect(m.find('POST', '/api/catalog/connectors/rev-income%401/publish')[0]?.body).toEqual({ passed: true, failures: [] })
    const docs = await within(card('Revenue Department')).findByRole('table', { name: 'Documents of REVENUE' })
    expect(await within(docs).findByText('Published')).toBeInTheDocument()
    expect(within(docs).queryByText('Draft')).not.toBeInTheDocument()
    expect(m.unhandled).toEqual([])
  })

  it('does not publish a draft connector whose test failed, and says why', async () => {
    const m = mockFetch([
      { method: 'GET', path: PATH, reply: { body: overview } },
      { method: 'POST', path: '/api/catalog/connectors/rev-income%401/test', reply: { body: { passed: false, failures: ['required field annualIncome is not mapped'] } } },
    ])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })
    await screen.findByRole('heading', { name: 'Revenue Department' })
    await userEvent.click(within(card('Revenue Department')).getByRole('button', { name: 'Test and publish' }))
    expect(await screen.findByText(/did not pass its test, so it was not published/)).toBeInTheDocument()
    expect(screen.getByText(/required field annualIncome is not mapped/)).toBeInTheDocument()
    expect(m.find('POST', '/api/catalog/connectors/rev-income%401/publish')).toHaveLength(0)
  })

  it('offers an officer no publish button', async () => {
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: overview } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: staffAuth(['officer']) })
    await screen.findByRole('heading', { name: 'Revenue Department' })
    expect(screen.queryByRole('button', { name: 'Test and publish' })).not.toBeInTheDocument()
  })

  it('says so when no department has been onboarded', async () => {
    const m = mockFetch([{ method: 'GET', path: PATH, reply: { body: { generatedAt: '2026-10-04T10:00:00Z', departments: [] } } }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })
    expect(await screen.findByText('No department has been onboarded yet')).toBeInTheDocument()
    expect(within(screen.getByRole('main')).getAllByRole('link', { name: 'Onboarding' })[0]).toHaveAttribute('href', '/staff/admin/onboarding')
  })

  it('surfaces a failure and can retry', async () => {
    let fail = true
    const m = mockFetch([{ method: 'GET', path: PATH, reply: () => (fail ? { status: 500, body: { status: 500 } } : { body: overview }) }])
    renderStaff({ route: ROUTE, fetchImpl: m.fetchImpl, auth: admin() })
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    fail = false
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('heading', { name: 'State Board of Education' })).toBeInTheDocument()
  })
})
