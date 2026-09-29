package com.samanvay.connector.internal.service;

import com.samanvay.connector.api.BankCheckAdapter;
import com.samanvay.connector.api.BankCheckAdapters;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Every {@link BankCheckAdapter} bean, by source code. Empty when no source is configured. */
@Component
class BankCheckAdapterRegistry implements BankCheckAdapters {

    private final Map<String, BankCheckAdapter> bySource;

    BankCheckAdapterRegistry(List<BankCheckAdapter> adapters) {
        this.bySource = adapters.stream().collect(Collectors.toUnmodifiableMap(BankCheckAdapter::sourceCode, Function.identity()));
    }

    @Override
    public Optional<BankCheckAdapter> forSource(String sourceCode) {
        return Optional.ofNullable(bySource.get(sourceCode));
    }
}
