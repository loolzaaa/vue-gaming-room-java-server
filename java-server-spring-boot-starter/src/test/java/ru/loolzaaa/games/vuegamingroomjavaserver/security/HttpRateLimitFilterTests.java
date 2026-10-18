package ru.loolzaaa.games.vuegamingroomjavaserver.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class HttpRateLimitFilterTests {

    @Test
    void shouldAllowBurstAndRejectExcessRequests() throws Exception {
        HttpRateLimitFilter filter = new HttpRateLimitFilter(1, 1);
        FilterChain filterChain = mock(FilterChain.class);

        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(request("127.0.0.1"), firstResponse, filterChain);
        assertThat(firstResponse.getStatus()).isEqualTo(200);

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(request("127.0.0.1"), secondResponse, filterChain);

        assertThat(secondResponse.getStatus()).isEqualTo(429);
        assertThat(secondResponse.getContentAsString()).contains("Too many requests");
        verify(filterChain, times(1)).doFilter(any(), any());
    }

    @Test
    void shouldUseSeparateBucketsForDifferentAddresses() throws Exception {
        HttpRateLimitFilter filter = new HttpRateLimitFilter(1, 1);
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request("127.0.0.1"), new MockHttpServletResponse(), filterChain);
        filter.doFilter(request("127.0.0.2"), new MockHttpServletResponse(), filterChain);

        verify(filterChain, times(2)).doFilter(any(), any());
    }

    @Test
    void shouldNotLimitNonRoomRequests() throws Exception {
        HttpRateLimitFilter filter = new HttpRateLimitFilter(1, 1);
        FilterChain filterChain = mock(FilterChain.class);

        MockHttpServletRequest request = request("127.0.0.1");
        request.setRequestURI("/game/info");
        filter.doFilter(request, new MockHttpServletResponse(), filterChain);

        verify(filterChain).doFilter(any(), any());
    }

    @Test
    void shouldIgnoreOptionsRequests() throws Exception {
        HttpRateLimitFilter filter = new HttpRateLimitFilter(1, 1);
        FilterChain filterChain = mock(FilterChain.class);

        MockHttpServletRequest request = request("127.0.0.1");
        request.setMethod("OPTIONS");
        filter.doFilter(request, new MockHttpServletResponse(), filterChain);
        filter.doFilter(request("127.0.0.1"), new MockHttpServletResponse(), filterChain);
        filter.doFilter(request("127.0.0.1"), new MockHttpServletResponse(), filterChain);

        // OPTIONS не расходует лимит, поэтому первый POST проходит, второй - нет
        verify(filterChain, times(2)).doFilter(any(), any());
    }

    private MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/room/create");
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
