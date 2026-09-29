package com.samanvay.orchestration.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.orchestration.internal.service.PassbookUpload.Kind;
import com.samanvay.shared.InvalidRequestException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PassbookUploadTest {

    private static byte[] file(int size, int... magic) {
        byte[] b = new byte[size];
        Arrays.fill(b, (byte) 'x');
        for (int i = 0; i < magic.length; i++) {
            b[i] = (byte) magic[i];
        }
        return b;
    }

    private static final int OK = 20 * 1024;

    @Test
    void acceptsPdfJpegPngByContent() {
        assertThat(PassbookUpload.validate(file(OK, 0x25, 0x50, 0x44, 0x46))).isEqualTo(Kind.PDF);
        assertThat(PassbookUpload.validate(file(OK, 0xFF, 0xD8, 0xFF))).isEqualTo(Kind.JPEG);
        assertThat(PassbookUpload.validate(file(OK, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
                .isEqualTo(Kind.PNG);
    }

    @Test
    void rejectsAContentTypeItDoesNotRecognise() {
        // A .pdf name / declared type can't help: content wins. Here the bytes are plain text.
        assertThatThrownBy(() -> PassbookUpload.validate(file(OK, 'h', 'e', 'l', 'l', 'o')))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("PDF, JPEG or PNG");
        // A ZIP (PK..) — e.g. a docx renamed to pdf — is refused.
        assertThatThrownBy(() -> PassbookUpload.validate(file(OK, 0x50, 0x4B, 0x03, 0x04)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsTooSmallAndTooLarge() {
        assertThatThrownBy(() -> PassbookUpload.validate(file(5 * 1024, 0x25, 0x50, 0x44, 0x46)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("too small");
        assertThatThrownBy(() -> PassbookUpload.validate(file(300 * 1024, 0x25, 0x50, 0x44, 0x46)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("larger than");
    }

    @Test
    void rejectsNullAndEmpty() {
        assertThatThrownBy(() -> PassbookUpload.validate(null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> PassbookUpload.validate(new byte[0])).isInstanceOf(InvalidRequestException.class);
    }
}
