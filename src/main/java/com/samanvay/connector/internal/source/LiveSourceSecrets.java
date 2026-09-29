package com.samanvay.connector.internal.source;

import com.samanvay.connector.internal.source.ifscbank.IfscBankClient;
import com.samanvay.connector.internal.source.ifscbank.IfscBankSourceProperties;
import com.samanvay.connector.internal.source.sftp.SftpSourceProperties;
import com.samanvay.shared.RequiredSecrets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** The credential of every source configured {@link SourceMode#LIVE}, for the production boot guard. */
@Component
class LiveSourceSecrets implements RequiredSecrets {

    private final ObjectProvider<IfscBankSourceProperties> ifscBank;
    private final ObjectProvider<SftpSourceProperties> sftp;

    LiveSourceSecrets(ObjectProvider<IfscBankSourceProperties> ifscBank, ObjectProvider<SftpSourceProperties> sftp) {
        this.ifscBank = ifscBank;
        this.sftp = sftp;
    }

    @Override
    public List<String> keys() {
        List<String> keys = new ArrayList<>();
        IfscBankSourceProperties bank = ifscBank.getIfAvailable();
        if (bank != null && bank.mode() == SourceMode.LIVE) {
            keys.add(SourceCredentials.secretKey(IfscBankClient.SOURCE_CODE));
        }
        SftpSourceProperties files = sftp.getIfAvailable();
        if (files != null) {
            files.sources().forEach((code, source) -> {
                if (source.mode() == SourceMode.LIVE) {
                    keys.add(SourceCredentials.secretKey(code));
                }
            });
        }
        return keys;
    }
}
