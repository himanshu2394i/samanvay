package com.samanvay.tracking.internal.web;

import com.samanvay.shared.security.Callers;
import com.samanvay.shared.security.CitizenAccess;
import com.samanvay.shared.security.DepartmentScope;
import com.samanvay.shared.InvalidRequestException;
import org.springframework.security.access.AccessDeniedException;
import com.samanvay.tracking.api.ApplicationSummary;
import com.samanvay.tracking.api.ApplicationTracking;
import com.samanvay.tracking.api.ApplicationView;
import com.samanvay.tracking.api.StepView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/applications")
class TrackingController {

    private final ApplicationTracking tracking;
    private final CitizenAccess citizenAccess;
    private final DepartmentScope departmentScope;

    TrackingController(ApplicationTracking tracking, CitizenAccess citizenAccess, DepartmentScope departmentScope) {
        this.tracking = tracking;
        this.citizenAccess = citizenAccess;
        this.departmentScope = departmentScope;
    }

    @GetMapping
    List<ApplicationSummary> list(
            @RequestParam(required = false) UUID citizenId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (citizenId == null && Callers.require().isCitizen()) {
            throw new AccessDeniedException("citizens may only list their own applications (citizenId required)");
        }
        if (citizenId == null && departmentScope.isDepartmentCaller()) {
            throw new InvalidRequestException("citizenId is required");
        }
        citizenAccess.requireMayActOn(citizenId);
        var p = PageRequest.of(page, size);
        List<ApplicationSummary> found =
                citizenId == null ? tracking.recent(p).getContent() : tracking.forCitizen(citizenId, p).getContent();
        return found.stream().filter(a -> departmentScope.mayRead(a.journeyCode())).toList();
    }

    @GetMapping("/{referenceNo}")
    ApplicationView byReference(@PathVariable String referenceNo) {
        ApplicationView view = tracking.byReference(referenceNo);
        departmentScope.requireReadable(view.journeyCode());
        citizenAccess.requireMayActOn(view.citizenId());
        return view;
    }

    @GetMapping("/{referenceNo}/steps")
    List<StepView> steps(@PathVariable String referenceNo) {
        ApplicationView view = tracking.byReference(referenceNo);
        departmentScope.requireReadable(view.journeyCode());
        citizenAccess.requireMayActOn(view.citizenId());
        return tracking.steps(referenceNo);
    }
}
