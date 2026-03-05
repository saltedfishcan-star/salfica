package com.example.atd.image;

import org.springframework.http.HttpStatus;

public class ImageProxyException extends RuntimeException {
    private final HttpStatus status;

    public ImageProxyException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}

