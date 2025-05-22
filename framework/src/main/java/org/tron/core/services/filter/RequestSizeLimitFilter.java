package org.tron.core.services.filter;


import java.io.IOException;
import java.io.InputStream;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
@Slf4j(topic = "API")
public class RequestSizeLimitFilter implements Filter {
  private final int maxSize = 10 * 1024 * 1024; // 10 MB

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    logger.info("Initializing RequestSizeLimitFilter with max size: " + maxSize + " bytes");
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;

    // check if POST
    String method = httpRequest.getMethod();
    if (HttpMethod.PUT.toString().equalsIgnoreCase(method)
        || HttpMethod.PATCH.toString().equalsIgnoreCase(method)) {
      return;
    } else if (!HttpMethod.POST.toString().equalsIgnoreCase(method)) {
      chain.doFilter(request, response);
      return;
    }

    try {
      ServletInputStream originalStream = httpRequest.getInputStream();
      InputStream wrappedStream = new LimitedInputStream(originalStream, maxSize, httpResponse);

      chain.doFilter(new HttpServletRequestWrapper(httpRequest) {
        @Override
        public ServletInputStream getInputStream() throws IOException {
          return new ServletInputStreamWrapper(wrappedStream);
        }
      }, response);
    } catch (RequestSizeExceededException e) {
      sendErrorResponse(httpResponse, e.getMessage());
    }
  }

  @Override
  public void destroy() {
  }

  private void sendErrorResponse(HttpServletResponse response, String message) throws IOException {
    response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
    response.setContentType("application/json");
    response.getWriter().println("{\"error\": \"" + message + "\", \"maxSize\": " + maxSize + "}");
  }

  private static class RequestSizeExceededException extends IOException {
    public RequestSizeExceededException(String message) {
      super(message);
    }
  }

  private static class LimitedInputStream extends InputStream {
    private final InputStream in;
    private int totalRead = 0;
    private final int maxSize;
    private final HttpServletResponse response;

    public LimitedInputStream(InputStream in, int maxSize, HttpServletResponse response) {
      this.in = in;
      this.maxSize = maxSize;
      this.response = response;
    }

    @Override
    public int read() throws IOException {
      int data = in.read();
      if (data != -1) {
        totalRead++;
        checkSize();
      }
      return data;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int read = in.read(b, off, len);
      if (read != -1) {
        totalRead += read;
        checkSize();
      }
      return read;
    }

    private void checkSize() throws RequestSizeExceededException {
      if (totalRead > maxSize) {
        throw new RequestSizeExceededException("Request body exceeds limit of " + maxSize + " bytes");
      }
    }
  }

  private static class ServletInputStreamWrapper extends ServletInputStream {
    private final InputStream in;

    public ServletInputStreamWrapper(InputStream in) {
      this.in = in;
    }

    @Override
    public int read() throws IOException {
      return in.read();
    }

    @Override
    public boolean isFinished() {
      return false;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
    }
  }

}