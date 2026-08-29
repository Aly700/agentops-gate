package dev.affan.agentopsgate.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.affan.agentopsgate.config.HttpRequestLoggingFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HttpRequestLoggingFilterTest {

    @Test
    void logsMethodPathAndResponseStatus() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(HttpRequestLoggingFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/decisions");
            MockHttpServletResponse response = new MockHttpServletResponse();
            SimpleMeterRegistry metrics = new SimpleMeterRegistry();

            new HttpRequestLoggingFilter(metrics).doFilter(
                    request,
                    response,
                    (ignoredRequest, chainResponse) -> ((MockHttpServletResponse) chainResponse).setStatus(503));

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.INFO);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .contains("event=http_request", "method=POST", "path=/decisions", "status=503");
            assertThat(metrics.counter("gate.http.responses", "status_class", "5xx").count())
                    .isEqualTo(1.0);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
