package com.relic.websearch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class WebPageFetchServiceTest {

    private final WebPageFetchService service = new WebPageFetchService(new WebPageTextExtractor());

    @Test
    void validatePublicHttpUriRejectsLoopbackIpv4() {
        assertThrows(IllegalArgumentException.class, () ->
                service.validatePublicHttpUri("http://127.0.0.1:8082/"));
    }

    @Test
    void validatePublicHttpUriRejectsUniqueLocalIpv6() {
        assertThrows(IllegalArgumentException.class, () ->
                service.validatePublicHttpUri("http://[fc00::1]/"));
    }

    @Test
    void validatePublicHttpUriRejectsIpv4MappedLoopback() {
        assertThrows(IllegalArgumentException.class, () ->
                service.validatePublicHttpUri("http://[::ffff:127.0.0.1]/"));
    }
}
