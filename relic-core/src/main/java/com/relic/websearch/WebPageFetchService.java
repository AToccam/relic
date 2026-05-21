package com.relic.websearch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebPageFetchService {

    private final WebPageTextExtractor textExtractor;

    @Value("${relic.web-search.timeout-ms:12000}")
    private int timeoutMs;

    @Value("${relic.web-search.user-agent:Mozilla/5.0 (compatible; RelicBot/1.0)}")
    private String userAgent;

    @Value("${relic.web-search.max-download-bytes:2097152}")
    private int maxDownloadBytes;

    @Value("${relic.web-search.max-content-chars:12000}")
    private int maxContentChars;

    @Value("${relic.web-search.max-redirects:5}")
    private int maxRedirects;

    public WebPageContent fetch(String url, String title, String snippet, String keyword) {
        URI currentUri = validatePublicHttpUri(url);
        try {
            Connection.Response response = null;
            int redirectLimit = Math.max(0, maxRedirects);
            for (int i = 0; i <= redirectLimit; i++) {
                response = Jsoup.connect(currentUri.toString())
                        .userAgent(userAgent)
                        .timeout(Math.max(3000, timeoutMs))
                        .maxBodySize(Math.max(65536, maxDownloadBytes))
                        .followRedirects(false)
                        .ignoreContentType(true)
                        .ignoreHttpErrors(true)
                        .execute();

                if (!isRedirect(response.statusCode())) {
                    break;
                }

                String location = response.header("Location");
                if (!StringUtils.hasText(location)) {
                    throw new IllegalArgumentException("网页重定向缺少 Location");
                }
                if (i == redirectLimit) {
                    throw new IllegalArgumentException("网页重定向次数过多");
                }

                // 每一跳都重新校验，避免公共 URL 30x 到内网地址。
                currentUri = validatePublicHttpUri(currentUri.resolve(location).toString());
            }

            if (response == null) {
                throw new IllegalArgumentException("网页抓取失败: 未获取到响应");
            }
            if (response.statusCode() >= 400) {
                throw new IllegalArgumentException("网页请求失败，HTTP " + response.statusCode());
            }

            String contentType = response.contentType();
            if (StringUtils.hasText(contentType) && !isSupportedContentType(contentType)) {
                throw new IllegalArgumentException("暂不支持抓取该内容类型: " + contentType);
            }

            Document document = response.parse();
            WebPageContent content = textExtractor.extract(
                    response.url().toExternalForm(),
                    title,
                    snippet,
                    keyword,
                    document,
                    Math.max(1000, maxContentChars));
            if (!StringUtils.hasText(content.getContent())) {
                throw new IllegalArgumentException("网页未提取到有效正文");
            }
            return content;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("网页抓取失败: url={}, reason={}", currentUri, e.getMessage());
            throw new IllegalArgumentException("网页抓取失败: " + e.getMessage(), e);
        }
    }

    URI validatePublicHttpUri(String url) {
        if (!StringUtils.hasText(url)) {
            throw new IllegalArgumentException("url 不能为空");
        }

        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("url 格式不正确");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))) {
            throw new IllegalArgumentException("仅支持 http/https 网页资源");
        }

        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw new IllegalArgumentException("url 缺少 host");
        }

        String lowerHost = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(lowerHost)
                || lowerHost.endsWith(".local")
                || lowerHost.endsWith(".internal")) {
            throw new IllegalArgumentException("不允许抓取本机或内网地址");
        }

        validateResolvedAddresses(lowerHost);
        return uri;
    }

    private void validateResolvedAddresses(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("url host 无法解析: " + host);
        }

        if (addresses.length == 0) {
            throw new IllegalArgumentException("url host 无法解析: " + host);
        }

        for (InetAddress address : addresses) {
            if (isUnsafeAddress(address)) {
                throw new IllegalArgumentException("不允许抓取本机或内网地址: " + address.getHostAddress());
            }
        }
    }

    private boolean isUnsafeAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return isUnsafeIpv4(bytes);
        }
        if (address instanceof Inet6Address) {
            return isUniqueLocalIpv6(bytes) || isIpv4MappedPrivate(bytes);
        }
        return false;
    }

    private boolean isUnsafeIpv4(byte[] bytes) {
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 0
                || first == 10
                || first == 127
                || first == 169 && second == 254
                || first == 172 && second >= 16 && second <= 31
                || first == 192 && second == 168
                || first >= 224;
    }

    private boolean isUniqueLocalIpv6(byte[] bytes) {
        int first = bytes[0] & 0xff;
        return (first & 0xfe) == 0xfc;
    }

    private boolean isIpv4MappedPrivate(byte[] bytes) {
        if (bytes.length != 16) {
            return false;
        }
        boolean prefixMatches = Arrays.equals(Arrays.copyOfRange(bytes, 0, 10), new byte[10])
                && (bytes[10] & 0xff) == 0xff
                && (bytes[11] & 0xff) == 0xff;
        if (!prefixMatches) {
            return false;
        }
        return isUnsafeIpv4(Arrays.copyOfRange(bytes, 12, 16));
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == 301
                || statusCode == 302
                || statusCode == 303
                || statusCode == 307
                || statusCode == 308;
    }

    private boolean isSupportedContentType(String contentType) {
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.contains("text/html")
                || lower.startsWith("text/plain")
                || lower.contains("application/xhtml")
                || lower.contains("application/xml")
                || lower.contains("text/xml");
    }
}
