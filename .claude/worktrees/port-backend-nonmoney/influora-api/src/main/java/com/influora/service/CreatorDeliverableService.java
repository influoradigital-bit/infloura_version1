package com.influora.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.common.LimitedInputStream;
import com.influora.common.MediaMimeSniffer;
import com.influora.common.ProofObjectKeys;
import com.influora.common.TextSanitizer;
import com.influora.common.Ulids;
import com.influora.config.R2Properties;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.DeliverableMetric;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DeliverableMetricRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.security.MalwareScanService;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.DeliverableActions;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.DeliverableFileResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.DeliverableListItem;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.DeliverableStatusResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.MarkPostedRequest;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.MarkPostedResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.MetricsPayload;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.MetricsReportRequest;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.MetricsReportResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.ProofUploadResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.SubmitRequest;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.SubmitResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.UploadResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Creator deliverable upload + status (Week 3 P0, 09_CREATOR_DELIVERABLES_SPEC.md §4.3). Identity
 * always from {@link CreatorContextService} — never trust a path-param creator id.
 *
 * <p>Upload hardening (Task #40): streams to R2 (M-19-3), returns time-limited presigned GETs
 * (M-19-4), binds proof screenshot keys to the authenticated creator/deliverable (M-24-1).
 */
@Service
public class CreatorDeliverableService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_MIME_PREFIXES = Set.of("image/", "video/");
    private static final long MAX_TOTAL_BYTES = 1_073_741_824L; // 1 GB per deliverable upload batch
    private static final long MAX_PROOF_BYTES = 10_485_760L; // 10 MB proof screenshots

    // --- DPF-3 live post URL guard (SSRF + stored-XSS) -----------------------------------
    private static final int MAX_POST_URL_LENGTH = 500;
    private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Set<String> ALLOWED_POST_URL_HOSTS =
            Set.of(
                    "instagram.com",
                    "youtube.com",
                    "youtu.be",
                    "tiktok.com",
                    "facebook.com",
                    "fb.watch",
                    "twitter.com",
                    "x.com",
                    "threads.net");

    private final CreatorContextService creatorContext;
    private final CollaborationRepository collaborationRepository;
    private final DeliverableRepository deliverableRepository;
    private final DeliverableMetricRepository deliverableMetricRepository;
    private final R2StorageService r2StorageService;
    private final R2Properties r2Properties;
    private final MalwareScanService malwareScanService;

    public CreatorDeliverableService(
            CreatorContextService creatorContext,
            CollaborationRepository collaborationRepository,
            DeliverableRepository deliverableRepository,
            DeliverableMetricRepository deliverableMetricRepository,
            R2StorageService r2StorageService,
            R2Properties r2Properties,
            MalwareScanService malwareScanService) {
        this.creatorContext = creatorContext;
        this.collaborationRepository = collaborationRepository;
        this.deliverableRepository = deliverableRepository;
        this.deliverableMetricRepository = deliverableMetricRepository;
        this.r2StorageService = r2StorageService;
        this.r2Properties = r2Properties;
        this.malwareScanService = malwareScanService;
    }

    @Transactional
    public UploadResponse uploadContent(
            AuthPrincipal principal,
            String deliverableId,
            List<MultipartFile> files,
            MultipartFile thumbnail,
            String caption,
            List<String> hashtags,
            String creatorNotes) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        Deliverable deliverable = requireOwnedDeliverable(principal, deliverableId);

        if (!canUploadNewVersion(deliverable.getStatus())) {
            throw new ApiException(
                    "INVALID_STATE",
                    "Cannot upload content in current deliverable state",
                    HttpStatus.CONFLICT);
        }
        if (files == null || files.isEmpty()) {
            throw new ApiException("INVALID_FILE", "At least one content file is required", HttpStatus.BAD_REQUEST);
        }
        if (!r2StorageService.isAvailable()) {
            throw new ApiException(
                    "STORAGE_UNAVAILABLE",
                    "File storage is not configured",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        // Enforce size limits before any R2 write (M-19-3 early reject).
        long totalBytes = files.stream().mapToLong(MultipartFile::getSize).sum();
        if (thumbnail != null && !thumbnail.isEmpty()) {
            totalBytes += thumbnail.getSize();
        }
        if (totalBytes > MAX_TOTAL_BYTES) {
            throw new ApiException(
                    "FILE_TOO_LARGE",
                    "Total upload size exceeds 1 GB limit",
                    HttpStatus.BAD_REQUEST);
        }

        int nextVersion = deliverable.getVersionNumber() + 1;
        List<StoredFile> uploaded = new ArrayList<>();
        String thumbnailKey = uploadOptionalThumbnail(deliverable, nextVersion, thumbnail);

        for (MultipartFile file : files) {
            malwareScanService.requireClean(file, "deliverable-content");
            uploaded.add(uploadFile(deliverable, nextVersion, file, thumbnailKey));
        }

        String filesJson = writeFilesJson(uploaded);
        deliverable.applyUpload(
                nextVersion,
                filesJson,
                TextSanitizer.sanitizePlainText(caption),
                JsonLists.toJson(TextSanitizer.sanitizeHashtags(hashtags)),
                TextSanitizer.sanitizePlainText(creatorNotes));
        deliverableRepository.save(deliverable);

        String versionId = Ulids.newUlid();
        return new UploadResponse(
                versionId,
                nextVersion,
                uploaded.stream().map(this::toFileResponse).toList(),
                DeliverableStatus.DRAFT);
    }

    /**
     * §4.7 proof screenshot upload — issues an ownership-bound R2 key under {@code
     * proof/{creatorUserId}/{deliverableId}/…} (Kabir M-24-1).
     */
    @Transactional
    public ProofUploadResponse uploadProof(
            AuthPrincipal principal, String deliverableId, MultipartFile screenshot) {
        creatorContext.requireCreatorProfile(principal);
        Deliverable deliverable = requireOwnedDeliverable(principal, deliverableId);

        if (screenshot == null || screenshot.isEmpty()) {
            throw new ApiException("INVALID_FILE", "Proof screenshot is required", HttpStatus.BAD_REQUEST);
        }
        if (!r2StorageService.isAvailable()) {
            throw new ApiException(
                    "STORAGE_UNAVAILABLE",
                    "File storage is not configured",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (screenshot.getSize() > MAX_PROOF_BYTES) {
            throw new ApiException(
                    "FILE_TOO_LARGE",
                    "Proof screenshot exceeds maximum size of " + MAX_PROOF_BYTES + " bytes",
                    HttpStatus.BAD_REQUEST);
        }

        validateImageMime(screenshot);
        malwareScanService.requireClean(screenshot, "deliverable-proof");

        String objectId = Ulids.newUlid();
        String ext = extensionForContentType(screenshot.getContentType());
        String key =
                ProofObjectKeys.build(principal.getUserId(), deliverable.getId(), objectId, ext);
        String contentType =
                screenshot.getContentType() != null ? screenshot.getContentType() : "image/png";
        streamToR2(key, screenshot, contentType, MAX_PROOF_BYTES);

        var presigned = r2StorageService.presignGet(key);
        return new ProofUploadResponse(
                objectId, key, presigned.uploadUrl(), Instant.now(), presigned.expiresAt());
    }

    @Transactional
    public SubmitResponse submitForReview(
            AuthPrincipal principal, String deliverableId, SubmitRequest request) {
        creatorContext.requireCreatorProfile(principal);
        Deliverable deliverable = requireOwnedDeliverable(principal, deliverableId);

        if (!canSubmit(deliverable.getStatus())) {
            throw new ApiException(
                    "INVALID_STATE",
                    "Cannot submit deliverable in current state",
                    HttpStatus.CONFLICT);
        }
        if (!hasUploadedFiles(deliverable)) {
            throw new ApiException(
                    "NO_CONTENT",
                    "Upload at least one file before submitting",
                    HttpStatus.BAD_REQUEST);
        }

        SubmitRequest body = request != null ? request : new SubmitRequest(null, null, null);
        DeliverableStatus newStatus =
                deliverable.getRevisionCount() > 0
                        ? DeliverableStatus.RESUBMITTED
                        : DeliverableStatus.SUBMITTED;
        deliverable.applySubmit(
                TextSanitizer.sanitizePlainText(body.finalCaption()),
                JsonLists.toJson(TextSanitizer.sanitizeHashtags(body.hashtags())),
                TextSanitizer.sanitizePlainText(body.notes()),
                newStatus);
        deliverableRepository.save(deliverable);

        String message =
                newStatus == DeliverableStatus.RESUBMITTED
                        ? "Resubmitted for brand review"
                        : "Submitted for brand review";
        return new SubmitResponse(deliverable.getId(), newStatus, message);
    }

    /**
     * Creator reports self-declared performance metrics (09_CREATOR_DELIVERABLES_SPEC.md §4.6).
     * Persists to {@link DeliverableMetric} (milestone-keyed lean schema) and transitions
     * {@code APPROVED}/{@code POSTED} → {@code METRICS_REPORTED}.
     */
    @Transactional
    public MetricsReportResponse reportMetrics(
            AuthPrincipal principal, String deliverableId, MetricsReportRequest request) {
        creatorContext.requireCreatorProfile(principal);
        Deliverable deliverable = requireOwnedDeliverable(principal, deliverableId);

        if (!canReportMetrics(deliverable.getStatus())) {
            throw new ApiException(
                    "INVALID_STATE",
                    "Metrics can only be reported once the deliverable is approved or posted",
                    HttpStatus.CONFLICT);
        }

        if (request == null || request.metrics() == null) {
            throw new ApiException(
                    "INVALID_REQUEST", "metrics payload is required", HttpStatus.BAD_REQUEST);
        }

        String milestoneId = deliverable.getMilestoneId();
        if (milestoneId == null || milestoneId.isBlank()) {
            throw new ApiException(
                    "MILESTONE_NOT_LINKED",
                    "Deliverable is not linked to a payment milestone",
                    HttpStatus.CONFLICT);
        }

        MetricsPayload metrics = request.metrics();
        validateNonNegativeMetrics(metrics);

        long reach = intOrZero(metrics.reach());
        long impressions = metrics.impressions() != null ? metrics.impressions() : intOrZero(metrics.views());
        long engagements =
                intOrZero(metrics.likes())
                        + intOrZero(metrics.comments())
                        + intOrZero(metrics.shares())
                        + intOrZero(metrics.saves());

        String proofKey = firstNonBlank(request.proofScreenshots());
        if (proofKey != null) {
            proofKey = requireOwnedProofKey(proofKey, principal.getUserId(), deliverable.getId());
        }

        DeliverableMetric metric =
                deliverableMetricRepository
                        .findByMilestoneId(milestoneId)
                        .orElseGet(
                                () ->
                                        DeliverableMetric.builder()
                                                .id(Ulids.newUlid())
                                                .milestoneId(milestoneId)
                                                .collaborationId(deliverable.getCollaborationId())
                                                .reportedByCreatorId(principal.getUserId())
                                                .build());

        metric.applyReport(
                reach > 0 ? reach : null,
                impressions > 0 ? impressions : null,
                engagements > 0 ? engagements : null,
                deliverable.getPostUrl(),
                proofKey,
                principal.getUserId());
        deliverableMetricRepository.save(metric);

        deliverable.applyMetricsReport();
        deliverableRepository.save(deliverable);

        Double engagementRate = calculateEngagementRate(metrics);
        return new MetricsReportResponse(
                deliverable.getId(),
                DeliverableStatus.METRICS_REPORTED,
                metrics,
                engagementRate,
                "PENDING",
                "Metrics submitted. They will be verified.");
    }

    /**
     * DPF-3 — creator marks an approved deliverable as posted, supplying the live post URL.
     * Only legal from {@link DeliverableStatus#APPROVED} (mirrors the same
     * upload/submit/report-metrics status-gate discipline as the rest of this service).
     */
    @Transactional
    public MarkPostedResponse markPosted(
            AuthPrincipal principal, String deliverableId, MarkPostedRequest request) {
        creatorContext.requireCreatorProfile(principal);
        Deliverable deliverable = requireOwnedDeliverable(principal, deliverableId);

        if (deliverable.getStatus() != DeliverableStatus.APPROVED) {
            throw new ApiException(
                    "INVALID_STATE",
                    "Deliverable must be approved before it can be marked as posted",
                    HttpStatus.CONFLICT);
        }

        String livePostUrl = request != null ? request.livePostUrl() : null;
        if (livePostUrl == null || livePostUrl.isBlank()) {
            throw new ApiException(
                    "INVALID_REQUEST", "live_post_url is required", HttpStatus.BAD_REQUEST);
        }
        livePostUrl = livePostUrl.trim();
        validatePlatformPostUrl(livePostUrl);

        deliverable.applyMarkPosted(livePostUrl);
        deliverableRepository.save(deliverable);

        return new MarkPostedResponse(
                deliverable.getId(),
                deliverable.getStatus(),
                deliverable.getPostUrl(),
                deliverable.getPostedAt());
    }

    /**
     * Validates a creator-submitted "I posted it live" URL (DPF-3). This value is persisted
     * verbatim and later rendered client-side as an opaque link/text — it must NEVER be
     * fetched, resolved, or followed server-side (SSRF surface) and must never contain raw
     * markup/attribute-breakout characters that could execute if rendered without further
     * escaping (stored XSS surface). Percent-encoded sequences (e.g. {@code %3Cscript%3E})
     * are accepted as inert opaque text — the frontend contract is to never URL-decode
     * {@code postUrl} before render; decoding it would turn this into live XSS.
     *
     * <p>Layered checks, in order: length cap, https-only scheme, then host-based SSRF
     * guards (localhost/loopback/private/link-local/cloud-metadata + localhost-disguised
     * subdomains), then a known-platform host allow-list (also defeats lookalike hosts like
     * {@code instagram.com.attacker.com} and path-spoofed hosts like
     * {@code attacker.com/instagram.com/...}), then a raw-character XSS guard.
     */
    private void validatePlatformPostUrl(String rawUrl) {
        if (rawUrl.length() > MAX_POST_URL_LENGTH) {
            throw invalidPostUrl();
        }

        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw invalidPostUrl();
        }

        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw invalidPostUrl();
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw invalidPostUrl();
        }
        host = host.toLowerCase(Locale.ROOT);

        if (host.equals("localhost") || host.endsWith(".localhost")) {
            throw invalidPostUrl();
        }
        if (isBlockedIpLiteralHost(host)) {
            throw invalidPostUrl();
        }

        String finalHost = host;
        boolean allowedHost =
                ALLOWED_POST_URL_HOSTS.stream()
                        .anyMatch(base -> finalHost.equals(base) || finalHost.endsWith("." + base));
        if (!allowedHost) {
            throw invalidPostUrl();
        }

        if (containsRawXssChars(rawUrl)) {
            throw invalidPostUrl();
        }
    }

    /** SSRF guard: blocks loopback/private/link-local/cloud-metadata IP-literal hosts. */
    private boolean isBlockedIpLiteralHost(String host) {
        String candidate = host;
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1); // strip IPv6 [ ]
        }
        boolean looksLikeIp = IPV4_LITERAL.matcher(candidate).matches() || candidate.contains(":");
        if (!looksLikeIp) {
            return false; // hostname, not an IP literal — the platform allow-list handles it
        }
        try {
            InetAddress addr = InetAddress.getByName(candidate);
            return addr.isLoopbackAddress()
                    || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress()
                    || addr.isAnyLocalAddress();
        } catch (UnknownHostException e) {
            return true; // fail closed on unparsable IP literal
        }
    }

    /** Stored-XSS guard: rejects literal markup/attribute-breakout characters, unencoded. */
    private boolean containsRawXssChars(String url) {
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '<' || c == '>' || c == '"' || c == '\'') {
                return true;
            }
        }
        return false;
    }

    private ApiException invalidPostUrl() {
        return new ApiException(
                "INVALID_POST_URL", "live_post_url is not a valid post URL", HttpStatus.BAD_REQUEST);
    }

    @Transactional(readOnly = true)
    public DeliverableStatusResponse getStatus(AuthPrincipal principal, String deliverableId) {
        creatorContext.requireCreatorProfile(principal);
        Deliverable deliverable = requireOwnedDeliverable(principal, deliverableId);
        return toStatusResponse(deliverable);
    }

    /**
     * Deal-room deliverable picker — scoped via {@link CreatorContextService} + collaboration
     * ownership (mirrors {@code DealService#requireCreatorCollaboration}).
     */
    @Transactional(readOnly = true)
    public List<DeliverableListItem> listForCollaboration(
            AuthPrincipal principal, String collaborationId) {
        creatorContext.requireCreatorProfile(principal);
        if (collaborationId == null || collaborationId.isBlank()) {
            throw new ApiException(
                    "INVALID_REQUEST", "collaboration_id is required", HttpStatus.BAD_REQUEST);
        }
        requireOwnedCollaboration(principal, collaborationId);
        return deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(collaborationId).stream()
                .map(CreatorDeliverableService::toListItem)
                .toList();
    }

    private void requireOwnedCollaboration(AuthPrincipal principal, String collaborationId) {
        collaborationRepository
                .findByIdAndCreatorId(collaborationId, principal.getUserId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "DEAL_NOT_FOUND", "Deal not found", HttpStatus.NOT_FOUND));
    }

    private Deliverable requireOwnedDeliverable(AuthPrincipal principal, String deliverableId) {
        return deliverableRepository
                .findByIdAndCreatorUserId(deliverableId, principal.getUserId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "DELIVERABLE_NOT_FOUND",
                                        "Deliverable not found",
                                        HttpStatus.NOT_FOUND));
    }

    /** Kabir M-24-1 — reject foreign / absolute / traversal proof keys. */
    static String requireOwnedProofKey(String key, String creatorUserId, String deliverableId) {
        if (!ProofObjectKeys.isOwnedProofKey(key, creatorUserId, deliverableId)) {
            throw new ApiException(
                    "INVALID_PROOF_KEY",
                    "Proof screenshot key is not owned by this creator for this deliverable",
                    HttpStatus.BAD_REQUEST);
        }
        return key.trim();
    }

    private static boolean canUploadNewVersion(DeliverableStatus status) {
        return status == DeliverableStatus.PENDING
                || status == DeliverableStatus.DRAFT
                || status == DeliverableStatus.REVISION_REQUESTED;
    }

    private static boolean canSubmit(DeliverableStatus status) {
        return status == DeliverableStatus.DRAFT || status == DeliverableStatus.REVISION_REQUESTED;
    }

    private static boolean canReportMetrics(DeliverableStatus status) {
        return status == DeliverableStatus.APPROVED
                || status == DeliverableStatus.POSTED
                || status == DeliverableStatus.METRICS_REPORTED;
    }

    private static void validateNonNegativeMetrics(MetricsPayload metrics) {
        if (isNegative(metrics.likes())
                || isNegative(metrics.comments())
                || isNegative(metrics.shares())
                || isNegative(metrics.views())
                || isNegative(metrics.reach())
                || isNegative(metrics.impressions())
                || isNegative(metrics.saves())) {
            throw new ApiException(
                    "INVALID_METRIC_VALUE",
                    "Reported metrics must be zero or positive",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private static boolean isNegative(Integer value) {
        return value != null && value < 0;
    }

    private static int intOrZero(Integer value) {
        return value != null ? value : 0;
    }

    private static String firstNonBlank(List<String> values) {
        if (values == null) {
            return null;
        }
        return values.stream().filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
    }

    /** Spec §5.2: (likes + comments) / reach × 100 when reach is available. */
    private static Double calculateEngagementRate(MetricsPayload metrics) {
        int reach = intOrZero(metrics.reach());
        if (reach <= 0) {
            return null;
        }
        double engaged = intOrZero(metrics.likes()) + intOrZero(metrics.comments());
        return Math.round(engaged / (double) reach * 1000.0) / 10.0;
    }

    private static boolean hasUploadedFiles(Deliverable deliverable) {
        return !readFilesJson(deliverable.getFilesJson()).isEmpty();
    }

    private String uploadOptionalThumbnail(Deliverable deliverable, int version, MultipartFile thumbnail) {
        if (thumbnail == null || thumbnail.isEmpty()) {
            return null;
        }
        validateMime(thumbnail);
        malwareScanService.requireClean(thumbnail, "deliverable-thumbnail");
        if (thumbnail.getSize() > r2Properties.getMaxVideoBytes()) {
            throw new ApiException(
                    "FILE_TOO_LARGE",
                    "Thumbnail exceeds maximum file size",
                    HttpStatus.BAD_REQUEST);
        }
        String key =
                String.format(
                        "deliverables/%s/v%d/thumb-%s",
                        deliverable.getId(), version, Ulids.newUlid());
        String contentType = thumbnail.getContentType() != null ? thumbnail.getContentType() : "image/jpeg";
        streamToR2(key, thumbnail, contentType, r2Properties.getMaxVideoBytes());
        return key;
    }

    private StoredFile uploadFile(
            Deliverable deliverable, int version, MultipartFile file, String sharedThumbnailKey) {
        if (file == null || file.isEmpty()) {
            throw new ApiException("INVALID_FILE", "Empty file in upload batch", HttpStatus.BAD_REQUEST);
        }
        validateMime(file);
        if (file.getSize() > r2Properties.getMaxVideoBytes()) {
            throw new ApiException(
                    "FILE_TOO_LARGE",
                    "File exceeds maximum size of " + r2Properties.getMaxVideoBytes() + " bytes",
                    HttpStatus.BAD_REQUEST);
        }

        String fileType = file.getContentType() != null && file.getContentType().startsWith("video/")
                ? "VIDEO"
                : "IMAGE";
        String safeName = sanitizeFileName(file.getOriginalFilename());
        String key =
                String.format(
                        "deliverables/%s/v%d/%s-%s",
                        deliverable.getId(), version, Ulids.newUlid(), safeName);

        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        String md5 = streamToR2(key, file, contentType, r2Properties.getMaxVideoBytes());

        // Persist R2 object keys (not public URLs). API responses rematerialize via presignGet.
        String thumbnailKey = "VIDEO".equals(fileType) ? sharedThumbnailKey : key;

        return new StoredFile(
                Ulids.newUlid(),
                fileType,
                safeName,
                key,
                thumbnailKey,
                file.getSize(),
                null,
                md5);
    }

    /**
     * Streams multipart bytes to R2 with a hard size cap (M-19-3). Returns MD5 hex computed while
     * streaming — never calls {@code MultipartFile#getBytes()}.
     */
    private String streamToR2(String key, MultipartFile file, String contentType, long maxBytes) {
        long size = file.getSize();
        if (size > maxBytes) {
            throw new ApiException(
                    "FILE_TOO_LARGE",
                    "File exceeds maximum size of " + maxBytes + " bytes",
                    HttpStatus.BAD_REQUEST);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (InputStream raw = file.getInputStream();
                    LimitedInputStream limited = new LimitedInputStream(raw, maxBytes);
                    DigestInputStream digest = new DigestInputStream(limited, md)) {
                r2StorageService.putStream(key, digest, size, contentType);
                return HexFormat.of().formatHex(md.digest());
            }
        } catch (IOException e) {
            throw new ApiException("UPLOAD_FAILED", "Failed to upload file", HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (NoSuchAlgorithmException e) {
            throw new ApiException("UPLOAD_FAILED", "Failed to hash file", HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (IllegalArgumentException e) {
            throw new ApiException("FILE_TOO_LARGE", e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    private static void validateMime(MultipartFile file) {
        String declared = file.getContentType();
        if (declared == null || ALLOWED_MIME_PREFIXES.stream().noneMatch(declared::startsWith)) {
            throw new ApiException(
                    "INVALID_FILE_TYPE",
                    "Only image and video files are allowed",
                    HttpStatus.BAD_REQUEST);
        }
        try {
            String sniffed = MediaMimeSniffer.detectMimeType(file.getInputStream());
            if (!MediaMimeSniffer.isAllowedMediaMime(sniffed)) {
                throw new ApiException(
                        "INVALID_FILE_TYPE",
                        "File content does not match an allowed image or video type",
                        HttpStatus.BAD_REQUEST);
            }
            if (!MediaMimeSniffer.mimeTypesCompatible(declared, sniffed)) {
                throw new ApiException(
                        "INVALID_FILE_TYPE",
                        "Declared content type does not match file content",
                        HttpStatus.BAD_REQUEST);
            }
        } catch (IOException e) {
            throw new ApiException(
                    "INVALID_FILE_TYPE", "Unable to read file content", HttpStatus.BAD_REQUEST);
        }
    }

    private static void validateImageMime(MultipartFile file) {
        String declared = file.getContentType();
        if (declared == null || !declared.startsWith("image/")) {
            throw new ApiException(
                    "INVALID_FILE_TYPE",
                    "Only image proof screenshots are allowed",
                    HttpStatus.BAD_REQUEST);
        }
        try {
            String sniffed = MediaMimeSniffer.detectMimeType(file.getInputStream());
            if (sniffed == null || !sniffed.startsWith("image/")) {
                throw new ApiException(
                        "INVALID_FILE_TYPE",
                        "File content does not match an allowed image type",
                        HttpStatus.BAD_REQUEST);
            }
            if (!MediaMimeSniffer.mimeTypesCompatible(declared, sniffed)) {
                throw new ApiException(
                        "INVALID_FILE_TYPE",
                        "Declared content type does not match file content",
                        HttpStatus.BAD_REQUEST);
            }
        } catch (IOException e) {
            throw new ApiException(
                    "INVALID_FILE_TYPE", "Unable to read file content", HttpStatus.BAD_REQUEST);
        }
    }

    private static String extensionForContentType(String contentType) {
        if (contentType == null) {
            return "png";
        }
        return switch (contentType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "png";
        };
    }

    private static String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) {
            return "upload.bin";
        }
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String writeFilesJson(List<StoredFile> files) {
        try {
            return MAPPER.writeValueAsString(files);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize deliverable files", e);
        }
    }

    private static List<StoredFile> readFilesJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<StoredFile>>() {});
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * M-19-4 — resolve a stored R2 object key (or legacy public URL) to a time-limited presigned
     * GET. Falls back to the stored value only when R2 is unavailable (dev without credentials).
     */
    private String resolveDownloadUrl(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        String key = toObjectKey(stored);
        if (key == null || !r2StorageService.isAvailable()) {
            return stored;
        }
        try {
            return r2StorageService.presignGet(key).uploadUrl();
        } catch (RuntimeException e) {
            return stored;
        }
    }

    private String toObjectKey(String stored) {
        if (!stored.startsWith("http://") && !stored.startsWith("https://")) {
            return stored;
        }
        String base = r2Properties.getPublicUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (stored.startsWith(base + "/")) {
            return stored.substring(base.length() + 1);
        }
        return null;
    }

    private DeliverableFileResponse toFileResponse(StoredFile file) {
        return new DeliverableFileResponse(
                file.id(),
                file.fileType(),
                file.fileName(),
                resolveDownloadUrl(file.url()),
                resolveDownloadUrl(file.thumbnailUrl()),
                file.fileSize(),
                file.durationSeconds());
    }

    /**
     * Package-visible (not {@code private}) so {@link DealService#listDeliverables} can reuse the
     * exact same {@code Deliverable} → {@code DeliverableListItem} mapping for the brand-side {@code
     * GET /deals/{dealId}/deliverables} list, instead of duplicating this logic.
     */
    static DeliverableListItem toListItem(Deliverable deliverable) {
        DeliverableStatus status = deliverable.getStatus();
        boolean completed =
                status == DeliverableStatus.APPROVED
                        || status == DeliverableStatus.VERIFIED
                        || status == DeliverableStatus.POSTED;
        String description =
                deliverable.getReviewNotes() != null && !deliverable.getReviewNotes().isBlank()
                        ? deliverable.getReviewNotes()
                        : "Version " + deliverable.getVersionNumber();
        Integer currentRevision =
                deliverable.getRevisionCount() > 0 ? deliverable.getRevisionCount() : null;
        Integer maxRevisions = deliverable.getRevisionCount() > 0 ? 2 : null;
        return new DeliverableListItem(
                deliverable.getId(),
                deliverable.getTitle(),
                description,
                status,
                completed,
                currentRevision,
                maxRevisions);
    }

    private DeliverableStatusResponse toStatusResponse(Deliverable deliverable) {
        List<StoredFile> files = readFilesJson(deliverable.getFilesJson());
        DeliverableStatus status = deliverable.getStatus();
        return new DeliverableStatusResponse(
                deliverable.getId(),
                deliverable.getCollaborationId(),
                deliverable.getType(),
                deliverable.getTitle(),
                status,
                deliverable.getDeadline(),
                deliverable.getVersionNumber(),
                deliverable.getRevisionCount(),
                files.stream().map(this::toFileResponse).toList(),
                deliverable.getCaption(),
                JsonLists.stringListFromJson(deliverable.getHashtagsJson()),
                deliverable.getCreatorNotes(),
                deliverable.getReviewNotes(),
                deliverable.getSubmittedAt(),
                deliverable.getReviewedAt(),
                new DeliverableActions(
                        canUploadNewVersion(status),
                        canSubmit(status) && !files.isEmpty(),
                        canReportMetrics(status)));
    }

    /**
     * JSON-serialized file row stored in {@link Deliverable#getFilesJson()}. {@code url} /
     * {@code thumbnailUrl} hold R2 object keys (M-19-4); API responses rematerialize via
     * {@link R2StorageService#presignGet}.
     */
    private record StoredFile(
            String id,
            String fileType,
            String fileName,
            String url,
            String thumbnailUrl,
            long fileSize,
            Integer durationSeconds,
            String md5Hash) {}
}
