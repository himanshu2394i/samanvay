package com.samanvay.connector.api;

import java.util.Optional;

/** Registry of {@link BankCheckAdapter}s by source code. */
public interface BankCheckAdapters {

    Optional<BankCheckAdapter> forSource(String sourceCode);
}
