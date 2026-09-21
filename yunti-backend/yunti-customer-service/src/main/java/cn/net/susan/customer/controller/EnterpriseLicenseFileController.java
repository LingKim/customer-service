package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.customer.security.JwtTokenParser;
import cn.net.susan.customer.service.EnterpriseLicenseFileService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * 企业营业执照上传、预览与内部归属校验接口。
 */
@RestController
@RequestMapping("/api/customer")
public class EnterpriseLicenseFileController {

    private final EnterpriseLicenseFileService fileService;
    private final JwtTokenParser jwtTokenParser;

    public EnterpriseLicenseFileController(
            EnterpriseLicenseFileService fileService,
            JwtTokenParser jwtTokenParser
    ) {
        this.fileService = fileService;
        this.jwtTokenParser = jwtTokenParser;
    }

    @PostMapping(value = "/files/enterprise/license", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<EnterpriseLicenseFileService.UploadResult> uploadLicense(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam("file") MultipartFile file
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(fileService.uploadLicense(user, file));
    }

    @GetMapping("/files/enterprise/{fileId}")
    public ResponseEntity<Resource> downloadLicense(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable long fileId
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        EnterpriseLicenseFileService.FileContent content = fileService.loadLicense(user, fileId);
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(content.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(parseMediaType(content.mimeType()))
                .contentLength(content.fileSize())
                .body(content.resource());
    }

    @GetMapping("/internal/files/enterprise/{fileId}/ownership")
    public ApiResponse<Boolean> validateOwnership(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable long fileId
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(fileService.isEnterpriseLicenseOwnedBy(user, fileId));
    }

    private MediaType parseMediaType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
