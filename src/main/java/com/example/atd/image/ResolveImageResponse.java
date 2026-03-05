package com.example.atd.image;

public record ResolveImageResponse(
        String status,
        String resolvedImageUrl,
        String proxyUrl,
        String reason,
        Integer selectedWidth,
        Integer selectedHeight,
        String qualityHint
) {
    // 成功结果：同时返回原图地址与代理地址。
    public static ResolveImageResponse ok(
            String resolvedImageUrl,
            String proxyUrl,
            Integer selectedWidth,
            Integer selectedHeight,
            String qualityHint
    ) {
        return new ResolveImageResponse("ok", resolvedImageUrl, proxyUrl, null, selectedWidth, selectedHeight, qualityHint);
    }

    // 失败结果：reason 由前端映射为可读提示。
    public static ResolveImageResponse failed(String reason) {
        return new ResolveImageResponse("failed", null, null, reason, null, null, null);
    }
}
