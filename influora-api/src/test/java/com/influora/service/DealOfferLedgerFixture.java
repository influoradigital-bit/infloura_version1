package com.influora.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import com.influora.domain.entity.Collaboration;
import com.influora.repository.CollaborationRepository;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;2.6), B0-43 — the ONE stub that every {@code DealService}
 * write path now needs, in one place instead of twenty-eight.
 *
 * <p><b>Why this exists.</b> {@code DealService.recordOffer} takes the collaboration row lock itself
 * and <b>checks the result</b>, because a lock that cannot fail loudly breaks silently (PRIYA-COMPAT-0912
 * condition). Under Mockito an unstubbed {@code findByIdForUpdate} returns {@code Optional.empty()},
 * so the checked lock turns every test that reaches {@code createProposal}, {@code doCounter},
 * {@code doAccept} or {@code doReject} without that stub into an error. That is the correct behaviour
 * of the production code and the correct behaviour of the mock; it is only the fixture that was
 * missing. Call {@link #stubOfferLedgerRowLock} from a suite's {@code @BeforeEach} and every present
 * and future write-path test in it inherits the stub, so the next person adding a write path does not
 * discover this by failure.
 *
 * <p><b>The stub answers with a placeholder row, and that is not a shortcut.</b> Two of the four write
 * points make a real row impossible to supply here: {@code createProposal} locks a collaboration the
 * service itself has just created, so no test can hold that instance before the call, and
 * {@code doCounter}/{@code doAccept} lock a row whose only purpose at that point is serialisation —
 * {@code recordOffer} reads <b>presence and nothing else</b>, because its caller already holds the
 * entity. The placeholder therefore asserts exactly what the production code asserts: the row is
 * lockable. It carries the queried id so that anything which does start reading it is at least about
 * the right deal, and {@code doReject} — the one path that genuinely consumes the locked row — is
 * stubbed explicitly by its own tests, which run after this one and win.
 *
 * <p><b>If {@code recordOffer} ever starts USING the returned row, this fixture becomes wrong and
 * must be replaced by a per-test stub of the real entity.</b> That is the one change to watch for; a
 * placeholder feeding a reader is worse than no stub, because it is silent.
 *
 * <p>{@code lenient()} because the stub is installed for the whole suite while most tests in these
 * classes never reach a write path, and strict stubs would report an unused stub as a failure.
 */
final class DealOfferLedgerFixture {

    /** Distinctive on purpose: if this id ever surfaces in an assertion, the placeholder was read. */
    private static final String PLACEHOLDER_CAMPAIGN_ID = "01HLOCKPLACEHOLDERCAMP";

    private static final String PLACEHOLDER_CREATOR_ID = "01HLOCKPLACEHOLDERCRTR";

    private DealOfferLedgerFixture() {}

    /**
     * Makes the offer-history row lock succeed for any collaboration id, as it does in production
     * wherever the caller has already loaded or saved the row.
     *
     * <p>Stubs {@code anyString()} rather than a single constant so that a suite whose collaboration
     * id is minted inside the service under test — {@code createProposal} — is covered too.
     */
    static void stubOfferLedgerRowLock(CollaborationRepository collaborationRepository) {
        lenient()
                .when(collaborationRepository.findByIdForUpdate(anyString()))
                .thenAnswer(invocation -> Optional.of(lockablePlaceholder(invocation.getArgument(0))));
    }

    private static Collaboration lockablePlaceholder(String collaborationId) {
        return Collaboration.propose(
                collaborationId,
                PLACEHOLDER_CAMPAIGN_ID,
                PLACEHOLDER_CREATOR_ID,
                new BigDecimal("1"),
                "INR",
                "row-lock placeholder");
    }
}
