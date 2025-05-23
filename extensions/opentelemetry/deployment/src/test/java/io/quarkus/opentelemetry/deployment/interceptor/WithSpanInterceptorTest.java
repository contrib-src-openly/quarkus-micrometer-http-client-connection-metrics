package io.quarkus.opentelemetry.deployment.interceptor;

import static io.opentelemetry.api.trace.SpanKind.CLIENT;
import static io.opentelemetry.api.trace.SpanKind.INTERNAL;
import static io.opentelemetry.api.trace.SpanKind.SERVER;
import static io.opentelemetry.api.trace.StatusCode.ERROR;
import static io.opentelemetry.semconv.incubating.CodeIncubatingAttributes.CODE_FUNCTION;
import static io.opentelemetry.semconv.incubating.CodeIncubatingAttributes.CODE_NAMESPACE;
import static io.quarkus.opentelemetry.deployment.common.exporter.TestSpanExporter.getSpanByKindAndParentId;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.opentelemetry.sdk.trace.data.ExceptionEventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.quarkus.opentelemetry.deployment.common.exporter.TestSpanExporter;
import io.quarkus.opentelemetry.deployment.common.exporter.TestSpanExporterProvider;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.Router;

public class WithSpanInterceptorTest {
    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(
                    () -> ShrinkWrap.create(JavaArchive.class)
                            .addClass(SpanBean.class)
                            .addClasses(TestSpanExporter.class, TestSpanExporterProvider.class)
                            .addAsManifestResource(
                                    "META-INF/services-config/io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider",
                                    "services/io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider")
                            .addAsResource("resource-config/application-no-metrics.properties", "application.properties"));

    @Inject
    SpanBean spanBean;
    @Inject
    TestSpanExporter spanExporter;

    @AfterEach
    void tearDown() {
        spanExporter.reset();
    }

    @Test
    void span() {
        spanBean.span();
        List<SpanData> spanItems = spanExporter.getFinishedSpanItems(1);
        assertEquals("SpanBean.span", spanItems.get(0).getName());
        assertEquals(INTERNAL, spanItems.get(0).getKind());
        assertNotEquals(ERROR, spanItems.get(0).getStatus().getStatusCode());
    }

    @Test
    void spanName() {
        spanBean.spanName();
        List<SpanData> spanItems = spanExporter.getFinishedSpanItems(1);
        assertEquals("name", spanItems.get(0).getName());
        assertEquals(INTERNAL, spanItems.get(0).getKind());
    }

    @Test
    void spanKind() {
        spanBean.spanKind();
        List<SpanData> spanItems = spanExporter.getFinishedSpanItems(1);
        SpanData span = spanItems.get(0);
        assertEquals("SpanBean.spanKind", span.getName());
        assertEquals(SERVER, span.getKind());
        assertClassMethodNames(span, SpanBean.class, "spanKind");
    }

    @Test
    void spanArgs() {
        spanBean.spanArgs("argument");
        List<SpanData> spanItems = spanExporter.getFinishedSpanItems(1);
        SpanData span = spanItems.get(0);
        assertEquals("SpanBean.spanArgs", span.getName());
        assertEquals(INTERNAL, span.getKind());
        assertEquals("argument", span.getAttributes().get(AttributeKey.stringKey("arg")));
        assertClassMethodNames(span, SpanBean.class, "spanArgs");
    }

    @Test
    void spanChild() {
        spanBean.spanChild();
        List<SpanData> spans = spanExporter.getFinishedSpanItems(2);

        final SpanData parent = getSpanByKindAndParentId(spans, INTERNAL, "0000000000000000");
        assertEquals("SpanBean.spanChild", parent.getName());
        assertClassMethodNames(parent, SpanBean.class, "spanChild");

        final SpanData child = getSpanByKindAndParentId(spans, INTERNAL, parent.getSpanId());
        assertEquals("SpanChildBean.spanChild", child.getName());
        assertClassMethodNames(child, SpanChildBean.class, "spanChild");

    }

    @Test
    void spanCdiRest() {
        spanBean.spanRestClient();
        List<SpanData> spans = spanExporter.getFinishedSpanItems(4);

        final SpanData parent = getSpanByKindAndParentId(spans, INTERNAL, "0000000000000000");
        final SpanData child = getSpanByKindAndParentId(spans, INTERNAL, parent.getSpanId());
        final SpanData client = getSpanByKindAndParentId(spans, CLIENT, child.getSpanId());
        getSpanByKindAndParentId(spans, SERVER, client.getSpanId());
    }

    @Test
    void spanWithException() {
        try {
            spanBean.spanWithException();
            fail("Exception expected");
        } catch (Exception e) {
            assertThrows(RuntimeException.class, () -> {
                throw e;
            });
        }
        List<SpanData> spanItems = spanExporter.getFinishedSpanItems(1);
        SpanData span = spanItems.get(0);
        assertEquals("SpanBean.spanWithException", span.getName());
        assertEquals(INTERNAL, span.getKind());
        assertEquals(ERROR, span.getStatus().getStatusCode());
        assertEquals(1, span.getEvents().size());
        assertEquals("spanWithException for tests",
                ((ExceptionEventData) span.getEvents().get(0)).getException().getMessage());
        assertClassMethodNames(span, SpanBean.class, "spanWithException");
    }

    @Test
    void spanUni() {
        assertEquals("hello Uni", spanBean.spanUni().await().atMost(Duration.ofSeconds(1)));
        List<SpanData> spans = spanExporter.getFinishedSpanItems(1);

        final SpanData parent = getSpanByKindAndParentId(spans, INTERNAL, "0000000000000000");
        assertEquals("withSpanAndUni", parent.getName());
        assertEquals(StatusCode.UNSET, parent.getStatus().getStatusCode());
        assertClassMethodNames(parent, SpanBean.class, "spanUni");
    }

    @Test
    void spanUniWithException() {
        try {
            spanBean.spanUniWithException().await().atMost(Duration.ofSeconds(1));
            fail("Exception expected");
        } catch (Exception e) {
            assertThrows(RuntimeException.class, () -> {
                throw e;
            });
        }
        List<SpanData> spanItems = spanExporter.getFinishedSpanItems(1);
        SpanData span = spanItems.get(0);
        assertEquals("withSpanAndUni", span.getName());
        assertEquals(INTERNAL, span.getKind());
        assertEquals(ERROR, span.getStatus().getStatusCode());
        assertEquals(1, span.getEvents().size());
        assertEquals("hello Uni",
                ((ExceptionEventData) span.getEvents().get(0)).getException().getMessage());
        assertClassMethodNames(span, SpanBean.class, "spanUniWithException");
    }

    @Test
    void spanMulti() {
        assertEquals("hello Multi 2", spanBean.spanMulti().collect().last().await().atMost(Duration.ofSeconds(1)));
        List<SpanData> spans = spanExporter.getFinishedSpanItems(1);

        final SpanData parent = getSpanByKindAndParentId(spans, INTERNAL, "0000000000000000");
        assertEquals("withSpanAndMulti", parent.getName());
        assertEquals(StatusCode.UNSET, parent.getStatus().getStatusCode());
        assertClassMethodNames(parent, SpanBean.class, "spanMulti");
    }

    @Test
    void spanMultiWithException() {
        try {
            spanBean.spanMultiWithException().collect().last().await().atMost(Duration.ofSeconds(1));
            fail("Exception expected");
        } catch (Exception e) {
            assertThrows(RuntimeException.class, () -> {
                throw e;
            });
        }
        List<SpanData> spanItems = spanExporter.getFinishedSpanItems(1);
        assertEquals("withSpanAndMulti", spanItems.get(0).getName());
        assertEquals(INTERNAL, spanItems.get(0).getKind());
        assertEquals(ERROR, spanItems.get(0).getStatus().getStatusCode());
        assertEquals(1, spanItems.get(0).getEvents().size());
        assertEquals("hello Multi",
                ((ExceptionEventData) spanItems.get(0).getEvents().get(0)).getException().getMessage());
        assertClassMethodNames(spanItems.get(0), SpanBean.class, "spanMultiWithException");
    }

    private void assertClassMethodNames(SpanData span, Class<?> clazz, String method) {
        assertEquals(method, span.getAttributes().get((CODE_FUNCTION)));
        assertEquals(clazz.getName(), span.getAttributes().get((CODE_NAMESPACE)));
    }

    @ApplicationScoped
    public static class SpanBean {
        @WithSpan
        public void span() {

        }

        @WithSpan
        public void spanWithException() {
            throw new RuntimeException("spanWithException for tests");
        }

        @WithSpan("name")
        public void spanName() {

        }

        @WithSpan(kind = SERVER)
        public void spanKind() {

        }

        @WithSpan
        public void spanArgs(@SpanAttribute(value = "arg") String arg) {

        }

        @Inject
        SpanChildBean spanChildBean;

        @WithSpan
        public void spanChild() {
            spanChildBean.spanChild();
        }

        @Inject
        SpanRestClient spanRestClient;

        @WithSpan
        public void spanRestClient() {
            spanRestClient.spanRestClient();
        }

        @WithSpan(value = "withSpanAndUni")
        public Uni<String> spanUni() {
            return Uni.createFrom().item("hello Uni");
        }

        @WithSpan(value = "withSpanAndUni")
        public Uni<String> spanUniWithException() {
            return Uni.createFrom().failure(new RuntimeException("hello Uni"));
        }

        @WithSpan(value = "withSpanAndMulti")
        public Multi<String> spanMulti() {
            return Multi.createFrom().items("hello Multi 1", "hello Multi 2");
        }

        @WithSpan(value = "withSpanAndMulti")
        public Multi<String> spanMultiWithException() {
            return Multi.createFrom().failure(new RuntimeException("hello Multi"));
        }
    }

    @ApplicationScoped
    public static class SpanChildBean {
        @WithSpan
        public void spanChild() {

        }
    }

    @ApplicationScoped
    public static class SpanRestClient {
        @Inject
        SmallRyeConfig config;

        @WithSpan
        public void spanRestClient() {
            try (Client client = ClientBuilder.newClient()) {
                WebTarget target = client.target(UriBuilder
                        .fromUri(config.getRawValue("test.url"))
                        .path("hello"));
                Response response = target.request().get();
                assertEquals(HTTP_OK, response.getStatus());
            }
        }
    }

    @ApplicationScoped
    public static class HelloRouter {
        @Inject
        Router router;

        public void register(@Observes StartupEvent ev) {
            router.get("/hello").handler(rc -> rc.response().end("hello"));
        }
    }
}
