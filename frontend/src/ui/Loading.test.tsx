import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Loading } from './Loading'

describe('Loading', () => {
  it('renders a spinner by default with the accessible label', () => {
    render(<Loading label="Loading applications" />)
    const status = screen.getByRole('status')
    expect(status).toHaveClass('loading')
    expect(status).toHaveTextContent('Loading applications…')
  })

  it('renders shimmer placeholder rows for variant="table" while staying accessible', () => {
    const { container } = render(<Loading variant="table" label="Loading applications" rows={3} />)
    const status = screen.getByRole('status')
    expect(status).toHaveClass('skeleton-table')
    expect(status).toHaveAttribute('aria-busy', 'true')
    expect(status).toHaveAccessibleName('Loading applications…')
    expect(container.querySelectorAll('.skeleton-row')).toHaveLength(3)
  })
})
