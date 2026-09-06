package com.influora.web;

import com.influora.service.FestivalCouponCopyService;
import com.influora.web.dto.FestivalCouponCopyDtos.RecordCouponCopyRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * PUBLIC coupon-copy tracking intake for the Festival Box page (T-FESTIVALBOX-0905 phase 6). Full
 * path {@code POST /api/v1/festival/coupon-copied} given {@code
 * server.servlet.context-path=/api/v1}.
 *
 * <p>Unauthenticated for the same reason {@link FestivalEnquiryController} is: the caller is a
 * shopper browsing {@code /festival-box/:edition} with no Influora account. The corresponding
 * {@code permitAll} entry in {@code SecurityConfig} names this exact verb+path (never a
 * {@code /festival/**} wildcard), same discipline as that controller's javadoc describes.
 *
 * <p><b>Always 204, no body, on ANY well-formed request</b> — the client fires this from {@code
 * navigator.clipboard.writeText}'s success handler and must never be blocked or shown anything;
 * see {@code FestivalCouponCopyService} for why an unrecognized sponsor also gets this same 204
 * rather than a distinguishable response. A malformed request (fails {@code @Valid}) still gets
 * the standard 400 from {@code GlobalExceptionHandler} — that is a client-side bug in the request
 * shape, not information about a specific sponsor.
 *
 * <p>All abuse controls (charset/length validation, the sponsor-recognition check, the bounded
 * daily-bucket table) live in {@link FestivalCouponCopyService} / the coupon-copy repository, not
 * here — this controller is deliberately as thin as {@link FestivalEnquiryController}.
 */
@RestController
public class FestivalCouponCopyController {

    private final FestivalCouponCopyService festivalCouponCopyService;

    public FestivalCouponCopyController(FestivalCouponCopyService festivalCouponCopyService) {
        this.festivalCouponCopyService = festivalCouponCopyService;
    }

    @PostMapping("/festival/coupon-copied")
    public ResponseEntity<Void> recordCopy(
            @Valid @RequestBody RecordCouponCopyRequest body, HttpServletRequest httpRequest) {
        // [Kabir M-1/M-2] getRemoteAddr() ONLY — never a raw X-Forwarded-For read. A client can put
        // anything in that header, so trusting it here would let one caller mint a fresh throttle
        // bucket per request and make the per-origin influence cap a no-op. Tomcat's RemoteIpValve
        // (forward-headers-strategy: native) has already validated the peer and rewritten this.
        festivalCouponCopyService.recordCopy(body, httpRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
