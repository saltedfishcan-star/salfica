package com.example.atd.image;

import org.springframework.http.HttpStatus;

public class ImageProxyException extends RuntimeException {
    // 代理接口需要透出的 HTTP 状态码。
    private final HttpStatus status;

    public ImageProxyException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
