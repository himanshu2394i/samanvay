import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { StatusStepper } from './StatusStepper'

describe('StatusStepper', () => {
  it('marks the approved terminal as the current (non-error) step', () => {
    render(<StatusStepper status="APPROVED" />)
    const li = screen.getByText('Approved').closest('li')!
    expect(li.className).toContain('now')
    expect(li.className).not.toContain('bad')
    expect(li).toHaveAttribute('aria-current', 'step')
  })

  it('renders a rejected application with a distinct error terminal, not the neutral current style', () => {
    render(<StatusStepper status="REJECTED" />)
    const li = screen.getByText('Rejected').closest('li')!
    // The rejection must read visually as a bad/terminal state, never the same blue as "Approved".
    expect(li.className).toContain('bad')
    expect(li.className).not.toContain('now')
    expect(li).toHaveAttribute('aria-current', 'step')
  })

  it('ends a closed application on its own neutral Closed terminal, never as Approved', () => {
    render(<StatusStepper status="CLOSED" />)
    expect(screen.queryByText('Approved')).not.toBeInTheDocument()
    const li = screen.getByText('Closed').closest('li')!
    expect(li.className).toContain('closed')
    expect(li.className).not.toContain('bad')
    expect(li.className).not.toContain('now')
    expect(li).toHaveAttribute('aria-current', 'step')
  })

  it('ends a failed application on a Failed error terminal, not on "In progress"', () => {
    render(<StatusStepper status="FAILED" />)
    expect(screen.queryByText('In progress')).not.toBeInTheDocument()
    const li = screen.getByText('Failed').closest('li')!
    expect(li.className).toContain('bad')
    expect(li).toHaveAttribute('aria-current', 'step')
    // the stage before it is done
    expect(screen.getByText('Submitted').closest('li')!.className).toContain('done')
  })
})
