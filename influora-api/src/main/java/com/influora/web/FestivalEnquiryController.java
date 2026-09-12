package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.service.BrandEmailOtpService;
import com.influora.service.FestivalEnquiryService;
import com.influora.web.dto.FestivalEnquiryDtos.SendEnquiryOtpRequest;
import com.influora.web.dto.FestivalEnquiryDtos.SubmitEnquiryRequest;
import com.influora.web.dto.FestivalEnquiryDtos.SubmitEnquiryResponse;
import com.influora.web.dto.auth.EmailOtpDtos.SendEmailOtpResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * PUBLIC enquiry intake for the Festival Box landing page (T-FESTIVALBOX-0905). Full path
 * {@code POST /api/v1/festival-enquiries} given {@code server.servlet.context-path=/api/v1}.
 *
 * <p>Unauthenticated by necessity: the submitter is a brand or creator who has no Influora account
 * yet — that is the entire point of the page. The corresponding {@code permitAll} entry in
 * {@code SecurityConfig} names this exact verb+path (never a {@code /festival-**} wildcard) so a
 * future festival endpoint cannot inherit public access by accident, following the same discipline
 * the Meta callback routes are registered with.
 *
 * <p>Returns the standard {@link ApiResponse} envelope, matching every other non-admin controller.
 * All abuse controls live in {@link FestivalEnquiryService}, not here.
 */
@RestController
public class FestivalEnquiryController {

    private final FestivalEnquiryService festivalEnquiryService;
    private final BrandEmailOtpService brandEmailOtpService;

    public FestivalEnquiryController(
            FestivalEnquiryService festivalEnquiryService,
            BrandEmailOtpService brandEmailOtpService) {
        this.festivalEnquiryService = festivalEnquiryService;
        this.brandEmailOtpService = brandEmailOtpService;
    }

    /**
     * Step one: mail a code to the address the visitor typed. Uses {@code sendOtpForPublicForm},
     * NOT {@code sendOtp} — see that method for why reusing the signup variant would dead-end every
     * existing customer who fills in this form.
     */
    @PostMapping("/festival-enquiries/send-otp")
    public ResponseEntity<ApiResponse<SendEmailOtpResponse>> sendOtp(
            @Valid @RequestBody SendEnquiryOtpRequest body, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        brandEmailOtpService.sendOtpForPublicForm(
                                body.email(), httpRequest.getRemoteAddr())));
    }

    /**
     * Store one enquiry. {@code httpRequest} is injected for the client address and user agent only
     * — every value that ends up in the row comes from the validated body, so a caller cannot use a
     * header to influence what is persisted about them.
     */
    @PostMapping("/festival-enquiries")
    public ResponseEntity<ApiResponse<SubmitEnquiryResponse>> submit(
            @Valid @RequestBody SubmitEnquiryRequest body, HttpServletRequest httpRequest) {
        // Verified HERE, deliberately, and not inside FestivalEnquiryService#submit.
        //
        // submit() is @Transactional. A verifyOtp call from inside it would join that transaction
        // with REQUIRED propagation, and when the code is wrong the ApiException would unwind
        // through submit's own rollback rules -- undoing the attempts++ that verifyOtp just wrote
        // and re-breaking the 3-attempt lockout that verifyOtp's noRollbackFor exists to keep.
        // Running it out here keeps verifyOtp in a transaction of its own, where its rules apply.
        //
        // Ordering note: this now runs BEFORE the honeypot check inside submit(). That does not
        // reopen the oracle [SEC: Kabir F-2] closed -- a bot has no code either way, so both the
        // trap-filled and trap-omitted requests collapse to the same INVALID_OTP, which is exactly
        // the indistinguishability that finding asked for.
        brandEmailOtpService.verifyOtp(body.email(), body.otp());
        return ResponseEntity.ok(ApiResponse.ok(festivalEnquiryService.submit(body, httpRequest)));
    }
}
