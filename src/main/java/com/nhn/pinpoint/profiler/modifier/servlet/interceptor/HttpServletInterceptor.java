package com.nhn.pinpoint.profiler.modifier.servlet.interceptor;

import java.util.Enumeration;
import java.util.UUID;

import com.nhn.pinpoint.profiler.context.*;
import com.nhn.pinpoint.profiler.interceptor.ByteCodeMethodDescriptorSupport;
import com.nhn.pinpoint.profiler.interceptor.MethodDescriptor;
import com.nhn.pinpoint.profiler.interceptor.SimpleAroundInterceptor;
import com.nhn.pinpoint.profiler.interceptor.TraceContextSupport;
import com.nhn.pinpoint.profiler.logging.Logger;
import com.nhn.pinpoint.profiler.logging.LoggerFactory;

import javax.servlet.http.HttpServletRequest;

import com.nhn.pinpoint.common.AnnotationKey;
import com.nhn.pinpoint.common.ServiceType;
import com.nhn.pinpoint.profiler.sampler.util.SamplingFlagUtils;
import com.nhn.pinpoint.profiler.util.NumberUtils;

public class HttpServletInterceptor implements SimpleAroundInterceptor, ByteCodeMethodDescriptorSupport, TraceContextSupport {

    private final Logger logger = LoggerFactory.getLogger(HttpServletInterceptor.class);
    private final boolean isDebug = logger.isDebugEnabled();

    private MethodDescriptor descriptor;
    private TraceContext traceContext;

/*    
    java.lang.IllegalStateException: already Trace Object exist.
	at com.profiler.context.TraceContext.attachTraceObject(TraceContext.java:54)
	at HttpServletInterceptor.before(HttpServletInterceptor.java:62)
	at org.springframework.web.servlet.FrameworkServlet.doGet(FrameworkServlet.java)								// profile method
**	at javax.servlet.http.HttpServlet.service(HttpServlet.java:617) 												// profile method
	at javax.servlet.http.HttpServlet.service(HttpServlet.java:717)
	at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:290)
	at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:206)
	at org.springframework.web.filter.CharacterEncodingFilter.doFilterInternal(CharacterEncodingFilter.java:88)
	at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:76)
	at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:235)
	at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:206)
	at org.apache.catalina.core.StandardWrapperValve.invoke(StandardWrapperValve.java:233)
	at org.apache.catalina.core.StandardContextValve.invoke(StandardContextValve.java:191)
**	at org.apache.catalina.core.StandardHostValve.invoke(StandardHostValve.java:127)  								// make traceId here
	at org.apache.catalina.valves.ErrorReportValve.invoke(ErrorReportValve.java:102)
	at org.apache.catalina.core.StandardEngineValve.invoke(StandardEngineValve.java:109)
	at org.apache.catalina.connector.CoyoteAdapter.service(CoyoteAdapter.java:293)
	at org.apache.coyote.http11.Http11Processor.process(Http11Processor.java:859)
	at org.apache.coyote.http11.Http11Protocol$Http11ConnectionHandler.process(Http11Protocol.java:602)
	at org.apache.tomcat.util.net.JIoEndpoint$Worker.run(JIoEndpoint.java:489)
	at java.lang.Thread.run(Thread.java:680)
*/
    @Override
    public void before(Object target, Object[] args) {
        if (isDebug) {
            logger.beforeInterceptor(target, args);
        }

        try {
//            traceContext.getActiveThreadCounter().start();

            HttpServletRequest request = (HttpServletRequest) args[0];
            boolean sampling = samplingEnable(request);
            if (!sampling) {
                // 샘플링 대상이 아닐 경우도 TraceObject를 생성하여, sampling 대상이 아니라는것을 명시해야 한다.
                // sampling 대상이 아닐경우 rpc 호출에서 sampling 대상이 아닌 것에 rpc호출 파라미터에 sampling disable 파라미터를 박을수 있다.
                traceContext.disableSampling();
                return;
            }

            String requestURL = request.getRequestURI();
            String remoteAddr = request.getRemoteAddr();

            TraceID traceId = populateTraceIdFromRequest(request);
            Trace trace;
            if (traceId != null) {
                if (logger.isInfoEnabled()) {
                    logger.debug("TraceID exist. continue trace. {} requestUrl:{}, remoteAddr:{}", new Object[] {traceId, requestURL, remoteAddr });
                }
                trace = traceContext.continueTraceObject(traceId);
            } else {
                trace = traceContext.newTraceObject();
                if (logger.isInfoEnabled()) {
                    logger.debug("TraceID not exist. start new trace. {} requestUrl:{}, remoteAddr:{}", new Object[] {traceId, requestURL, remoteAddr });
                }
            }

            trace.markBeforeTime();
            // TODO 잘못됬음 Servlet가 되어야함
            trace.recordServiceType(ServiceType.TOMCAT);
            trace.recordRpcName(requestURL);

            int port = request.getServerPort();
            trace.recordEndPoint(request.getProtocol() + ":" + request.getServerName() + ((port > 0) ? ":" + port : ""));
            trace.recordDestinationId(request.getServerName() + ((port > 0) ? ":" + port : ""));
            trace.recordAttribute(AnnotationKey.HTTP_URL, request.getRequestURI());
        } catch (Throwable e) {
            if (logger.isWarnEnabled()) {
                logger.warn("Tomcat StandardHostValve trace start fail. Caused:" + e.getMessage(), e);
            }
        }
    }

    @Override
    public void after(Object target, Object[] args, Object result) {
        if (isDebug) {
            logger.afterInterceptor(target, args, result);
        }

        Trace trace = traceContext.currentTraceObject();
        if (trace == null) {
            return;
        }
        try {
            traceContext.detachTraceObject();

            HttpServletRequest request = (HttpServletRequest) args[0];
            String parameters = getRequestParameter(request);
            if (parameters != null && parameters.length() > 0) {
                trace.recordAttribute(AnnotationKey.HTTP_PARAM, parameters);
            }


            trace.recordApi(descriptor);

            trace.recordException(result);

            trace.markAfterTime();
        } finally {
            trace.traceBlockEnd();
        }
    }

    private boolean samplingEnable(HttpServletRequest request) {
        // optional 값.
        String samplingFlag = request.getHeader(Header.HTTP_SAMPLED.toString());
        return SamplingFlagUtils.isSamplingFlag(samplingFlag);
    }

    /**
     * Pupulate source trace from HTTP Header.
     *
     * @param request
     * @return
     */
    private TraceID populateTraceIdFromRequest(HttpServletRequest request) {
        String strUUID = request.getHeader(Header.HTTP_TRACE_ID.toString());
        if (strUUID != null) {
            UUID uuid = UUID.fromString(strUUID);
            int parentSpanID = NumberUtils.parseInteger(request.getHeader(Header.HTTP_PARENT_SPAN_ID.toString()), SpanID.NULL);
            int spanID = NumberUtils.parseInteger(request.getHeader(Header.HTTP_SPAN_ID.toString()), SpanID.NULL);
            short flags = NumberUtils.parseShort(request.getHeader(Header.HTTP_FLAGS.toString()), (short) 0);

            TraceID id = this.traceContext.createTraceId(uuid, parentSpanID, spanID, flags);
            if (logger.isInfoEnabled()) {
                logger.info("TraceID exist. continue trace. " + id);
            }
            return id;
        } else {
            return null;
        }
    }

    private String getRequestParameter(HttpServletRequest request) {
        Enumeration<?> attrs = request.getParameterNames();
        StringBuilder params = new StringBuilder();

        while (attrs.hasMoreElements()) {
            String keyString = attrs.nextElement().toString();
            Object value = request.getParameter(keyString);

            if (value != null) {
                String valueString = value.toString();
                int valueStringLength = valueString.length();

                if (valueStringLength > 0 && valueStringLength < 100) {
                    params.append(keyString).append("=").append(valueString);
                }

                if (attrs.hasMoreElements()) {
                    params.append(", ");
                }
            }
        }
        return params.toString();
    }

    @Override
    public void setMethodDescriptor(MethodDescriptor descriptor) {
        this.descriptor = descriptor;
        this.traceContext.cacheApi(descriptor);
    }


    @Override
    public void setTraceContext(TraceContext traceContext) {
        this.traceContext = traceContext;
    }
}
