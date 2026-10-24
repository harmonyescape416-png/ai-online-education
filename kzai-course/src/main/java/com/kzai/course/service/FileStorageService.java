package com.kzai.course.service;

import com.kzai.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 文件存储（M3）：只保存在服务端受控目录，返回相对路径。
 * <p>
 * 不做任何"假链接"：文件必须真实落盘，访问必须走 /files/{id}/download 鉴权。
 */
@Slf4j
@Service
public class FileStorageService {

    /** 允许的文件扩展名白名单（非法类型直接拒绝） */
    private static final Set<String> ALLOWED_EXT = Set.of(
            "jpg", "jpeg", "png", "gif", "webp",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "zip",
            "md", "markdown", "mp4");

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Path root;
    private final long maxSize;

    public FileStorageService(@Value("${kzai.file.root:data/upload}") String rootPath,
                              @Value("${kzai.file.max-size:20971520}") long maxSize) {
        this.root = Paths.get(rootPath).toAbsolutePath().normalize();
        this.maxSize = maxSize;
    }

    /** 存储文件，返回相对路径（不暴露服务器绝对路径） */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件不能为空");
        }
        if (file.getSize() > maxSize) {
            throw new BizException("文件大小超过限制（最大 " + (maxSize / 1024 / 1024) + " MB）");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extensionOf(original);
        if (ext.isEmpty() || !ALLOWED_EXT.contains(ext)) {
            throw new BizException("不支持的文件类型：" + (ext.isEmpty() ? "无扩展名" : ext));
        }
        try {
            Path dir = root.resolve(LocalDate.now().format(DATE_DIR));
            Files.createDirectories(dir);
            String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            Path target = dir.resolve(storedName);
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return root.relativize(target).toString();
        } catch (IOException e) {
            log.error("[file] store failed: {}", e.getMessage());
            throw new BizException("文件保存失败，请稍后重试");
        }
    }

    /** 解析为真实文件路径；越界访问直接拒绝 */
    public Path resolve(String relativePath) {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new BizException("非法文件路径");
        }
        return target;
    }

    public static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
