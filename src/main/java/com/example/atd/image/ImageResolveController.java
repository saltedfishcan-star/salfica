package com.example.atd.image;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/image")
public class ImageResolveController {
    private final ImageResolveService imageResolveService;

    public ImageResolveController(ImageResolveService imageResolveService) {
        this.imageResolveService = imageResolveService;
    }

    /**
     * 解析图片地址。
     * 输入可以是直链或网页地址，服务会尝试返回可访问的图片代理地址。
     */
    @PostMapping("/resolve")
    public ResolveImageResponse resolve(@RequestBody(required = false) ResolveImageRequest request) {
        if (request == null || request.url() == null) {
            return ResolveImageResponse.failed("invalid_url");
        }
        return imageResolveService.resolve(request.url());
    }

    /**
     * 代理拉取图片二进制，统一规避前端跨域限制。
     */
    @GetMapping("/proxy")
    public ResponseEntity<byte[]> proxy(@RequestParam("target") String target) {
        ProxyImagePayload payload = imageResolveService.proxyImage(target);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES))
                .body(payload.body());
    }

    /**
     * 统一返回代理接口错误码与错误原因。
     */
    @ExceptionHandler(ImageProxyException.class)
    public ResponseEntity<Map<String, String>> handleProxyError(ImageProxyException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(Map.of("error", exception.getMessage()));
    }
}
