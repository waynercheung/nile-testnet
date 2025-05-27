package org.tron.core.services.http;

import static org.apache.http.entity.ContentType.APPLICATION_FORM_URLENCODED;
import static org.tron.core.services.http.Util.getJsonString;

import java.io.BufferedReader;
import javax.servlet.http.HttpServletRequest;
import lombok.Getter;

public class PostParams {

  public static final String S_VALUE = "value";

  @Getter
  private String params;
  @Getter
  private boolean visible;

  public PostParams(String params, boolean visible) {
    this.params = params;
    this.visible = visible;
  }

  public static PostParams getPostParams(HttpServletRequest request) throws Exception {
    // String input = request.getReader().lines().collect(Collectors.joining(System.lineSeparator()));

    StringBuilder builder = new StringBuilder();
    try (BufferedReader reader = request.getReader()) {
      char[] buffer = new char[8192];
      int charsRead;
      while ((charsRead = reader.read(buffer)) != -1) {
        builder.append(buffer, 0, charsRead);
      }
    }
    String input = builder.toString();

    Util.checkBodySize(input);
    if (APPLICATION_FORM_URLENCODED.getMimeType().equals(request.getContentType())) {
      input = getJsonString(input);
    }
    boolean visible = Util.getVisiblePost(input);
    return new PostParams(input, visible);
  }
}
