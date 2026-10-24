package com.kzai.course.controller;

import com.kzai.common.constant.Constants;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import com.kzai.course.entity.CourseResource;
import com.kzai.course.service.ResourceService;
import com.kzai.course.vo.ResourceVO;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 文件接口（M3 上传 / 受控下载，M5 增加 HTTP Range 支持）。
 * <p>
 * 上传只落服务端受控目录；下载按"免费课程 / 已购权益 / 上传人 / 管理员"鉴权，
 * 不暴露本地绝对路径，付费视频与资料无法绕过权益校验直接访问。
 * <p>
 * Range：视频播放必须支持断点续传，合法 Range 返回 206 Partial Content，
 * 非法 Range（起点越界、起止倒置）返回 416 Range Not Satisfiable。
 */
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileController {

    private final ResourceService resourceService;

    @PostMapping("/upload")
    @RequireRoles({Constants.ROLE_TEACHER, Constants.ROLE_ADMIN})
    public R<ResourceVO> upload(@RequestParam("file") MultipartFile file) {
        CourseResource resource = resourceService.upload(file);
        return R.ok(ResourceService.toVO(resource));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(
            @PathVariable("id") Long id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        ResourceService.Downloadable downloadable = resourceService.requireDownload(id);
        CourseResource resource = downloadable.resource();
        Path path = downloadable.path();
        try {
            long size = Files.size(path);
            boolean video = Integer.valueOf(Constants.RES_KIND_VIDEO).equals(resource.getKind());
            String encoded = URLEncoder.encode(
                    resource.getFileName() == null ? "file" : resource.getFileName(),
                    StandardCharsets.UTF_8);
            MediaType mediaType = resource.getContentType() == null
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(resource.getContentType());
            String disposition = (video ? "inline; " : "attachment; ")
                    + "filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded;

            // 无 Range：整段返回，并声明支持字节范围
            if (rangeHeader == null || rangeHeader.isBlank()) {
                InputStream in = Files.newInputStream(path);
                return ResponseEntity.ok()
                        .contentType(mediaType)
                        .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(size))
                        .body(new InputStreamResource(in));
            }

            List<HttpRange> ranges;
            try {
                ranges = HttpRange.parseRanges(rangeHeader);
            } catch (IllegalArgumentException e) {
                return rangeNotSatisfiable(size);
            }
            if (ranges == null || ranges.isEmpty()) {
                return rangeNotSatisfiable(size);
            }
            HttpRange range = ranges.get(0);
            long start = range.getRangeStart(size);
            long end = range.getRangeEnd(size);
            // 起点越界或起止倒置：416
            if (start >= size || end < start) {
                return rangeNotSatisfiable(size);
            }
            long length = end - start + 1;
            InputStream in = rangedStream(Files.newInputStream(path), start, length);
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size)
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(length))
                    .body(new InputStreamResource(in));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private static ResponseEntity<InputStreamResource> rangeNotSatisfiable(long size) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + size)
                .build();
    }

    /** 只读取 [start, start+length) 区间的流，避免把整个文件读入内存 */
    private static InputStream rangedStream(InputStream source, long start, long length) throws IOException {
        long skipped = 0;
        while (skipped < start) {
            long n = source.skip(start - skipped);
            if (n <= 0) {
                throw new IOException("无法定位到请求的字节位置");
            }
            skipped += n;
        }
        return new BoundedInputStream(source, length);
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        private BoundedInputStream(InputStream in, long length) {
            super(in);
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = super.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int toRead = (int) Math.min(len, remaining);
            int n = super.read(b, off, toRead);
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }
    }
}
