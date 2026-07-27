package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.DealSenderType;
import com.influora.security.AuthPrincipal;
import com.influora.service.DealMessageStreamRegistry;
import com.influora.service.DealService;
import com.influora.web.dto.deal.DealDtos.CounterRequest;
import com.influora.web.dto.deal.DealDtos.DealMessageResponse;
import com.influora.web.dto.deal.DealDtos.DealResponse;
import com.influora.web.dto.deal.DealDtos.OkResponse;
import com.influora.web.dto.deal.DealDtos.SendMessageRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Task #9 — pure delegation tests for {@link DealController}. */
@ExtendWith(MockitoExtension.class)
class DealControllerTest {

    @Mock private DealService dealService;
    @Mock private com.influora.service.DisputeService disputeService;
    @Mock private DealMessageStreamRegistry messageStreamRegistry;
    @Mock private com.influora.service.ShipmentService shipmentService;
    @Mock private AuthPrincipal principal;

    private DealController controller;

    @BeforeEach
    void setUp() {
        controller = new DealController(dealService, disputeService, messageStreamRegistry, shipmentService);
    }

    @Test
    @DisplayName("GET /deals delegates to service with status filter")
    void testList() {
        DealResponse deal =
                new DealResponse(
                        "deal1",
                        "camp1",
                        "Campaign",
                        "cp1",
                        "Brand",
                        null,
                        null,
                        CollaborationStatus.INVITED,
                        BigDecimal.TEN,
                        "INR",
                        "Hi",
                        Instant.now(),
                        1,
                        0,
                        0,
                        null,
                        null,
                        null,
                        false);
        when(dealService.list(principal, "new")).thenReturn(List.of(deal));

        ResponseEntity<ApiResponse<List<DealResponse>>> response = controller.list(principal, "new");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().data().size());
        verify(dealService).list(principal, "new");
    }

    @Test
    @DisplayName("POST /deals/{id}/accept delegates with idempotency header")
    void testAccept() {
        DealResponse deal =
                new DealResponse(
                        "deal1",
                        "camp1",
                        "Campaign",
                        "cp1",
                        "Brand",
                        null,
                        null,
                        CollaborationStatus.TERMS_AGREED,
                        BigDecimal.TEN,
                        "INR",
                        null,
                        null,
                        0,
                        0,
                        0,
                        null,
                        null,
                        null,
                        false);
        when(dealService.accept(principal, "deal1", "idem-1")).thenReturn(deal);

        ResponseEntity<ApiResponse<DealResponse>> response =
                controller.accept(principal, "deal1", "idem-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(CollaborationStatus.TERMS_AGREED, response.getBody().data().status());
        verify(dealService).accept(principal, "deal1", "idem-1");
    }

    @Test
    @DisplayName("GET /deals/{id}/messages delegates to service")
    void testListMessages() {
        DealMessageResponse message =
                new DealMessageResponse(
                        "m1",
                        "deal1",
                        DealMessageKind.text,
                        "u1",
                        DealSenderType.creator,
                        "Hello",
                        null,
                        Instant.now(),
                        List.of());
        when(dealService.listMessages(principal, "deal1", null)).thenReturn(List.of(message));

        ResponseEntity<ApiResponse<List<DealMessageResponse>>> response =
                controller.listMessages(principal, "deal1", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Hello", response.getBody().data().get(0).content());
        verify(dealService).listMessages(principal, "deal1", null);
    }

    @Test
    @DisplayName("POST /deals/{id}/messages/read delegates to service")
    void testMarkRead() {
        when(dealService.markRead(principal, "deal1")).thenReturn(OkResponse.success());

        ResponseEntity<ApiResponse<OkResponse>> response = controller.markRead(principal, "deal1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().data().ok());
        verify(dealService).markRead(principal, "deal1");
    }

    @Test
    @DisplayName("POST /deals/{id}/counter delegates with body and idempotency key")
    void testCounter() {
        CounterRequest body = new CounterRequest(new BigDecimal("30000"), "How about this?", null, null, null);
        DealResponse deal =
                new DealResponse(
                        "deal1",
                        "camp1",
                        "Campaign",
                        "cp1",
                        "Brand",
                        null,
                        null,
                        CollaborationStatus.IN_NEGOTIATION,
                        new BigDecimal("30000"),
                        "INR",
                        null,
                        null,
                        0,
                        0,
                        0,
                        null,
                        null,
                        null,
                        false);
        when(dealService.counter(principal, "deal1", body, "idem-2")).thenReturn(deal);

        ResponseEntity<ApiResponse<DealResponse>> response =
                controller.counter(principal, "deal1", "idem-2", body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(new BigDecimal("30000"), response.getBody().data().dealValue());
        verify(dealService).counter(principal, "deal1", body, "idem-2");
    }

    @Test
    @DisplayName("POST /deals/{dealId}/messages delegates to service")
    void testSendMessage() {
        SendMessageRequest body = new SendMessageRequest("Ping", DealMessageKind.text);
        DealMessageResponse message =
                new DealMessageResponse(
                        "m2",
                        "deal1",
                        DealMessageKind.text,
                        "u1",
                        DealSenderType.brand,
                        "Ping",
                        null,
                        Instant.now(),
                        List.of());
        when(dealService.sendMessage(principal, "deal1", body)).thenReturn(message);

        ResponseEntity<ApiResponse<DealMessageResponse>> response =
                controller.sendMessage(principal, "deal1", body);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("Ping", response.getBody().data().content());
        verify(dealService).sendMessage(principal, "deal1", body);
    }

    // ------------------------------------------------------------------
    // Realtime deal-message SSE stream — GET /deals/{dealId}/messages/stream.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /deals/{dealId}/messages/stream authorizes THEN registers an emitter")
    void testStreamMessagesRegistersEmitterOnAuthorizedOpen() {
        SseEmitter emitter = controller.streamMessages(principal, "deal1");

        assertNotNull(emitter);
        var inOrder = org.mockito.Mockito.inOrder(dealService, messageStreamRegistry);
        inOrder.verify(dealService).authorizeMessageStream(principal, "deal1");
        inOrder.verify(messageStreamRegistry).register(eq("deal1"), any(SseEmitter.class));
    }

    @Test
    @DisplayName("GET /deals/{dealId}/messages/stream: caller not a party to the deal is rejected — no emitter created")
    void testStreamMessagesRejectsUnauthorizedCallerBeforeEmitterCreated() {
        doThrow(new ApiException("DEAL_NOT_FOUND", "Deal not found", HttpStatus.NOT_FOUND))
                .when(dealService)
                .authorizeMessageStream(principal, "deal1");

        assertThrows(ApiException.class, () -> controller.streamMessages(principal, "deal1"));

        verifyNoInteractions(messageStreamRegistry);
    }
}
