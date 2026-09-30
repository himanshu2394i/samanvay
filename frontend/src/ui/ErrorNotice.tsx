import { useT } from '../i18n'
import { describeError } from './errors'

interface Props {
  error: unknown
  onRetry?: () => void
}

export function ErrorNotice({ error, onRetry }: Props) {
  const t = useT()
  return (
    <div className="notice bad" role="alert">
      <p>{describeError(error, t)}</p>
      {onRetry ? (
        <button type="button" className="btn" onClick={onRetry}>
          {t('errors.tryAgain')}
        </button>
      ) : null}
    </div>
  )
}
