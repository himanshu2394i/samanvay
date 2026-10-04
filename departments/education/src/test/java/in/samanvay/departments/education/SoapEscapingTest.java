package in.samanvay.departments.education;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Whatever the Board's database holds is written into the SOAP reply as text, never as markup. */
class SoapEscapingTest {

    static final String REQUEST = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Header>"
            + "<wsse:Security xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\"><wsse:UsernameToken>"
            + "<wsse:Username>u</wsse:Username><wsse:Password>p</wsse:Password></wsse:UsernameToken></wsse:Security></soap:Header>"
            + "<soap:Body><GetMarksRequest><studentId>EDU-1</studentId></GetMarksRequest></soap:Body></soap:Envelope>";

    @Test
    void markup_in_a_stored_value_comes_out_as_text_and_the_reply_stays_well_formed() throws Exception {
        MarksRecords hostile = id -> Optional.of(new MarksRecords.Marks("EDU-1", "91.5", "Board <b>\"A\"</b> & 'Sons'", "HSC</exam><injected>x</injected>"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new EducationController(hostile, "u", "p")).build();

        String xml = mvc.perform(post("/marks/service").header("SOAPAction", "GetMarks").contentType(MediaType.TEXT_XML).content(REQUEST))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        org.w3c.dom.Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertThat(doc.getElementsByTagName("injected").getLength()).isZero();
        assertThat(doc.getElementsByTagName("board").item(0).getTextContent()).isEqualTo("Board <b>\"A\"</b> & 'Sons'");
        assertThat(doc.getElementsByTagName("exam").item(0).getTextContent()).isEqualTo("HSC</exam><injected>x</injected>");
        assertThat(doc.getElementsByTagName("percentage").item(0).getTextContent()).isEqualTo("91.5");
    }
}
