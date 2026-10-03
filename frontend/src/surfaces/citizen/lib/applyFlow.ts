import type { CitizenApi } from '../../../api/citizenApi'
import type {
  ApplicationSummary,
  DepartmentLinkNeed,
  JourneyInstance,
  LinkProofKind,
  LinkProofProviderInfo,
  LinkRequest,
  Uuid,
} from '../../../api/types'

/** Proof kinds this app can complete on its own. DEPT_IDP and DEPT_ASSERTION need a department sign-in the static portals handle. */
export const SUPPORTED_PROOFS: readonly LinkProofKind[] = ['LOCAL_ID_OTP']

export function supportedProviders(providers: LinkProofProviderInfo[]): LinkProofProviderInfo[] {
  return providers.filter((p) => SUPPORTED_PROOFS.includes(p.kind))
}

export function unsupportedProviders(providers: LinkProofProviderInfo[]): LinkProofProviderInfo[] {
  return providers.filter((p) => !SUPPORTED_PROOFS.includes(p.kind))
}

export function allLinked(needs: DepartmentLinkNeed[]): boolean {
  return needs.length > 0 && needs.every((d) => d.linked)
}

export interface LinkForm {
  citizenId: Uuid
  departmentCode: string
  provider: LinkProofKind
  localIdType: string
  localId: string
  /** The one-time code; only used by LOCAL_ID_OTP. */
  otp: string
}

/**
 * Body for POST /api/identity/links. LOCAL_ID_OTP (a labelled demo) sends the entered one-time code.
 */
export function buildLinkRequest(f: LinkForm): LinkRequest {
  return {
    citizenId: f.citizenId,
    departmentCode: f.departmentCode,
    localIdType: f.localIdType.trim(),
    localId: f.localId.trim(),
    provider: f.provider,
    proof: f.otp.trim(),
  }
}

export interface PollOptions {
  attempts?: number
  intervalMs?: number
  /** Injectable so tests need no real timers. */
  sleep?: (ms: number) => Promise<void>
}

const realSleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

/**
 * Starting a journey returns only the instance id; the application row (and its
 * reference number) is written asynchronously. Poll the citizen's list until the one for
 * this instance appears. Resolves null if it does not show up in time (the application is
 * still submitted; it will be in "My applications").
 */
export async function pollForApplication(
  api: Pick<CitizenApi, 'listApplications'>,
  citizenId: Uuid,
  instance: Pick<JourneyInstance, 'id'>,
  { attempts = 20, intervalMs = 500, sleep = realSleep }: PollOptions = {},
): Promise<ApplicationSummary | null> {
  for (let i = 0; i < attempts; i++) {
    const apps = await api.listApplications(citizenId, 20)
    const found = apps.find((a) => a.instanceId === instance.id)
    if (found) return found
    if (i < attempts - 1) await sleep(intervalMs)
  }
  return null
}
