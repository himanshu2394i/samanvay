import { describe, expect, it } from 'vitest'
import { fakeJwt } from '../test/jwt'
import { decodeJwtPayload, departmentFromToken, hasAnyRole, rolesFromToken, staffRolesOf } from './roles'

describe('token roles', () => {
  it('reads realm roles from the access token, upper-cased', () => {
    const t = fakeJwt({ realm_access: { roles: ['officer', 'default-roles-samanvay-staff'] } })
    expect(rolesFromToken(t)).toEqual(['OFFICER', 'DEFAULT-ROLES-SAMANVAY-STAFF'])
    expect(staffRolesOf(rolesFromToken(t))).toEqual(['OFFICER'])
  })

  it('reads the department claim, and ignores a blank or non-string one', () => {
    expect(departmentFromToken(fakeJwt({ department: ' SCHOLARSHIP ' }))).toBe('SCHOLARSHIP')
    expect(departmentFromToken(fakeJwt({ department: '  ' }))).toBeNull()
    expect(departmentFromToken(fakeJwt({ department: 7 }))).toBeNull()
    expect(departmentFromToken(fakeJwt({}))).toBeNull()
  })

  it('decodes a UTF-8 payload', () => {
    expect((decodeJwtPayload(fakeJwt({ department: 'महसूल' })) as { department: string }).department).toBe('महसूल')
  })

  it('yields no roles for anything that is not a well-formed token (fails closed)', () => {
    for (const bad of ['', 'not-a-jwt', 'a.b', 'a.%%%.c', fakeJwt({ realm_access: { roles: 'officer' } }), fakeJwt({ realm_access: null })]) {
      expect(rolesFromToken(bad)).toEqual([])
    }
  })

  it('does not treat a citizen or a department role as staff', () => {
    expect(staffRolesOf(['CITIZEN', 'DEPARTMENT'])).toEqual([])
    expect(staffRolesOf(['ADMIN', 'REVIEWER'])).toEqual(['REVIEWER', 'ADMIN'])
  })

  it('hasAnyRole is an intersection test', () => {
    expect(hasAnyRole(['OFFICER'], ['OFFICER', 'ADMIN'])).toBe(true)
    expect(hasAnyRole(['REVIEWER'], ['OFFICER', 'ADMIN'])).toBe(false)
    expect(hasAnyRole([], ['ADMIN'])).toBe(false)
  })
})
