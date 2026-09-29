// The API has no "who am I" endpoint: a citizen token is bound to a citizen record by
// POST /api/identity/citizens, which returns the record id. We remember that id per token
// subject (a per-viewer convenience; losing it just means the profile form shows again and
// the same call returns the same record).

const key = (sub: string) => `samanvay.citizenId.${sub}`

export function readCitizenId(sub: string): string | null {
  try {
    return window.localStorage.getItem(key(sub))
  } catch {
    return null
  }
}

export function writeCitizenId(sub: string, id: string | null): void {
  try {
    if (id) window.localStorage.setItem(key(sub), id)
    else window.localStorage.removeItem(key(sub))
  } catch {
    /* storage blocked: the id is simply not remembered */
  }
}
