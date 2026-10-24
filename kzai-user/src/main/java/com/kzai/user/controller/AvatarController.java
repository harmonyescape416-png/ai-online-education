package com.kzai.user.controller;

import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.user.entity.UserProfileExt;
import com.kzai.user.mapper.UserProfileExtMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/**
 * M15.15 方案 A（用户批准）：头像上传与访问（本地存储 data/avatar/）。
 * 安全约束：仅本人上传；类型白名单 jpg/png/webp；大小 ≤2MB；文件名由服务端按 userId 生成；
 * 访问为公开 GET（头像非机密内容），URL 不含敏感信息；不落库敏感数据。
 */
@RestController
@RequestMapping("/users/me/avatar")
@RequiredArgsConstructor
@Slf4j
public class AvatarController {

    private final UserProfileExtMapper extMapper;

    @Value("${kzai.avatar.root:data/avatar}")
    private String rootPath;

    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp");
    private static final long MAX_SIZE = 2L * 1024 * 1024;

    /** 上传/更新本人头像（覆盖式：文件名固定 {userId}.{ext}） */
    @PostMapping
    public R<UserProfileExt> upload(@RequestParam("file") MultipartFile file) throws Exception {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new AuthException("未登录或 token 无效");
        }
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择头像文件");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BizException("头像文件不能超过 2MB");
        }
        String original = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "";
        String ext = original.contains(".") ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException("仅支持 jpg/png/webp 格式");
        }
        Path root = Paths.get(rootPath).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path target = root.resolve(userId + "." + ext).normalize();
        if (!target.startsWith(root)) {
            throw new BizException("非法路径");
        }
        try (var in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        // M15.15：带版本参数——覆盖式同 URL 的浏览器缓存破除，保存后顶栏/页面立即显示新头像
        String url = "/users/me/avatar/file/" + userId + "." + ext + "?v=" + System.currentTimeMillis();
        for (String e : ALLOWED_EXT) {
            if (!e.equals(ext)) {
                Files.deleteIfExists(root.resolve(userId + "." + e));
            }
        }
        UserProfileExt entity = extMapper.selectById(userId);
        if (entity == null) {
            entity = new UserProfileExt();
            entity.setUserId(userId);
            entity.setAvatarUrl(url);
            extMapper.insert(entity);
        } else {
            entity.setAvatarUrl(url);
            extMapper.updateById(entity);
        }
        log.info("[avatar] user {} uploaded avatar ({} bytes)", userId, file.getSize());
        return R.ok(entity);
    }

    /** 头像访问（公开 GET；头像非机密内容；URL 由扩展表 avatar_url 提供） */
    @GetMapping("/file/{fileName}")
    public ResponseEntity<FileSystemResource> file(@PathVariable String fileName) throws Exception {
        if (!fileName.matches("[0-9]+\\.(jpg|jpeg|png|webp)")) {
            throw new BizException("非法文件名");
        }
        Path root = Paths.get(rootPath).toAbsolutePath().normalize();
        Path target = root.resolve(fileName).normalize();
        if (!target.startsWith(root) || !Files.exists(target)) {
            throw new BizException("头像不存在");
        }
        String lower = fileName.toLowerCase();
        MediaType mt = lower.endsWith(".png") ? MediaType.IMAGE_PNG
                : lower.endsWith(".webp") ? MediaType.parseMediaType("image/webp")
                : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(mt).body(new FileSystemResource(target));
    }
}
