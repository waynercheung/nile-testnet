package org.tron.core.services.filter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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

  public static final long MAX_REQUEST_SIZE = 5 * 1024 * 1024L; // 10MB
  private static final int BUFFER_SIZE = 8192; // 8KB buffer for efficient reading
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

    // 只处理POST, PUT, PATCH等有请求体的方法
    if (!hasRequestBody(httpRequest.getMethod())) {
      chain.doFilter(request, response);
      return;
    }

    try {
      if (!preCheckContentLength(httpRequest, httpResponse)) {
        return;
      }

      SizeLimitedRequestWrapper wrappedRequest = new SizeLimitedRequestWrapper(httpRequest);
      chain.doFilter(wrappedRequest, response);

    } catch (RequestTooLargeException e) {
      logger.warn("Request body size exceeded limit: {} bytes", e.getActualSize());
      sendErrorResponse(httpResponse, "Request body too large", e.getActualSize());
    } catch (IOException e) {
      logger.error("IO Error in RequestSizeLimitFilter: {}", e.getMessage());
      sendErrorResponse(httpResponse, "Request processing failed", 0);
    } catch (Exception e) {
      logger.error("Error in RequestSizeLimitFilter: {}", e.getMessage(), e);
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

  private boolean preCheckContentLength(HttpServletRequest request, HttpServletResponse response)
      throws IOException {

    String contentLengthHeader = request.getHeader("Content-Length");
    if (contentLengthHeader != null) {
      try {
        long contentLength = Long.parseLong(contentLengthHeader);
        if (contentLength > MAX_REQUEST_SIZE) {
          logger.warn("Request rejected by Content-Length: {} bytes (limit: {} bytes) ",
              contentLength, MAX_REQUEST_SIZE);
          sendErrorResponse(response, "Request Content-Length exceeds limit", contentLength);
          return false;
        }
      } catch (NumberFormatException e) {
        logger.warn("Invalid Content-Length header: {} ", contentLengthHeader);
        sendErrorResponse(response, "Invalid Content-Length header", 0);
        return false;
      }
    }
    return true;
  }

  private void sendErrorResponse(HttpServletResponse response, String message, long actualSize)
      throws IOException {

    if (response.isCommitted()) {
      logger.warn("Cannot send error response - response already committed");
      return;
    }

    response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
    response.setContentType("application/json; charset=utf-8");
    response.setHeader("Connection", "close"); // 关闭连接以停止数据传输

    String jsonResponse = String.format(
        "{\"error\":\"Request too large\",\"message\":\"%s\",\"maxSize\":%d,\"actualSize\":%d,\"code\":413}",
        message, MAX_REQUEST_SIZE, actualSize);

    response.getWriter().write(jsonResponse);
    response.getWriter().flush();
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

  private static class SizeLimitedRequestWrapper extends HttpServletRequestWrapper {
    private SizeLimitedInputStream sizeLimitedInputStream;
    private BufferedReader bufferedReader;

    public SizeLimitedRequestWrapper(HttpServletRequest request) {
      super(request);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (sizeLimitedInputStream == null) {
        ServletInputStream originalStream = super.getInputStream();
        sizeLimitedInputStream = new SizeLimitedInputStream(originalStream);
      }
      return sizeLimitedInputStream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
      if (bufferedReader == null) {
        String encoding = getCharacterEncoding();
        if (encoding == null) {
          encoding = "UTF-8";
        }
        bufferedReader = new BufferedReader(new InputStreamReader(getInputStream(), encoding));
      }
      return bufferedReader;
    }
  }

  private static class SizeLimitedInputStream extends ServletInputStream {
    private final ServletInputStream originalStream;
    private final AtomicLong totalBytesRead = new AtomicLong(0);
    private boolean finished = false;
    private long lastLoggedSize = 0;

    public SizeLimitedInputStream(ServletInputStream originalStream) {
      this.originalStream = originalStream;
    }

    @Override
    public int read() throws IOException {
      checkSizeLimit(1);

      int data = originalStream.read();
      if (data == -1) {
        finished = true;
      } else {
        long currentSize = totalBytesRead.incrementAndGet();
        logProgressIfNeeded(currentSize);
      }
      return data;
    }

    @Override
    public int read(byte[] b) throws IOException {
      return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      try {
        checkSizeLimit(len);
      } catch (Exception e) {
        logger.info("Error is {}", e.getMessage());
      }

      int bytesRead = originalStream.read(b, off, len);
      if (bytesRead == -1) {
        finished = true;
      } else if (bytesRead > 0) {
        long currentSize = totalBytesRead.addAndGet(bytesRead);

        // 再次检查实际读取的字节数
        if (currentSize > MAX_REQUEST_SIZE) {
          throw new RequestTooLargeException(
              "1 Request body size " + currentSize + " exceeds limit of " + MAX_REQUEST_SIZE + " bytes",
              currentSize);
        }

        logProgressIfNeeded(currentSize);
      }
      return bytesRead;
    }

    @Override
    public long skip(long n) throws IOException {
      long currentSize = totalBytesRead.get();
      if (currentSize + n > MAX_REQUEST_SIZE) {
        throw new RequestTooLargeException(
            "2 Request body size would exceed limit after skipping " + n + " bytes",
            currentSize + n);
      }

      long skipped = originalStream.skip(n);
      if (skipped > 0) {
        totalBytesRead.addAndGet(skipped);
      }
      return skipped;
    }

    @Override
    public int available() throws IOException {
      return originalStream.available();
    }

    @Override
    public void close() throws IOException {
      originalStream.close();
    }

    @Override
    public boolean isFinished() {
      return finished || originalStream.isFinished();
    }

    @Override
    public boolean isReady() {
      return originalStream.isReady();
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      originalStream.setReadListener(readListener);
    }

    private void checkSizeLimit(int aboutToRead) throws RequestTooLargeException {
      long currentSize = totalBytesRead.get();
      if (currentSize + aboutToRead > MAX_REQUEST_SIZE) {
        logger.info("3 Request body size {} exceeds limit of " + MAX_REQUEST_SIZE + " bytes",
            currentSize + aboutToRead, currentSize + aboutToRead);
        throw new RequestTooLargeException(
            "3 Request body size " + (currentSize + aboutToRead) + " exceeds limit of " + MAX_REQUEST_SIZE + " bytes",
            currentSize + aboutToRead);
      }
    }

    // just for test
    private void logProgressIfNeeded(long currentSize) {
      if (currentSize - lastLoggedSize >= LOG_INTERVAL) {
        logger.debug("Large request in progress: {} MB read", currentSize / (1024 * 1024));
        lastLoggedSize = currentSize;
      }
    }
  }
}