import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { HashRouter } from 'react-router-dom'
import { PortalApp } from './PortalApp'
import './portal.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <HashRouter>
      <PortalApp />
    </HashRouter>
  </StrictMode>,
)
