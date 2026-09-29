package com.samanvay.orchestration.internal.web;

import com.samanvay.orchestration.internal.service.BankReviewService;
import com.samanvay.orchestration.internal.service.BankReviewView;
import com.samanvay.shared.InvalidRequestException;
import com.samanvay.shared.security.Callers;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Officer card for bank checks that need a human. The bank holder's name is never
 * returned here (the API only exposes the masked account, the reason and the
 * matcher version). All routes are OFFICER-only (SecurityConfig + ApiAccessMatrix).
 */
@RestController
@RequestMapping("/api/officer/bank-reviews")
class OfficerBankReviewController {

    private final BankReviewService reviews;

    OfficerBankReviewController(BankReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping
    List<BankReviewView> open() {
        return reviews.openReviews();
    }

    /** Attach a passbook / cancelled cheque. Validated by content (magic bytes) and size. */
    @PostMapping("/{id}/passbook")
    void uploadPassbook(@PathVariable UUID id, @RequestParam(value = "file", required = false) MultipartFile file) {
        // required=false so a missing/non-multipart request is a 400 from us, not a framework 500.
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("a passbook file is required");
        }
        try {
            reviews.attachPassbook(id, file.getBytes(), officer());
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the uploaded file", e);
        }
    }

    @PostMapping("/{id}/request-document")
    void requestDocument(@PathVariable UUID id) {
        reviews.requestDocument(id, officer());
    }

    @PostMapping("/{id}/approve")
    void approve(@PathVariable UUID id, @RequestBody(required = false) Decision body) {
        reviews.approve(id, body == null ? null : body.reason(), officer());
    }

    @PostMapping("/{id}/reject")
    void reject(@PathVariable UUID id, @RequestBody(required = false) Decision body) {
        reviews.reject(id, InvalidRequestException.requireText(body == null ? null : body.reason(), "reason"), officer());
    }

    private static String officer() {
        return Callers.require().subject();
    }

    record Decision(String reason) {}
}
