import { describe, expect, it, vi } from 'vitest'
import { clearSigninRealm, detectRealm, realmFromHash, rememberSigninRealm } from './realm'

describe('realm selection', () => {
  it('routes #/staff and below to the staff realm, everything else to citizen', () => {
    for (const h of ['#/staff', '#/staff/', '#/staff/officer/exceptions', '#/staff?x=1']) expect(realmFromHash(h)).toBe('staff')
    for (const h of ['', '#', '#/', '#/services', '#/staffing', '#/applications/STAFF-1']) expect(realmFromHash(h)).toBe('citizen')
  })

  it('uses the route on a normal page load', () => {
    expect(detectRealm({ hash: '#/staff' }, false)).toBe('staff')
    expect(detectRealm({ hash: '#/services' }, false)).toBe('citizen')
  })

  it('uses the remembered realm when the IdP sends the browser back (no route in the URL)', () => {
    rememberSigninRealm('staff')
    expect(detectRealm({ hash: '' }, true)).toBe('staff')
    clearSigninRealm()
    expect(detectRealm({ hash: '' }, true)).toBe('citizen')
    rememberSigninRealm('citizen')
    expect(detectRealm({ hash: '' }, true)).toBe('citizen')
  })

  it('falls back to citizen (fails closed) when storage is unavailable', () => {
    const broken = {
      getItem: vi.fn(() => {
        throw new Error('blocked')
      }),
    }
    expect(detectRealm({ hash: '' }, true, broken)).toBe('citizen')
    expect(() =>
      rememberSigninRealm('staff', {
        setItem: () => {
          throw new Error('blocked')
        },
      }),
    ).not.toThrow()
  })
})
