package com.tesa.foodbridge;
import com.tesa.foodbridge.shared.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
class RequestIdFilterTest {
    @Test void acceptsGeneratesAndClearsEvenOnFailure() throws Exception {
        var filter=new RequestIdFilter();
        var request=new MockHttpServletRequest(); var response=new MockHttpServletResponse();
        request.addHeader("X-Request-Id","demo-123");
        filter.doFilter(request,response,(q,s)->assertEquals("demo-123",MDC.get("requestId")));
        assertEquals("demo-123",response.getHeader("X-Request-Id")); assertNull(MDC.get("requestId"));
        var missing=new MockHttpServletRequest(); var generated=new MockHttpServletResponse();
        assertThrows(jakarta.servlet.ServletException.class,()->filter.doFilter(missing,generated,(q,s)->{
            assertNotNull(MDC.get("requestId")); throw new jakarta.servlet.ServletException("test");
        }));
        assertNotNull(generated.getHeader("X-Request-Id")); assertNull(MDC.get("requestId"));
    }
}
