package com.samanvay;

import com.samanvay.connector.api.IssuedDocuments;
import com.samanvay.connector.api.IssuedRecord;
import com.samanvay.tracking.api.ApplicationTracking;
import com.samanvay.tracking.api.StepView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/applications")
class IssuedRecordsWeb {

    private final ApplicationTracking tracking;
    private final IssuedDocuments issued;

    IssuedRecordsWeb(ApplicationTracking tracking, IssuedDocuments issued) {
        this.tracking = tracking;
        this.issued = issued;
    }

    @GetMapping("/{referenceNo}/issued-records")
    List<IssuedRecord> issuedRecords(@PathVariable String referenceNo) {
        return tracking.steps(referenceNo).stream()
                .map(this::preview)
                .toList();
    }

    private IssuedRecord preview(StepView step) {
        return issued.preview(step.stepCode(), step.departmentCode(), step.status());
    }
}
