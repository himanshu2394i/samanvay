/**
 * The application lifecycle as a compact stepper, shared by the citizen and officer views.
 * Driven by the application `status`: Submitted → In progress → Verified → Approved (or Rejected).
 * Reuses the `.steps` styling in index.css that the apply wizard already uses.
 */
const STAGES = ['Submitted', 'In progress', 'Verified', 'Approved']

/** Which stage the given status is at (index into STAGES). */
function stageIndex(status: string): number {
  switch (status) {
    case 'SUBMITTED':
      return 0
    case 'PARTIALLY_VERIFIED':
    case 'FAILED':
      return 1
    case 'VERIFIED':
      return 2
    case 'APPROVED':
    case 'REJECTED':
    case 'CLOSED':
      return 3
    default:
      return 1
  }
}

export function StatusStepper({ status }: { status: string }) {
  const current = stageIndex(status)
  const stages = status === 'REJECTED' ? [...STAGES.slice(0, 3), 'Rejected'] : STAGES
  return (
    <ol className="steps" aria-label="Application progress">
      {stages.map((label, i) => (
        <li
          key={label}
          className={i < current ? 'done' : i === current ? 'now' : ''}
          aria-current={i === current ? 'step' : undefined}
        >
          <span className="n">{i + 1}</span> {label}
        </li>
      ))}
    </ol>
  )
}
