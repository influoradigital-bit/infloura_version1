package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.R2Properties;
import com.influora.domain.enums.UserType;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.FileUploadRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.security.MalwareScanService;
import com.influora.web.dto.upload.UploadDtos.UploadResponse;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

/**
 * N2 (Wave 6) — POST /uploads had no route at all before this pass. Covers UploadService's
 * validation gates (empty file, storage unavailable, oversized, mismatched/unsupported MIME), the
 * malware-scan gate firing before anything reaches R2, and the happy path persisting a D6
 * `file_uploads` row.
 */
@ExtendWith(MockitoExtension.class)
class UploadServiceTest {

    private static final byte[] MINIMAL_PNG = {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x00
    };
    private static final byte[] MINIMAL_PDF = "%PDF-1.4 fake".getBytes();

    @Mock private R2StorageService r2StorageService;
    @Mock private R2Properties r2Properties;
    @Mock private MalwareScanService malwareScanService;
    @Mock private FileUploadRepository fileUploadRepository;
    @Mock private AuthPrincipal principal;

    private UploadService service;

    @BeforeEach
    void setUp() {
        service = new UploadService(r2StorageService, r2Properties, malwareScanService, fileUploadRepository);
    }

    @Test
    @DisplayName("rejects a null/empty file before touching storage")
    void testRejectsEmptyFile() {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);
        ApiException ex = assertThrows(ApiException.class, () -> service.upload(principal, empty));
        assertEquals("INVALID_FILE", ex.getCode());
    }

    @Test
    @DisplayName("rejects when R2 storage is not configured")
    void testRejectsWhenStorageUnavailable() {
        when(r2StorageService.isAvailable()).thenReturn(false);
        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", MINIMAL_PNG);

        ApiException ex = assertThrows(ApiException.class, () -> service.upload(principal, file));
        assertEquals("STORAGE_UNAVAILABLE", ex.getCode());
    }

    @Test
    @DisplayName("rejects a file over the size cap")
    void testRejectsOversizedFile() {
        when(r2StorageService.isAvailable()).thenReturn(true);
        byte[] tooBig = new byte[(int) UploadService.MAX_BYTES + 1];
        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", tooBig);

        ApiException ex = assertThrows(ApiException.class, () -> service.upload(principal, file));
        assertEquals("FILE_TOO_LARGE", ex.getCode());
    }

    @Test
    @DisplayName("rejects declared content-type that doesn't match sniffed bytes")
    void testRejectsMismatchedDeclaredMime() {
        when(r2StorageService.isAvailable()).thenReturn(true);
        // Declares image/png but the bytes are PDF magic — mismatch must be rejected.
        MockMultipartFile file = new MockMultipartFile("file", "fake.png", "image/png", MINIMAL_PDF);

        ApiException ex = assertThrows(ApiException.class, () -> service.upload(principal, file));
        assertEquals("INVALID_FILE_TYPE", ex.getCode());
    }

    @Test
    @DisplayName("rejects content that sniffs to neither an image nor a PDF")
    void testRejectsUnsupportedFileType() {
        when(r2StorageService.isAvailable()).thenReturn(true);
        MockMultipartFile file =
                new MockMultipartFile("file", "evil.exe", "application/x-msdownload", new byte[] {1, 2, 3, 4});

        ApiException ex = assertThrows(ApiException.class, () -> service.upload(principal, file));
        assertEquals("INVALID_FILE_TYPE", ex.getCode());
    }

    @Test
    @DisplayName("malware scan gate rejects before any R2 write")
    void testMalwareScanRejectsBeforeUpload() {
        when(r2StorageService.isAvailable()).thenReturn(true);
        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", MINIMAL_PNG);
        org.mockito.Mockito.doThrow(
                        new ApiException(
                                "MALWARE_DETECTED", "infected", org.springframework.http.HttpStatus.BAD_REQUEST))
                .when(malwareScanService)
                .requireClean(any(), eq("generic-upload"));

        ApiException ex = assertThrows(ApiException.class, () -> service.upload(principal, file));
        assertEquals("MALWARE_DETECTED", ex.getCode());
        verify(r2StorageService, never()).putStream(anyString(), any(InputStream.class), anyLong(), anyString());
    }

    @Test
    @DisplayName("happy path: valid PNG streams to R2 and persists a file_uploads row")
    void testUploadPngPersistsAndReturnsUrl() {
        when(principal.getUserType()).thenReturn(UserType.BRAND);
        when(principal.getUserId()).thenReturn("01HBRANDUSER1234AB");
        when(r2StorageService.isAvailable()).thenReturn(true);
        when(r2StorageService.putStream(anyString(), any(InputStream.class), eq((long) MINIMAL_PNG.length), eq("image/png")))
                .thenReturn("etag-123");
        when(r2StorageService.publicUrl(anyString())).thenAnswer(inv -> "https://r2.influora.com/" + inv.getArgument(0));
        when(r2Properties.getBucketName()).thenReturn("influora-dev");

        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", MINIMAL_PNG);

        UploadResponse response = service.upload(principal, file);

        assertEquals(true, response.url().startsWith("https://r2.influora.com/uploads/brand/01HBRANDUSER1234AB/"));
        assertEquals(true, response.key().startsWith("uploads/brand/01HBRANDUSER1234AB/"));
        assertEquals(true, response.key().endsWith(".png"));
        verify(fileUploadRepository).save(any());
        verify(malwareScanService).requireClean(any(), eq("generic-upload"));
    }

    @Test
    @DisplayName("happy path: valid PDF (GST/PAN doc) is accepted")
    void testUploadPdfAccepted() {
        when(principal.getUserType()).thenReturn(UserType.BRAND);
        when(principal.getUserId()).thenReturn("01HBRANDUSER1234AB");
        when(r2StorageService.isAvailable()).thenReturn(true);
        when(r2StorageService.putStream(
                        anyString(), any(InputStream.class), eq((long) MINIMAL_PDF.length), eq("application/pdf")))
                .thenReturn("etag-456");
        when(r2StorageService.publicUrl(anyString())).thenAnswer(inv -> "https://r2.influora.com/" + inv.getArgument(0));
        when(r2Properties.getBucketName()).thenReturn("influora-dev");

        MockMultipartFile file =
                new MockMultipartFile("file", "gstin.pdf", "application/pdf", MINIMAL_PDF);

        UploadResponse response = service.upload(principal, file);

        assertEquals(true, response.key().endsWith(".pdf"));
        verify(fileUploadRepository).save(any());
    }
}
