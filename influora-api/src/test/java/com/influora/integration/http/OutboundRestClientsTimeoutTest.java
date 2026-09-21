package com.influora.integration.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * EV-045 — execution proof that a client built through {@link OutboundRestClients} abandons a hung
 * provider at the read timeout instead of waiting on it.
 *
 * <p>The stub is a local {@link ServerSocket} that ACCEPTS the connection and then never writes a
 * byte. That is the failure mode that matters and the one a refused connection does not reproduce:
 * a refused connect fails instantly even with no timeout configured, so a test against a closed
 * port would pass against the broken code and prove nothing.
 *
 * <p>No real provider is contacted — everything here is 127.0.0.1.
 */
class OutboundRestClientsTimeoutTest {

    /** Short enough to keep the test fast, long enough that it cannot be mistaken for an instant
     * connection error (see {@link #assertItWasTheTimeoutAndNotAnInstantFailure}). */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private ServerSocket serverSocket;
    private ExecutorService accepter;
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();
    private String baseUrl;

    @BeforeEach
    void startHungServer() throws IOException {
        serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        baseUrl = "http://127.0.0.1:" + serverSocket.getLocalPort();
        accepter = Executors.newSingleThreadExecutor();
        accepter.submit(
                () -> {
                    while (!serverSocket.isClosed()) {
                        try {
                            // Accept and hold. Never read, never respond — the hung provider.
                            accepted.add(serverSocket.accept());
                        } catch (IOException e) {
                            return;
                        }
                    }
                });
    }

    @AfterEach
    void stopHungServer() throws Exception {
        for (Socket socket : accepted) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
        serverSocket.close();
        accepter.shutdownNow();
        accepter.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void aHungProviderIsAbandonedAtTheReadTimeout() {
        RestClient client =
                OutboundRestClients.builder(Duration.ofSeconds(2), READ_TIMEOUT)
                        .baseUrl(baseUrl)
                        .build();

        long startedNanos = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/hangs-forever").retrieve().body(String.class))
                .isInstanceOf(Exception.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);

        assertItWasTheTimeoutAndNotAnInstantFailure(elapsed);
    }

    @Test
    void theDefaultBuilderIsAlsoBounded() {
        // The overload every production call site actually uses. Its read timeout is
        // OutboundRestClients.DEFAULT_READ_TIMEOUT, so the upper bound comes from there.
        RestClient client = OutboundRestClients.builder().baseUrl(baseUrl).build();

        long startedNanos = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/hangs-forever").retrieve().body(String.class))
                .isInstanceOf(Exception.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);

        assertThat(elapsed)
                .as("returned at the default read timeout, not at the provider's convenience")
                .isLessThan(OutboundRestClients.DEFAULT_READ_TIMEOUT.plusSeconds(10));
        assertThat(OutboundRestClients.DEFAULT_READ_TIMEOUT).isGreaterThan(Duration.ZERO);
        assertThat(OutboundRestClients.DEFAULT_CONNECT_TIMEOUT).isGreaterThan(Duration.ZERO);
    }

    /**
     * FALSIFICATION. Two ways this test could be green while the fix is absent:
     *
     * <ul>
     *   <li>The request never reached the server and failed instantly (wrong port, connection
     *       refused) — then {@code elapsed} would be milliseconds, far below the read timeout. So
     *       we assert a LOWER bound too.
     *   <li>The read timeout was never applied and something else ended the call — then {@code
     *       elapsed} would be the JDK/OS default, minutes. So we assert an UPPER bound.
     * </ul>
     *
     * Only an actual read timeout lands between the two.
     */
    private static void assertItWasTheTimeoutAndNotAnInstantFailure(Duration elapsed) {
        assertThat(elapsed)
                .as("must have actually waited for the read timeout, not failed to connect")
                .isGreaterThanOrEqualTo(READ_TIMEOUT.minusMillis(250));
        assertThat(elapsed)
                .as("must have given up at the read timeout, not waited on the hung provider")
                .isLessThan(READ_TIMEOUT.plusSeconds(10));
    }
}
