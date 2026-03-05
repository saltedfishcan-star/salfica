package com.example.atd.image;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ImageResolveService {
    // 网络与缓存策略：限制响应大小，避免慢请求和超大内容拖垮服务。
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int MAX_HTML_BYTES = 2 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final String USER_AGENT = "ATD-ImageResolver/1.0 (+https://example.local)";
    private static final int MIN_CANDIDATE_SIDE = 180;
    private static final int MIN_CANDIDATE_AREA = 50_000;
    private static final int HIGH_QUALITY_SIDE = 1_000;
    private static final int HIGH_QUALITY_AREA = 500_000;
    private static final int LOW_QUALITY_SIDE = 320;
    private static final int LOW_QUALITY_AREA = 120_000;
    private static final List<String> POSITIVE_QUALITY_HINTS = List.of(
            "original", "large", "master", "4k", "1080", "2048"
    );
    private static final List<String> NEGATIVE_QUALITY_HINTS = List.of(
            "thumb", "thumbnail", "small", "avatar", "icon", "logo", "sprite"
    );

    private static final List<String> IMAGE_SUFFIXES = List.of(
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".svg", ".avif"
    );

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // 解析结果短期缓存，减少重复抓取同一页面。
    private final Map<String, CacheEntry> resolveCache = new ConcurrentHashMap<>();

    /**
     * 对外解析入口：校验 URL -> 命中缓存 -> 实际解析。
     */
    public ResolveImageResponse resolve(String rawUrl) {
        URI source = parseAndValidate(rawUrl);
        if (source == null) {
            return ResolveImageResponse.failed("invalid_url");
        }

        String cacheKey = source.toString();
        ResolveImageResponse cached = getCached(cacheKey);
        if (cached != null) {
            return cached;
        }

        ResolveImageResponse resolved = resolveInternal(source);
        putCached(cacheKey, resolved);
        return resolved;
    }

    /**
     * 代理下载图片，返回原始图片字节与内容类型。
     */
    public ProxyImagePayload proxyImage(String target) {
        URI source = parseAndValidate(target);
        if (source == null) {
            throw new ImageProxyException(HttpStatus.BAD_REQUEST, "invalid_target_url");
        }

        HttpFetchResult fetched;
        try {
            fetched = fetch(source, "image/*,*/*;q=0.8", MAX_IMAGE_BYTES);
        } catch (IOException e) {
            if ("response_too_large".equals(e.getMessage())) {
                throw new ImageProxyException(HttpStatus.PAYLOAD_TOO_LARGE, "response_too_large");
            }
            throw new ImageProxyException(HttpStatus.BAD_GATEWAY, "upstream_fetch_failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageProxyException(HttpStatus.BAD_GATEWAY, "upstream_interrupted");
        }

        if (fetched.statusCode() < 200 || fetched.statusCode() >= 300) {
            throw new ImageProxyException(HttpStatus.BAD_GATEWAY, "upstream_status_" + fetched.statusCode());
        }

        String contentType = sanitizeContentType(fetched.contentType());
        if (!isImageContentType(contentType)) {
            throw new ImageProxyException(HttpStatus.BAD_REQUEST, "target_not_image");
        }

        return new ProxyImagePayload(fetched.body(), contentType);
    }

    /**
     * 核心解析流程：
     * 1) 直链图片直接返回；
     * 2) 非直链抓取 HTML，提取候选图片；
     * 3) 返回可用于前端展示的代理 URL。
     */
    private ResolveImageResponse resolveInternal(URI source) {
        if (looksLikeDirectImage(source.getPath())) {
            return ResolveImageResponse.ok(source.toString(), buildProxyUrl(source), null, null, "normal");
        }

        HttpFetchResult fetched;
        try {
            fetched = fetch(source, "text/html,application/xhtml+xml,image/*;q=0.9,*/*;q=0.8", MAX_HTML_BYTES);
        } catch (IOException e) {
            if ("response_too_large".equals(e.getMessage())) {
                return ResolveImageResponse.failed("response_too_large");
            }
            return ResolveImageResponse.failed("upstream_fetch_failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResolveImageResponse.failed("upstream_interrupted");
        }

        if (fetched.statusCode() < 200 || fetched.statusCode() >= 300) {
            return ResolveImageResponse.failed("upstream_status_" + fetched.statusCode());
        }

        String contentType = sanitizeContentType(fetched.contentType());
        if (isImageContentType(contentType)) {
            URI finalUri = fetched.finalUri();
            if (parseAndValidate(finalUri.toString()) == null) {
                return ResolveImageResponse.failed("blocked_image_host");
            }
            return ResolveImageResponse.ok(finalUri.toString(), buildProxyUrl(finalUri), null, null, "normal");
        }

        String html = decodeBody(fetched.body(), fetched.contentType());
        Optional<SelectedCandidate> imageCandidate = extractImageCandidate(html, fetched.finalUri());
        if (imageCandidate.isEmpty()) {
            return ResolveImageResponse.failed("no_image_found");
        }

        SelectedCandidate selected = imageCandidate.get();
        URI resolved = selected.candidate().uri();
        Integer width = selected.candidate().width() > 0 ? selected.candidate().width() : null;
        Integer height = selected.candidate().height() > 0 ? selected.candidate().height() : null;
        String qualityHint = qualityHint(width, height, selected.forcedLowQuality());
        return ResolveImageResponse.ok(resolved.toString(), buildProxyUrl(resolved), width, height, qualityHint);
    }

    /**
     * 发起 HTTP 请求并读取受限长度的响应体。
     */
    private HttpFetchResult fetch(URI source, String acceptHeader, int maxBytes) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(source)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", acceptHeader)
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] body;
        try (InputStream input = response.body()) {
            body = readLimited(input, maxBytes);
        }

        return new HttpFetchResult(
                response.statusCode(),
                response.uri(),
                body,
                response.headers().firstValue("Content-Type").orElse(null)
        );
    }

    /**
     * 读取输入流并做最大字节数保护。
     */
    private byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        byte[] chunk = new byte[8192];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int total = 0;

        while (true) {
            int read = input.read(chunk);
            if (read < 0) {
                break;
            }

            total += read;
            if (total > maxBytes) {
                throw new IOException("response_too_large");
            }
            output.write(chunk, 0, read);
        }

        return output.toByteArray();
    }

    /**
     * 依据响应 Content-Type 里的 charset 解码 HTML，默认 UTF-8。
     */
    private String decodeBody(byte[] body, String contentType) {
        Charset charset = StandardCharsets.UTF_8;
        if (contentType != null) {
            try {
                MediaType mediaType = MediaType.parseMediaType(contentType);
                if (mediaType.getCharset() != null) {
                    charset = mediaType.getCharset();
                }
            } catch (InvalidMediaTypeException ignored) {
                // Fall back to UTF-8.
            }
        }
        return new String(body, charset);
    }

    /**
     * 从 HTML 中提取最合适图片：
     * - 合并 meta/img/srcset 候选；
     * - 通过尺寸 + 关键词综合打分；
     * - 过滤明显小图，若全部被过滤则回退并标记低清风险。
     */
    private Optional<SelectedCandidate> extractImageCandidate(String html, URI baseUri) {
        Document document = Jsoup.parse(html, baseUri.toString());
        Map<String, ImageCandidate> candidates = new LinkedHashMap<>();

        Elements metaCandidates = document.select(
                "meta[property=og:image],meta[name=og:image],meta[property=og:image:url],meta[name=twitter:image],meta[property=twitter:image]"
        );
        for (Element element : metaCandidates) {
            addUrlCandidate(candidates, baseUri, element.attr("content"), "", -1, -1);
        }

        for (Element img : document.select("img[src],img[data-src],img[data-original],img[srcset],img[data-srcset]")) {
            String textHints = (img.attr("class") + " " + img.attr("id") + " " + img.attr("alt")).toLowerCase(Locale.ROOT);
            int width = parseDimension(img.attr("width"));
            int height = parseDimension(img.attr("height"));

            String raw = firstNonBlank(img.attr("src"), img.attr("data-src"), img.attr("data-original"));
            addUrlCandidate(candidates, baseUri, raw, textHints, width, height);

            String srcset = firstNonBlank(img.attr("srcset"), img.attr("data-srcset"));
            addSrcsetCandidates(candidates, baseUri, srcset, textHints, width, height);
        }

        ImageCandidate best = null;
        ImageCandidate fallback = null;
        for (ImageCandidate candidate : candidates.values()) {
            if (fallback == null) {
                fallback = candidate;
            }
            if (isLowResolutionCandidate(candidate)) {
                continue;
            }
            if (best == null || candidate.score() > best.score()) {
                best = candidate;
            }
        }

        if (best != null) {
            return Optional.of(new SelectedCandidate(best, false));
        }
        if (fallback != null) {
            return Optional.of(new SelectedCandidate(fallback, true));
        }
        return Optional.empty();
    }

    private void addUrlCandidate(
            Map<String, ImageCandidate> candidates,
            URI baseUri,
            String rawUrl,
            String textHints,
            int width,
            int height
    ) {
        Optional<URI> resolved = resolveToPublicUri(baseUri, rawUrl);
        if (resolved.isEmpty()) {
            return;
        }
        URI uri = resolved.get();
        int score = scoreCandidate(uri.toString(), textHints, width, height);
        mergeCandidate(candidates, new ImageCandidate(uri, width, height, score));
    }

    private void addSrcsetCandidates(
            Map<String, ImageCandidate> candidates,
            URI baseUri,
            String srcset,
            String textHints,
            int fallbackWidth,
            int fallbackHeight
    ) {
        if (srcset == null || srcset.isBlank()) {
            return;
        }

        for (String rawPart : srcset.split(",")) {
            String part = rawPart.trim();
            if (part.isEmpty()) {
                continue;
            }

            String[] tokens = part.split("\\s+");
            if (tokens.length == 0) {
                continue;
            }

            String url = tokens[0];
            int width = fallbackWidth;
            int height = fallbackHeight;
            if (tokens.length >= 2) {
                int[] parsedSize = parseSrcsetDescriptor(tokens[1], fallbackWidth, fallbackHeight);
                width = parsedSize[0];
                height = parsedSize[1];
            }

            addUrlCandidate(candidates, baseUri, url, textHints, width, height);
        }
    }

    private int[] parseSrcsetDescriptor(String descriptor, int fallbackWidth, int fallbackHeight) {
        if (descriptor == null || descriptor.isBlank()) {
            return new int[]{fallbackWidth, fallbackHeight};
        }

        String normalized = descriptor.trim().toLowerCase(Locale.ROOT);
        try {
            if (normalized.endsWith("w")) {
                int width = Integer.parseInt(normalized.substring(0, normalized.length() - 1));
                if (width > 0) {
                    int height = (fallbackHeight > 0 && fallbackWidth > 0)
                            ? Math.max(1, Math.round(width * (fallbackHeight / (float) fallbackWidth)))
                            : Math.max(1, Math.round(width * 0.75f));
                    return new int[]{width, height};
                }
            }

            if (normalized.endsWith("x")) {
                double ratio = Double.parseDouble(normalized.substring(0, normalized.length() - 1));
                if (ratio > 0) {
                    int width = fallbackWidth > 0 ? Math.max(1, (int) Math.round(fallbackWidth * ratio)) : Math.max(1, (int) Math.round(320 * ratio));
                    int height = fallbackHeight > 0 ? Math.max(1, (int) Math.round(fallbackHeight * ratio)) : Math.max(1, (int) Math.round(180 * ratio));
                    return new int[]{width, height};
                }
            }
        } catch (NumberFormatException ignored) {
            // Ignore invalid descriptor and keep fallback values.
        }

        return new int[]{fallbackWidth, fallbackHeight};
    }

    private int scoreCandidate(String url, String textHints, int width, int height) {
        int score = 10_000;

        if (width > 0 && height > 0) {
            long area = (long) width * height;
            score += (int) Math.min(area, 5_000_000L);
        }

        String merged = (url + " " + textHints).toLowerCase(Locale.ROOT);
        for (String keyword : POSITIVE_QUALITY_HINTS) {
            if (merged.contains(keyword)) {
                score += 120_000;
            }
        }
        for (String keyword : NEGATIVE_QUALITY_HINTS) {
            if (merged.contains(keyword)) {
                score -= 200_000;
            }
        }
        return score;
    }

    private boolean isLowResolutionCandidate(ImageCandidate candidate) {
        int width = candidate.width();
        int height = candidate.height();
        if (width <= 0 || height <= 0) {
            return false;
        }
        long area = (long) width * height;
        return width < MIN_CANDIDATE_SIDE || height < MIN_CANDIDATE_SIDE || area < MIN_CANDIDATE_AREA;
    }

    private void mergeCandidate(Map<String, ImageCandidate> candidates, ImageCandidate next) {
        String key = next.uri().toString();
        ImageCandidate current = candidates.get(key);
        if (current == null || next.score() > current.score()) {
            candidates.put(key, next);
        }
    }

    private int parseDimension(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        String cleaned = raw.trim().toLowerCase(Locale.ROOT).replace("px", "");
        try {
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private String qualityHint(Integer width, Integer height, boolean forcedLowQuality) {
        if (forcedLowQuality) {
            return "low";
        }

        if (width == null || height == null || width <= 0 || height <= 0) {
            return "normal";
        }

        long area = (long) width * height;
        if ((width >= HIGH_QUALITY_SIDE || height >= HIGH_QUALITY_SIDE) && area >= HIGH_QUALITY_AREA) {
            return "high";
        }
        if (width < LOW_QUALITY_SIDE || height < LOW_QUALITY_SIDE || area < LOW_QUALITY_AREA) {
            return "low";
        }
        return "normal";
    }

    private Optional<URI> resolveToPublicUri(URI baseUri, String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        if (raw.startsWith("data:")) {
            return Optional.empty();
        }

        URI resolved;
        try {
            resolved = baseUri.resolve(raw.trim());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        URI validated = parseAndValidate(resolved.toString());
        if (validated == null) {
            return Optional.empty();
        }
        return Optional.of(validated);
    }

    /**
     * 基于后缀快速判断 URL path 是否像图片直链。
     */
    private boolean looksLikeDirectImage(String path) {
        if (path == null) {
            return false;
        }
        String lowerPath = path.toLowerCase(Locale.ROOT);
        for (String suffix : IMAGE_SUFFIXES) {
            if (lowerPath.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private String sanitizeContentType(String rawContentType) {
        if (rawContentType == null || rawContentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        String value = rawContentType.trim();
        int separatorIndex = value.indexOf(';');
        if (separatorIndex >= 0) {
            value = value.substring(0, separatorIndex).trim();
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private boolean isImageContentType(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    private String buildProxyUrl(URI resolvedImageUri) {
        return "/api/image/proxy?target=" + URLEncoder.encode(resolvedImageUri.toString(), StandardCharsets.UTF_8);
    }

    /**
     * URL 基础校验与 SSRF 风险过滤：
     * - 仅允许 http/https
     * - 禁止内网、回环、链路本地等地址
     */
    private URI parseAndValidate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }

        URI uri;
        try {
            uri = URI.create(rawUrl.trim()).normalize();
        } catch (IllegalArgumentException e) {
            return null;
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return null;
        }

        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
            return null;
        }

        if (!isPublicHost(host)) {
            return null;
        }

        return uri;
    }

    private boolean isPublicHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(lower) || lower.endsWith(".local")) {
            return false;
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return false;
        }

        for (InetAddress address : addresses) {
            if (isPrivateAddress(address)) {
                return false;
            }
        }
        return true;
    }

    private boolean isPrivateAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        if (address instanceof Inet4Address ipv4) {
            byte[] b = ipv4.getAddress();
            int b1 = b[0] & 0xff;
            int b2 = b[1] & 0xff;

            if (b1 == 10 || b1 == 127 || b1 == 0) {
                return true;
            }
            if (b1 == 172 && b2 >= 16 && b2 <= 31) {
                return true;
            }
            if (b1 == 192 && b2 == 168) {
                return true;
            }
            if (b1 == 169 && b2 == 254) {
                return true;
            }
            if (b1 == 100 && b2 >= 64 && b2 <= 127) {
                return true;
            }
            if (b1 == 198 && (b2 == 18 || b2 == 19)) {
                return true;
            }
        }

        if (address instanceof Inet6Address ipv6) {
            byte[] b = ipv6.getAddress();
            return (b[0] & (byte) 0xfe) == (byte) 0xfc;
        }

        return false;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private ResolveImageResponse getCached(String key) {
        CacheEntry entry = resolveCache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            resolveCache.remove(key);
            return null;
        }
        return entry.response();
    }

    /**
     * 写入解析缓存，TTL 由 CACHE_TTL 控制。
     */
    private void putCached(String key, ResolveImageResponse response) {
        resolveCache.put(key, new CacheEntry(response, Instant.now().plus(CACHE_TTL)));
    }

    private record ImageCandidate(URI uri, int width, int height, int score) {
    }

    private record SelectedCandidate(ImageCandidate candidate, boolean forcedLowQuality) {
    }

    private record HttpFetchResult(int statusCode, URI finalUri, byte[] body, String contentType) {
    }

    private record CacheEntry(ResolveImageResponse response, Instant expiresAt) {
    }
}
