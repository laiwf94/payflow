package io.github.laiwf94.payflow.webhook;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The shape an endpoint URL must have: absolute {@code http} or {@code https}, a host, no user info
 * and no fragment, at most {@value #MAX_LENGTH} printable characters. Scheme and host are
 * lowercased so one endpoint isn't stored two ways. Which addresses the host may resolve to is the
 * sender's check, made at registration and again on every send.
 */
public final class WebhookUrls {

  public static final int MAX_LENGTH = 2048;
  private static final Pattern PRINTABLE = Pattern.compile("[\\x21-\\x7e]+");

  private WebhookUrls() {}

  /**
   * The URL as stored.
   *
   * @throws IllegalArgumentException when it doesn't have the shape above
   */
  public static String normalize(String url) {
    if (url == null || url.length() > MAX_LENGTH || !PRINTABLE.matcher(url).matches()) {
      throw invalid("must be 1-" + MAX_LENGTH + " printable ASCII characters without spaces");
    }
    URI u;
    try {
      u = new URI(url);
    } catch (URISyntaxException e) {
      throw invalid("is not a valid URL");
    }
    String scheme = u.getScheme() == null ? null : u.getScheme().toLowerCase(Locale.ROOT);
    if (u.isOpaque() || !("https".equals(scheme) || "http".equals(scheme))) {
      throw invalid("must be an absolute http or https URL");
    }
    if (u.getRawUserInfo() != null) {
      throw invalid("must not carry a user name or password");
    }
    if (u.getHost() == null || u.getHost().isEmpty()) {
      throw invalid("must name a host");
    }
    if (u.getRawFragment() != null) {
      throw invalid("must not have a fragment");
    }
    if (u.getPort() == 0 || u.getPort() > 65_535) {
      throw invalid("port must be 1-65535");
    }
    StringBuilder out =
        new StringBuilder(scheme).append("://").append(u.getHost().toLowerCase(Locale.ROOT));
    if (u.getPort() > 0) {
      out.append(':').append(u.getPort());
    }
    out.append(u.getRawPath() == null ? "" : u.getRawPath());
    if (u.getRawQuery() != null) {
      out.append('?').append(u.getRawQuery());
    }
    return out.toString();
  }

  private static IllegalArgumentException invalid(String why) {
    return new IllegalArgumentException("url: " + why);
  }
}
