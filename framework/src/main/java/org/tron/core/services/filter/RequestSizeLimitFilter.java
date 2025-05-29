package org.tron.core.services.filter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.stereotype.Component;

@Component
@Slf4j(topic = "API")
public class RequestSizeLimitFilter implements Filter {

  public static final int MAX_REQUEST_SIZE = 5 * 1024 * 1024; // 5MB
  private static final long LOG_INTERVAL = 1024 * 1024L; // Log every 1MB for large requests

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    logger.info("RequestSizeLimitFilter initialized with max size: {} bytes ({} MB)",
        MAX_REQUEST_SIZE, MAX_REQUEST_SIZE / (1024 * 1024));
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {

    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;

    // Only process methods with request body
    if (!hasRequestBody(httpRequest.getMethod())) {
      chain.doFilter(request, response);
      return;
    }

    try {
      // Pre-check Content-Length header
      if (!preCheckContentLength(httpRequest, httpResponse)) {
        return;
      }

      SizeLimitedRequestWrapper wrappedRequest = new SizeLimitedRequestWrapper(httpRequest);
      chain.doFilter(wrappedRequest, response);

    } catch (RequestTooLargeException e) {
      if (!httpResponse.isCommitted()) {
        logger.warn("Request body size exceeded limit: {} bytes (limit: {} bytes)",
            e.getActualSize(), MAX_REQUEST_SIZE);
        sendErrorResponse(httpResponse, "Request body too large", e.getActualSize());
      }
    } catch (IOException e) {
      logger.error("IO error in RequestSizeLimitFilter: {}", e.getMessage());
      if (!httpResponse.isCommitted()) {
        sendErrorResponse(httpResponse, "Request processing failed", 0);
      }
    } catch (Exception e) {
      logger.error("Unexpected error in RequestSizeLimitFilter", e);
      if (!httpResponse.isCommitted()) {
        sendErrorResponse(httpResponse, "Request processing failed", 0);
      }
    }
  }

  private boolean hasRequestBody(String method) {
    return "POST".equalsIgnoreCase(method) ||
        "PUT".equalsIgnoreCase(method) ||
        "PATCH".equalsIgnoreCase(method);
  }

  /**
   * Pre-check Content-Length header to reject obviously over-sized requests early
   */
  private boolean preCheckContentLength(HttpServletRequest request, HttpServletResponse response)
      throws IOException {

    String contentLengthHeader = request.getHeader("Content-Length");
    if (contentLengthHeader != null && !contentLengthHeader.trim().isEmpty()) {
      try {
        long contentLength = Long.parseLong(contentLengthHeader.trim());
        if (contentLength > MAX_REQUEST_SIZE) {
          logger.warn("Request rejected by Content-Length header: {} bytes (limit: {} bytes)",
              contentLength, MAX_REQUEST_SIZE);
          sendErrorResponse(response, "Request Content-Length exceeds limit", contentLength);
          return false;
        }
        if (contentLength < 0) {
          logger.warn("Invalid negative Content-Length: {}", contentLength);
          sendErrorResponse(response, "Invalid Content-Length header", 0);
          return false;
        }
      } catch (NumberFormatException e) {
        logger.warn("Invalid Content-Length header format: '{}'", contentLengthHeader);
        sendErrorResponse(response, "Invalid Content-Length header", 0);
        return false;
      }
    }
    return true;
  }

  private void sendErrorResponse(HttpServletResponse response, String message, long actualSize)
      throws IOException {

    if (response.isCommitted()) {
      logger.debug("Cannot send error response - response already committed");
      return;
    }

    response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
    response.setContentType("application/json; charset=UTF-8");
    response.setHeader("Connection", "close");

    String jsonResponse = String.format(
        "{\"error\":\"Request too large\",\"message\":\"%s\",\"maxSize\":%d,\"actualSize\":%d,\"code\":413}",
        escapeJson(message), MAX_REQUEST_SIZE, actualSize);

    response.getWriter().write(jsonResponse);
    response.getWriter().flush();
  }

  /**
   * Simple JSON string escaping to prevent injection
   */
  private String escapeJson(String input) {
    if (input == null) return "";
    return input.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  @Override
  public void destroy() {
  }

  public static class RequestTooLargeException extends IOException {
    private final long actualSize;

    public RequestTooLargeException(String message, long actualSize) {
      super(message);
      this.actualSize = actualSize;
    }

    public long getActualSize() {
      return actualSize;
    }
  }

  /**
   * Request wrapper that provides size-limited InputStream and Reader
   */
  private static class SizeLimitedRequestWrapper extends HttpServletRequestWrapper {
    private volatile SizeLimitedInputStream sizeLimitedInputStream;
    private volatile BufferedReader bufferedReader;
    private final Object streamLock = new Object();
    private final Object readerLock = new Object();

    public SizeLimitedRequestWrapper(HttpServletRequest request) {
      super(request);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      // Double-checked locking pattern with separate lock object
      if (sizeLimitedInputStream == null) {
        synchronized (streamLock) {
          if (sizeLimitedInputStream == null) {
            ServletInputStream originalStream = super.getInputStream();
            sizeLimitedInputStream = new SizeLimitedInputStream(originalStream);
          }
        }
      }
      return sizeLimitedInputStream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
      if (bufferedReader == null) {
        synchronized (readerLock) {
          if (bufferedReader == null) {
            String encoding = getCharacterEncoding();
            if (encoding == null || encoding.trim().isEmpty()) {
              encoding = "UTF-8";
            }
            bufferedReader = new BufferedReader(
                new InputStreamReader(getInputStream(), encoding));
          }
        }
      }
      return bufferedReader;
    }
  }

  private static class SizeLimitedInputStream extends ServletInputStream {
    private final ServletInputStream originalStream;
    private final AtomicLong totalBytesRead = new AtomicLong(0);
    private final AtomicBoolean limitExceeded = new AtomicBoolean(false);
    private volatile boolean finished = false;
    private volatile long lastLoggedSize = 0;

    public SizeLimitedInputStream(ServletInputStream originalStream) {
      this.originalStream = originalStream;
    }

    @Override
    public int read() throws IOException {
      if (limitExceeded.get()) {
        throw createLimitExceededException();
      }

      int data = originalStream.read();
      if (data == -1) {
        finished = true;
        return -1;
      }

      // Check size limit with minimal overhead
      long newSize = totalBytesRead.incrementAndGet();
      checkSizeLimit(newSize);
      logProgressIfNeeded(newSize);
      return data;
    }

    @Override
    public int read(byte[] b) throws IOException {
      return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      // Parameter validation with minimal overhead
      if (b == null) {
        throw new NullPointerException("Buffer cannot be null");
      }
      if (off < 0 || len < 0 || len > b.length - off) {
        throw new IndexOutOfBoundsException("Invalid buffer parameters");
      }
      if (len == 0) {
        return 0;
      }

      // already exceeded limit, throw exception
      if (limitExceeded.get()) {
        throw createLimitExceededException();
      }

      // Calculate maximum safe read length to prevent overshooting
      long currentSize = totalBytesRead.get();
      long remainingBytes = MAX_REQUEST_SIZE - currentSize;

      if (remainingBytes <= 0) {
        limitExceeded.set(true);
        throw createLimitExceededException();
      }

      // Limit read length to prevent reading beyond the limit
      int safeReadLength = (int) Math.min(len, remainingBytes);

      int bytesRead = originalStream.read(b, off, safeReadLength);
      if (bytesRead == -1) {
        finished = true;
        return -1;
      }

      if (bytesRead > 0) {
        long newSize = totalBytesRead.addAndGet(bytesRead);
        checkSizeLimit(newSize);
        logProgressIfNeeded(newSize);
      }

      return bytesRead;
    }

    @Override
    public long skip(long n) throws IOException {
      if (n <= 0) {
        return 0;
      }

      if (limitExceeded.get()) {
        throw createLimitExceededException();
      }

      long currentSize = totalBytesRead.get();
      if (currentSize + n > MAX_REQUEST_SIZE) {
        limitExceeded.set(true);
        throw createLimitExceededException();
      }

      long skipped = originalStream.skip(n);
      if (skipped > 0) {
        long newSize = totalBytesRead.addAndGet(skipped);
        logProgressIfNeeded(newSize);
      }
      return skipped;
    }

    @Override
    public int available() throws IOException {
      return originalStream.available();
    }

    @Override
    public void close() throws IOException {
      finished = true;
      originalStream.close();
    }

    @Override
    public boolean isFinished() {
      return finished || originalStream.isFinished();
    }

    @Override
    public boolean isReady() {
      return !limitExceeded.get() && originalStream.isReady();
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      originalStream.setReadListener(readListener);
    }

    private void checkSizeLimit(long currentSize) throws RequestTooLargeException {
      if (currentSize > MAX_REQUEST_SIZE && limitExceeded.compareAndSet(false, true)) {
        throw createLimitExceededException();
      }
    }

    private RequestTooLargeException createLimitExceededException() {
      return new RequestTooLargeException(
          "Request body size exceeds limit of " + MAX_REQUEST_SIZE + " bytes",
          totalBytesRead.get());
    }

    private void logProgressIfNeeded(long currentSize) {
      if (logger.isDebugEnabled() && currentSize - lastLoggedSize >= LOG_INTERVAL) {
        logger.debug("Large request reading progress: {} MB", currentSize / (1024 * 1024));
        lastLoggedSize = currentSize;
      }
    }
  }
}