import { beforeEach, describe, expect, it } from 'vitest'
import { callbackUrl, isHttpUrl, readCallback, safeReturnPath, savePending, takePending } from './deptLogin'

describe('department login helpers', () => {
  beforeEach(() => sessionStorage.clear())

  it('sends the department back to this app\'s own callback route, whatever folder the app is served from', () => {
    expect(callbackUrl({ origin: 'https://app.example.gov', pathname: '/' })).toBe('https://app.example.gov/#/dept-callback')
    expect(callbackUrl({ origin: 'https://app.example.gov', pathname: '/app/' })).toBe('https://app.example.gov/app/#/dept-callback')
  })

  it('remembers who started a login, and hands it over exactly once', () => {
    savePending({ citizenId: 'c1', departmentCode: 'REVENUE', returnPath: '/services/X/apply' })
    expect(takePending()).toEqual({ citizenId: 'c1', departmentCode: 'REVENUE', returnPath: '/services/X/apply' })
    expect(takePending()).toBeNull()
  })

  it('ignores anything in storage that is not a complete pending login', () => {
    sessionStorage.setItem('samanvay.deptLogin', '{"citizenId":"c1"}')
    expect(takePending()).toBeNull()
    sessionStorage.setItem('samanvay.deptLogin', 'not json')
    expect(takePending()).toBeNull()
  })

  it('only ever returns the citizen to a path on this site', () => {
    expect(safeReturnPath('/services/X/apply')).toBe('/services/X/apply')
    expect(safeReturnPath('//evil.example/x')).toBe('/')
    expect(safeReturnPath('https://evil.example/')).toBe('/')
    expect(safeReturnPath('javascript:alert(1)')).toBe('/')
    expect(safeReturnPath(undefined)).toBe('/')
  })

  it('only follows an http or https login address', () => {
    expect(isHttpUrl('https://revenue.example.gov/login?x=1')).toBe(true)
    expect(isHttpUrl('http://localhost:8091/login')).toBe(true)
    expect(isHttpUrl('javascript:alert(1)')).toBe(false)
    expect(isHttpUrl('data:text/html,hi')).toBe(false)
    expect(isHttpUrl('not a url')).toBe(false)
  })

  it('reads what the department sent back: the signed assertion, or a refusal', () => {
    expect(readCallback('?assertion=abc.def.ghi&state=s1')).toEqual({ assertion: 'abc.def.ghi' })
    expect(readCallback('?error=access_denied')).toEqual({ error: 'The department login did not complete. Please try again.' })
    expect(readCallback('?state=s1')).toEqual({ error: 'The department did not send back a login result. Please try again.' })
    expect(readCallback('')).toEqual({ error: 'The department did not send back a login result. Please try again.' })
  })
})
