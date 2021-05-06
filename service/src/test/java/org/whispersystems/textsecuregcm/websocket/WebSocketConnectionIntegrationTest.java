package org.whispersystems.textsecuregcm.websocket;

import com.google.protobuf.ByteString;
import com.opentable.db.postgres.embedded.LiquibasePreparer;
import com.opentable.db.postgres.junit.EmbeddedPostgresRules;
import com.opentable.db.postgres.junit.PreparedDbRule;
import org.apache.commons.lang3.RandomStringUtils;
import org.jdbi.v3.core.Jdbi;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.stubbing.Answer;
import org.whispersystems.textsecuregcm.configuration.CircuitBreakerConfiguration;
import org.whispersystems.textsecuregcm.entities.MessageProtos;
import org.whispersystems.textsecuregcm.metrics.PushLatencyManager;
import org.whispersystems.textsecuregcm.push.PushSender;
import org.whispersystems.textsecuregcm.push.ReceiptSender;
import org.whispersystems.textsecuregcm.redis.AbstractRedisClusterTest;
import org.whispersystems.textsecuregcm.storage.Account;
import org.whispersystems.textsecuregcm.storage.Device;
import org.whispersystems.textsecuregcm.storage.FaultTolerantDatabase;
import org.whispersystems.textsecuregcm.storage.Messages;
import org.whispersystems.textsecuregcm.storage.MessagesCache;
import org.whispersystems.textsecuregcm.storage.MessagesManager;
import org.whispersystems.websocket.WebSocketClient;
import org.whispersystems.websocket.messages.WebSocketResponseMessage;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class WebSocketConnectionIntegrationTest extends AbstractRedisClusterTest {

    @Rule
    public PreparedDbRule db = EmbeddedPostgresRules.preparedDatabase(LiquibasePreparer.forClasspathLocation("messagedb.xml"));

    private ExecutorService executorService;
    private Messages messages;
    private MessagesCache messagesCache;
    private Account account;
    private Device device;
    private WebSocketClient webSocketClient;
    private WebSocketConnection webSocketConnection;

    @Before
    public void setupAccountsDao() {
    }

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        executorService = Executors.newSingleThreadExecutor();
        messages = new Messages(new FaultTolerantDatabase("messages-test", Jdbi.create(db.getTestDatabase()), new CircuitBreakerConfiguration()));
        messagesCache = new MessagesCache(getRedisCluster(), executorService);
        account = mock(Account.class);
        device = mock(Device.class);
        webSocketClient = mock(WebSocketClient.class);

        when(account.getNumber()).thenReturn("+18005551234");
        when(account.getUuid()).thenReturn(UUID.randomUUID());
        when(device.getId()).thenReturn(1L);

        webSocketConnection = new WebSocketConnection(mock(PushSender.class),
                mock(ReceiptSender.class),
                new MessagesManager(messages, messagesCache, mock(PushLatencyManager.class)),
                account,
                device,
                webSocketClient,
                "connection-id");
    }

    @After
    @Override
    public void tearDown() throws Exception {
        executorService.shutdown();
        executorService.awaitTermination(2, TimeUnit.SECONDS);

        super.tearDown();
    }

    @Test(timeout = 15_000)
    public void testProcessStoredMessages() throws InterruptedException {
        final int persistedMessageCount = 207;
        final int cachedMessageCount = 173;

        for (int i = 0; i < persistedMessageCount; i++) {
            final UUID messageGuid = UUID.randomUUID();
            messages.store(messageGuid, generateRandomMessage(messageGuid), account.getNumber(), device.getId());
        }

        for (int i = 0; i < cachedMessageCount; i++) {
            final UUID messageGuid = UUID.randomUUID();
            messagesCache.insert(messageGuid, account.getUuid(), device.getId(), generateRandomMessage(messageGuid));
        }

        final WebSocketResponseMessage successResponse = mock(WebSocketResponseMessage.class);
        final AtomicBoolean queueCleared = new AtomicBoolean(false);

        when(successResponse.getStatus()).thenReturn(200);
        when(webSocketClient.sendRequest(eq("PUT"), eq("/api/v1/message"), anyList(), any())).thenReturn(CompletableFuture.completedFuture(successResponse));

        when(webSocketClient.sendRequest(eq("PUT"), eq("/api/v1/queue/empty"), anyList(), any())).thenAnswer((Answer<CompletableFuture<WebSocketResponseMessage>>)invocation -> {
            synchronized (queueCleared) {
                queueCleared.set(true);
                queueCleared.notifyAll();
            }

            return CompletableFuture.completedFuture(successResponse);
        });

        webSocketConnection.processStoredMessages();

        synchronized (queueCleared) {
            while (!queueCleared.get()) {
                queueCleared.wait();
            }
        }

        verify(webSocketClient, times(persistedMessageCount + cachedMessageCount)).sendRequest(eq("PUT"), eq("/api/v1/message"), anyList(), any());
        verify(webSocketClient).sendRequest(eq("PUT"), eq("/api/v1/queue/empty"), anyList(), eq(Optional.empty()));
    }

    @Test(timeout = 15_000)
    public void testProcessStoredMessagesClientClosed() {
        final int persistedMessageCount = 207;
        final int cachedMessageCount = 173;

        for (int i = 0; i < persistedMessageCount; i++) {
            final UUID messageGuid = UUID.randomUUID();
            messages.store(messageGuid, generateRandomMessage(messageGuid), account.getNumber(), device.getId());
        }

        for (int i = 0; i < cachedMessageCount; i++) {
            final UUID messageGuid = UUID.randomUUID();
            messagesCache.insert(messageGuid, account.getUuid(), device.getId(), generateRandomMessage(messageGuid));
        }

        when(webSocketClient.sendRequest(eq("PUT"), eq("/api/v1/message"), anyList(), any())).thenReturn(CompletableFuture.failedFuture(new IOException("Connection closed")));

        webSocketConnection.processStoredMessages();

        verify(webSocketClient, atMost(persistedMessageCount + cachedMessageCount)).sendRequest(eq("PUT"), eq("/api/v1/message"), anyList(), any());
        verify(webSocketClient, never()).sendRequest(eq("PUT"), eq("/api/v1/queue/empty"), anyList(), eq(Optional.empty()));
    }

    private MessageProtos.Envelope generateRandomMessage(final UUID messageGuid) {
        return MessageProtos.Envelope.newBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setServerTimestamp(System.currentTimeMillis())
                .setContent(ByteString.copyFromUtf8(RandomStringUtils.randomAlphanumeric(256)))
                .setType(MessageProtos.Envelope.Type.CIPHERTEXT)
                .setServerGuid(messageGuid.toString())
                .build();
    }
}
