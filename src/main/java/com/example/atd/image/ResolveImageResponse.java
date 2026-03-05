package com.example.atd.image;

public record ResolveImageResponse(
        String status,
        String resolvedImageUrl,
        String proxyUrl,
        String reason
) {
    // 成功结果：同时返回原图地址与代理地址。
    public static ResolveImageResponse ok(String resolvedImageUrl, String proxyUrl) {
        return new ResolveImageResponse("ok", resolvedImageUrl, proxyUrl, null);
    }

    // 失败结果：reason 由前端映射为可读提示。
    public static ResolveImageResponse failed(String reason) {
        return new ResolveImageResponse("failed", null, null, reason);
    }
}
