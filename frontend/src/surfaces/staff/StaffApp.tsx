import { Navigate, Route, Routes } from 'react-router-dom'
import { Loading } from '../../ui/Loading'
import { RequireRole, RequireStaff } from './guards'
import { ADMIN, OFFICER, OPS, REVIEWER } from './nav'
import { StaffLayout } from './StaffLayout'
import { StaffHomePage } from './pages/StaffHomePage'
import { ExceptionsPage } from './pages/ExceptionsPage'
import { BankReviewsPage } from './pages/BankReviewsPage'
import { ApplicationsReviewPage } from './pages/ApplicationsReviewPage'
import { ApplicationReviewPage } from './pages/ApplicationReviewPage'
import { MetricsPage } from './pages/MetricsPage'
import { AuditPage } from './pages/AuditPage'
import { CatalogPage } from './pages/CatalogPage'
import { OnboardingPage } from './pages/OnboardingPage'
import { ReviewerQueuePage } from './pages/ReviewerQueuePage'
import { StaffNotFoundPage } from './pages/StaffNotFoundPage'

/**
 * The staff surfaces (officer, admin, reviewer), all under #/staff and all on the STAFF
 * Keycloak realm. Access is by the roles in the token:
 *
 *   OFFICER          exceptions, bank reviews, applications
 *   OFFICER, ADMIN   metrics, audit ledger
 *   ADMIN            catalog, onboarding
 *   REVIEWER         identity review
 *
 * These mirror SecurityConfig's route rules. The citizen surface (CitizenApp) shares
 * nothing with this folder except src/auth, src/api and src/ui.
 */
export function StaffApp() {
  return (
    <Routes>
      {/* The IdP redirect can land on the bare page with no hash. */}
      <Route path="/" element={<Navigate to="/staff" replace />} />
      <Route path="/staff" element={<StaffLayout />}>
        <Route element={<RequireStaff />}>
          <Route index element={<StaffHomePage />} />
          <Route element={<RequireRole allow={OFFICER} />}>
            <Route path="officer/exceptions" element={<ExceptionsPage />} />
            <Route path="officer/bank-reviews" element={<BankReviewsPage />} />
            <Route path="officer/applications" element={<ApplicationsReviewPage />} />
            <Route path="officer/applications/:ref" element={<ApplicationReviewPage />} />
          </Route>
          <Route element={<RequireRole allow={OPS} />}>
            <Route path="ops/metrics" element={<MetricsPage />} />
            <Route path="ops/audit" element={<AuditPage />} />
          </Route>
          <Route element={<RequireRole allow={ADMIN} />}>
            <Route path="admin/catalog" element={<CatalogPage />} />
            <Route path="admin/onboarding" element={<OnboardingPage />} />
          </Route>
          <Route element={<RequireRole allow={REVIEWER} />}>
            <Route path="reviewer/queue" element={<ReviewerQueuePage />} />
          </Route>
          <Route path="*" element={<StaffNotFoundPage />} />
        </Route>
      </Route>
      {/* A hash outside /staff means the user is moving to the citizen area: main.tsx reloads. */}
      <Route path="*" element={<Loading label="Switching" />} />
    </Routes>
  )
}
