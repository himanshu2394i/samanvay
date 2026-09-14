package com.samanvay.connector.internal.web;

import com.samanvay.connector.api.IssuedDocuments;
import com.samanvay.connector.api.LockerDocument;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/connector")
class IssuedDocumentsController {

    private final IssuedDocuments issued;

    IssuedDocumentsController(IssuedDocuments issued) {
        this.issued = issued;
    }

    @GetMapping("/issued-documents")
    List<LockerDocument> locker(@RequestParam String departmentCode) {
        return issued.lockerForDepartment(departmentCode);
    }
}
