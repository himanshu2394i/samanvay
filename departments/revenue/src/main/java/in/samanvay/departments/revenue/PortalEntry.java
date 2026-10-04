package in.samanvay.departments.revenue;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The department's address is its citizen portal. */
@RestController
class PortalEntry {

    @GetMapping("/")
    ResponseEntity<Void> home() {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/portal/").build();
    }
}
