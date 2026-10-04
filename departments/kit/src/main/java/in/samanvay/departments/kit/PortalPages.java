package in.samanvay.departments.kit;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The portal's pages are static files under /portal/; Spring does not serve a folder's index.html by itself, so say so. */
@Controller
public class PortalPages {

    @GetMapping({"/portal", "/portal/"})
    String index() {
        return "forward:/portal/index.html";
    }
}
