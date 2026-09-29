/** An unsigned JWT-shaped string for tests: header.payload.signature with a real base64url payload. */
export function fakeJwt(claims: Record<string, unknown>): string {
  const b64 = (o: unknown) =>
    btoa(unescape(encodeURIComponent(JSON.stringify(o)))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `${b64({ alg: 'none', typ: 'JWT' })}.${b64(claims)}.sig`
}
