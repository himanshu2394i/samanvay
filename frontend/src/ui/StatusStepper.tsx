/**
 * The application lifecycle as a compact stepper, shared by the citizen and officer views.
 * Driven by the application `status`: Submitted → In progress → Verified → Approved, or ending early on its own
 * terminal step: Rejected and Failed (error styling), Closed (neutral).
 * Reuses the `.steps` styling in index.css that the apply wizard already uses.
 */
const STAGES = ['Submitted', 'In progress', 'Verified', 'Approved']

/** Which stage the given status is at (index into STAGES). */
function stageIndex(status: string): number {
  switch (status) {
    case 'SUBMITTED':
      return 0
    case 'PARTIALLY_VERIFIED':
      return 1
    case 'VERIFIED':
      return 2
    case 'APPROVED':
      return 3
    default:
      return 1
  }
}

/** Terminal states that replace the stages from `at` on with their own last step. */
const TERMINAL: Record<string, { label: string; at: number; tone: 'bad' | 'closed' }> = {
  REJECTED: { label: 'Rejected', at: 3, tone: 'bad' },
  FAILED: { label: 'Failed', at: 1, tone: 'bad' },
  CLOSED: { label: 'Closed', at: 3, tone: 'closed' },
}

export function StatusStepper({ status }: { status: string }) {
  const terminal = TERMINAL[status]
  const current = terminal ? terminal.at : stageIndex(status)
  const stages = terminal ? [...STAGES.slice(0, terminal.at), terminal.label] : STAGES
  return (
    <ol className="steps" aria-label="Application progress">
      {stages.map((label, i) => (
        <li
          key={label}
          // A rejected or failed application's terminal step reads as an error (red), a closed one as
          // neutral; never the blue "current" style that an approved application ends on.
          className={i < current ? 'done' : i === current ? (terminal ? terminal.tone : 'now') : ''}
          aria-current={i === current ? 'step' : undefined}
        >
          <span className="n">{i + 1}</span> {label}
        </li>
      ))}
    </ol>
  )
}
