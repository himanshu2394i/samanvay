package com.samanvay.identity.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.samanvay.identity.api.CitizenMatch;
import com.samanvay.identity.internal.domain.ProfileEntity;
import com.samanvay.identity.internal.repository.CandidateMatchRepository;
import com.samanvay.identity.internal.repository.CitizenRepository;
import com.samanvay.identity.internal.repository.LinkRepository;
import com.samanvay.identity.internal.repository.ProfileRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/** Officer citizen search: name substring or exact id, capped, and coarse fields only. */
class CitizenSearchTest {

    private final ProfileRepository profiles = mock(ProfileRepository.class);

    @Test
    void blankAndSingleCharacterQueriesReturnNothingWithoutHittingTheDatabase() {
        assertThat(service().search(null)).isEmpty();
        assertThat(service().search("   ")).isEmpty();
        assertThat(service().search("r")).isEmpty();
        verifyNoInteractions(profiles);
    }

    @Test
    void nameQueryMatchesOnNameAndReturnsOnlyCoarseFields() {
        ProfileEntity p = profile(UUID.randomUUID(), "Ramesh Kumar", "रमेश", LocalDate.of(2004, 1, 15));
        when(profiles.findByNameLatinContainingIgnoreCaseOrNameDevanagariContainingIgnoreCase(
                        anyString(), anyString(), any(Pageable.class)))
                .thenReturn(List.of(p));

        List<CitizenMatch> found = service().search("  ramesh ");

        assertThat(found).containsExactly(new CitizenMatch(p.getCitizenId(), "Ramesh Kumar", "रमेश", 2004));
        verify(profiles)
                .findByNameLatinContainingIgnoreCaseOrNameDevanagariContainingIgnoreCase(
                        org.mockito.ArgumentMatchers.eq("ramesh"), org.mockito.ArgumentMatchers.eq("ramesh"), any(Pageable.class));
    }

    @Test
    void exactIdMatchesEvenThoughItIsNotAName_andIsNotListedTwice() {
        UUID id = UUID.randomUUID();
        ProfileEntity p = profile(id, "Sita Devi", null, LocalDate.of(1990, 5, 1));
        when(profiles.findById(id)).thenReturn(Optional.of(p));
        when(profiles.findByNameLatinContainingIgnoreCaseOrNameDevanagariContainingIgnoreCase(
                        anyString(), anyString(), any(Pageable.class)))
                .thenReturn(List.of(p));

        assertThat(service().search(id.toString())).extracting(CitizenMatch::citizenId).containsExactly(id);
    }

    private static ProfileEntity profile(UUID id, String latin, String devanagari, LocalDate dob) {
        ProfileEntity p = new ProfileEntity();
        p.setCitizenId(id);
        p.setNameLatin(latin);
        p.setNameDevanagari(devanagari);
        p.setDob(dob);
        return p;
    }

    private IdentityServices service() {
        return new IdentityServices(
                mock(CitizenRepository.class),
                profiles,
                mock(LinkRepository.class),
                mock(CandidateMatchRepository.class),
                new CandidateScorer(),
                new ReviewerAuth(),
                e -> {},
                mock(com.samanvay.audit.api.AuditService.class),
                List.of(),
                mock(com.samanvay.catalog.api.JourneyCatalog.class),
                mock(com.samanvay.catalog.api.DepartmentCatalog.class));
    }
}
