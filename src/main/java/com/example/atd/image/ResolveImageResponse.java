package com.example.atd.image;

public record ResolveImageResponse(
        String status,
        String resolvedImageUrl,
        String proxyUrl,
        String reason
) {
    public static ResolveImageResponse ok(String resolvedImageUrl, String proxyUrl) {
        return new ResolveImageResponse("ok", resolvedImageUrl, proxyUrl, null);
    }

    public static ResolveImageResponse failed(String reason) {
        return new ResolveImageResponse("failed", null, null, reason);
    }
}

