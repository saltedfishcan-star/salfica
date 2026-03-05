package com.example.atd.image;

public record ProxyImagePayload(byte[] body, String contentType) {
    // body 为图片二进制，contentType 用于原样透传响应头。
}
