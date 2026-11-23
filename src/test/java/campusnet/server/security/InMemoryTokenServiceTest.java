package campusnet.server.security;

import campusnet.api.Config;
import campusnet.api.Grant;
import campusnet.api.Op;
import campusnet.api.Principal;
import campusnet.api.Role;
import campusnet.common.PropertiesConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryTokenServiceTest {

    private InMemoryTokenService tokenService;
    private Principal student;

    @BeforeEach
    void setUp() {
        tokenService = new InMemoryTokenService(PropertiesConfig.empty());
        student = new Principal("alice", Role.STUDENT);
    }

    @Test
    @DisplayName("issue produces 128-bit hex string (32 lowercase hex characters)")
    void issueFormat() {
        String token = tokenService.issue(student, Op.PUT, "report.pdf", 1024);
        assertNotNull(token);
        assertEquals(32, token.length());
        assertTrue(token.matches("^[0-9a-f]{32}$"));
    }

    @Test
    @DisplayName("redeem is one-time: removes token atomically on first use")
    void redeemOneTimeUse() {
        String token = tokenService.issue(student, Op.PUT, "notes.txt", 2048);

        Optional<Grant> first = tokenService.redeem(token);
        assertTrue(first.isPresent());
        assertEquals(student, first.get().who());
        assertEquals(Op.PUT, first.get().op());
        assertEquals("notes.txt", first.get().fileName());
        assertEquals(2048, first.get().size());

        Optional<Grant> second = tokenService.redeem(token);
        assertTrue(second.isEmpty());
    }

    @Test
    @DisplayName("redeem returns empty for unknown, null, or empty tokens")
    void redeemUnknownOrInvalid() {
        assertTrue(tokenService.redeem("0123456789abcdef0123456789abcdef").isEmpty());
        assertTrue(tokenService.redeem(null).isEmpty());
        assertTrue(tokenService.redeem("").isEmpty());
    }

    @Test
    @DisplayName("redeem returns empty when token is expired (TTL elapsed)")
    void redeemExpiredTokenFails() throws InterruptedException {
        Properties props = new Properties();
        props.setProperty("token.ttlSec", "0"); // Expire immediately
        InMemoryTokenService shortTtlService = new InMemoryTokenService(new PropertiesConfig(props));

        String token = shortTtlService.issue(student, Op.GET, "file.txt", 0);
        Thread.sleep(10); // Guarantee nanoTime passes expiration

        Optional<Grant> grant = shortTtlService.redeem(token);
        assertTrue(grant.isEmpty());
    }

    @Test
    @DisplayName("Concurrent redeem: exactly one thread wins among multiple contenders")
    void concurrentRedeemExactlyOneWinner() throws Exception {
        String token = tokenService.issue(student, Op.PUT, "concurrent.bin", 4096);

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<Optional<Grant>>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> tokenService.redeem(token));
        }

        List<Future<Optional<Grant>>> results = executor.invokeAll(tasks);
        executor.shutdown();

        int winners = 0;
        for (Future<Optional<Grant>> future : results) {
            if (future.get().isPresent()) {
                winners++;
            }
        }

        assertEquals(1, winners, "Exactly one thread must successfully redeem the token");
    }

    @Test
    @DisplayName("Per-user token cap: issuing beyond limit throws IllegalStateException")
    void userTokenCapEnforced() {
        Properties props = new Properties();
        props.setProperty("token.maxPerUser", "5");
        InMemoryTokenService cappedService = new InMemoryTokenService(new PropertiesConfig(props));

        for (int i = 0; i < 5; i++) {
            cappedService.issue(student, Op.PUT, "file" + i + ".txt", 100);
        }

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> cappedService.issue(student, Op.PUT, "file5.txt", 100));
        assertTrue(ex.getMessage().contains("Maximum outstanding tokens"));
    }

    @Test
    @DisplayName("Expired tokens are purged on issue, freeing capacity under the cap")
    void expiredTokensPurgedOnIssue() throws InterruptedException {
        Properties props = new Properties();
        props.setProperty("token.ttlSec", "1");
        props.setProperty("token.maxPerUser", "2");
        InMemoryTokenService cappedService = new InMemoryTokenService(new PropertiesConfig(props));

        cappedService.issue(student, Op.PUT, "file1.txt", 100);
        cappedService.issue(student, Op.PUT, "file2.txt", 100);

        // Limit reached
        assertThrows(IllegalStateException.class,
                () -> cappedService.issue(student, Op.PUT, "file3.txt", 100));

        // Wait for TTL to expire
        Thread.sleep(1100);

        // Issue should purge expired tokens and succeed
        String newToken = cappedService.issue(student, Op.PUT, "file3.txt", 100);
        assertNotNull(newToken);
    }

    @Test
    @DisplayName("issue validates input parameters")
    void issueValidatesInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> tokenService.issue(null, Op.PUT, "file.txt", 100));
        assertThrows(IllegalArgumentException.class,
                () -> tokenService.issue(student, null, "file.txt", 100));
        assertThrows(IllegalArgumentException.class,
                () -> tokenService.issue(student, Op.PUT, null, 100));
        assertThrows(IllegalArgumentException.class,
                () -> tokenService.issue(student, Op.PUT, "", 100));
        assertThrows(IllegalArgumentException.class,
                () -> tokenService.issue(student, Op.PUT, "file.txt", -1));
    }

    @Test
    @DisplayName("Concurrent issue enforces the per-user token cap")
    void concurrentIssueEnforcesTokenCap() throws Exception {
        Properties props = new Properties();
        props.setProperty("token.maxPerUser", "5");
        InMemoryTokenService cappedService = new InMemoryTokenService(new PropertiesConfig(props));

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(20);
        AtomicInteger issued = new AtomicInteger();

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int index = i;
            tasks.add(() -> {
                ready.countDown();
                ready.await();
                try {
                    cappedService.issue(student, Op.PUT, "file-" + index + ".txt", 100);
                    issued.incrementAndGet();
                    return true;
                } catch (IllegalStateException e) {
                    return false;
                }
            });
        }

        for (Future<Boolean> future : executor.invokeAll(tasks)) {
            future.get();
        }
        executor.shutdown();

        assertEquals(5, issued.get());
    }
}
