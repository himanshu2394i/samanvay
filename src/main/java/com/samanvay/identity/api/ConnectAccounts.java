package com.samanvay.identity.api;

import java.util.List;

public record ConnectAccounts(
        String journeyCode, List<DepartmentLinkNeed> departments, List<LinkProofProviderInfo> providers) {}
