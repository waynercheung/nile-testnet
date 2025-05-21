package org.tron.core.services.filter;


import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j(topic = "API")
public class RequestSizeLimitFilter implements Filter {
  private final int maxRequestSize = 10 * 1024 * 1024; // 10 MB

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    logger.info("Initializing RequestSizeLimitFilter with max size: " + maxRequestSize + " bytes");
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpRequest = (HttpServletRequest) request;
    int contentLength = httpRequest.getContentLength();

    if (contentLength > maxRequestSize) {
      logger.warn("拒绝过大请求: " + contentLength + " bytes, URI: " + httpRequest.getRequestURI());
      HttpServletResponse httpResponse = (HttpServletResponse) response;
      httpResponse.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
      httpResponse.setContentType("application/json");
      httpResponse.getWriter().write("{\"error\": \"Request entity too large\", \"maxSize\": " + maxRequestSize + "}");
      return;
    }

    chain.doFilter(request, response);
  }

  @Override
  public void destroy() {
    // 清理资源
  }
}