package org.tron.core.services.filter;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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

    int contentLength = httpRequest.getContentLength();
    if (contentLength > maxSize) {
      logger.warn("Request rejected due to Content-Length: {} bytes (limit: {} bytes)",
          contentLength, maxSize);
      sendErrorResponse(httpResponse, "Request too large based on Content-Length header");
      return;
    }

    try {
      HttpServletRequestWrapper wrappedRequest = new HttpServletRequestWrapper(httpRequest) {
        private ServletInputStream wrappedInputStream;
        private BufferedReader wrappedReader;

        @Override
        public ServletInputStream getInputStream() throws IOException {
          if (wrappedInputStream == null) {
            ServletInputStream originalStream = super.getInputStream();
            LimitedInputStream limitedStream = new LimitedInputStream(originalStream, maxSize);
            wrappedInputStream = new ServletInputStreamWrapper(limitedStream);
          }
          return wrappedInputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
          if (wrappedReader == null) {
            String encoding = getCharacterEncoding();
            if (encoding == null) {
              encoding = "UTF-8";
            }
            wrappedReader = new BufferedReader(
                new InputStreamReader(getInputStream(), encoding));
          }
          return wrappedReader;
        }
      };

      chain.doFilter(wrappedRequest, response);
    } catch (RequestSizeExceededException e) {
      sendErrorResponse(httpResponse, e.getMessage());
    } catch (IOException e) {
      logger.error("Request processing failed: {}", e.getMessage());
      sendErrorResponse(httpResponse, "Request body processing failed: " + e.getMessage());
    }
  }

  @Override
  public void destroy() {
    logger.info("RequestSizeLimitFilter destroyed");
  }

  private boolean isOurFilterException(IOException e) {
    // 检查异常堆栈中是否包含我们的过滤器
    return e.getMessage() != null &&
        (e.getMessage().contains("Request body exceeds limit") ||
            e.getMessage().contains("RequestSizeExceededException"));
  }

  private void sendErrorResponse(HttpServletResponse response, String message) {
    try {
      if (!response.isCommitted()) {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String jsonResponse = String.format(
            "{\"Error\": \"%s\", \"MaxSize\": %d, \"Code\": 413}",
            message, maxSize);

        response.getWriter().write(jsonResponse);
        response.getWriter().flush();
      }
    } catch (IOException e) {
      logger.error("Failed to send error response: {}", e.getMessage());
    }
  }

  private static class RequestSizeExceededException extends IOException {
    private final long actualSize;

    public RequestSizeExceededException(String message, long actualSize) {
      super(message);
      this.actualSize = actualSize;
    }

    public long getActualSize() {
      return actualSize;
    }
  }

  private static final class LimitedInputStream extends InputStream {
    private final InputStream in;
    private long totalRead = 0;
    private final long maxSize;
    private boolean sizeExceeded = false;

    public LimitedInputStream(InputStream in, long maxSize) {
      this.in = in;
      this.maxSize = maxSize;
    }

    @Override
    public int read() throws IOException {
      if (sizeExceeded) {
        throw new RequestSizeExceededException(
            "Request body exceeds limit of " + maxSize + " bytes", totalRead);
      }

      int data = in.read();
      if (data != -1) {
        totalRead++;
        if (totalRead > maxSize) {
          sizeExceeded = true;
          throw new RequestSizeExceededException(
              "Request body exceeds limit of " + maxSize + " bytes", totalRead);
        }
      }
      return data;
    }

    @Override
    public int read(byte[] b) throws IOException {
      return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (sizeExceeded) {
        throw new RequestSizeExceededException(
            "Request body exceeds limit of " + maxSize + " bytes", totalRead);
      }

      int bytesRead = in.read(b, off, len);
      if (bytesRead > 0) {
        totalRead += bytesRead;
        if (totalRead > maxSize) {
          sizeExceeded = true;
          throw new RequestSizeExceededException(
              "Request body exceeds limit of " + maxSize + " bytes", totalRead);
        }
      }
      return bytesRead;
    }

    @Override
    public long skip(long n) throws IOException {
      if (sizeExceeded) {
        throw new RequestSizeExceededException(
            "Request body exceeds limit of " + maxSize + " bytes", totalRead);
      }

      long skipped = in.skip(n);
      if (skipped > 0) {
        totalRead += skipped;
        if (totalRead > maxSize) {
          sizeExceeded = true;
          throw new RequestSizeExceededException(
              "Request body exceeds limit of " + maxSize + " bytes", totalRead);
        }
      }
      return skipped;
    }

    @Override
    public int available() throws IOException {
      return in.available();
    }

    @Override
    public void close() throws IOException {
      in.close();
    }
  }

  private static final class ServletInputStreamWrapper extends ServletInputStream {
    private final InputStream inputStream;
    private boolean finished = false;

    public ServletInputStreamWrapper(InputStream inputStream) {
      this.inputStream = inputStream;
    }

    @Override
    public int read() throws IOException {
      int data = inputStream.read();
      if (data == -1) {
        finished = true;
      }
      return data;
    }

    @Override
    public int read(byte[] b) throws IOException {
      int bytesRead = inputStream.read(b);
      if (bytesRead == -1) {
        finished = true;
      }
      return bytesRead;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int bytesRead = inputStream.read(b, off, len);
      if (bytesRead == -1) {
        finished = true;
      }
      return bytesRead;
    }

    @Override
    public boolean isFinished() {
      return finished;
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