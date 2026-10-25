package com.tesa.foodbridge.shared;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RequestIdFilter.class);
    private static final String ATTRIBUTE=RequestIdFilter.class.getName()+".id";
    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected boolean shouldNotFilterErrorDispatch() { return false; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String id=(String) request.getAttribute(ATTRIBUTE);
        if(id==null) {
            id=request.getHeader("X-Request-Id");
            if(id==null || !id.matches("[A-Za-z0-9._-]{1,100}")) id=UUID.randomUUID().toString();
            request.setAttribute(ATTRIBUTE,id);
        }
        MDC.put("requestId",id);
        response.setHeader("X-Request-Id",id);
        try { chain.doFilter(request,response); }
        finally {
            try { LOG.info("HTTP dispatch finished method={} status={}",request.getMethod(),response.getStatus()); }
            finally { MDC.remove("requestId"); }
        }
    }
}
