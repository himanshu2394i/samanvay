import { Route, Routes } from 'react-router-dom'
import { LanguageProvider } from '../../i18n'
import { CitizenLayout } from './CitizenLayout'
import { ProtectedArea, RequireProfile } from './guards'
import { LandingPage } from './pages/LandingPage'
import { ServicesPage } from './pages/ServicesPage'
import { ServicePage } from './pages/ServicePage'
import { ProfilePage } from './pages/ProfilePage'
import { ApplyPage } from './pages/ApplyPage'
import { DeptCallbackPage } from './pages/DeptCallbackPage'
import { ApplicationsPage } from './pages/ApplicationsPage'
import { ApplicationPage } from './pages/ApplicationPage'
import { ConsentsPage } from './pages/ConsentsPage'
import { NotFoundPage } from './pages/NotFoundPage'

/**
 * The citizen surface. Officer and admin surfaces will live beside this folder
 * (src/surfaces/officer, src/surfaces/admin) with their own layout, guards and realm
 * (staff), reusing src/auth, src/api and src/ui.
 */
export function CitizenApp() {
  return (
    <LanguageProvider>
      <Routes>
        <Route element={<CitizenLayout />}>
          <Route index element={<LandingPage />} />
          <Route element={<ProtectedArea />}>
            <Route path="services" element={<ServicesPage />} />
            <Route path="services/:code" element={<ServicePage />} />
            <Route path="profile" element={<ProfilePage />} />
            <Route path="applications/:ref" element={<ApplicationPage />} />
            <Route path="dept-callback" element={<DeptCallbackPage />} />
            <Route element={<RequireProfile />}>
              <Route path="services/:code/apply" element={<ApplyPage />} />
              <Route path="applications" element={<ApplicationsPage />} />
              <Route path="consents" element={<ConsentsPage />} />
            </Route>
          </Route>
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </LanguageProvider>
  )
}
