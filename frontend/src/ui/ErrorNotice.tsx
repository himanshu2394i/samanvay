import { describeError } from './errors'

interface Props {
  error: unknown
  onRetry?: () => void
}

export function ErrorNotice({ error, onRetry }: Props) {
  return (
    <div className="notice bad" role="alert">
      <p>{describeError(error)}</p>
      {onRetry ? (
        <button type="button" className="btn" onClick={onRetry}>
          Try again
        </button>
      ) : null}
    </div>
  )
}
