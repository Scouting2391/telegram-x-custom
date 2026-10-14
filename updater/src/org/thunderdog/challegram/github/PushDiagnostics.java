package org.thunderdog.challegram.github;

import java.util.Collections;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Describes push failures without changing registration or the original exception. */
public final class PushDiagnostics {
  private static final int MAX_CAUSE_DEPTH = 16;
  private static final Pattern FIREBASE_ERROR_CODE = Pattern.compile("(?<=: )[A-Z_]+$");
  private static final Pattern HTTP_CODE = Pattern.compile("HTTP response: \\[([0-9]{3}) ");
  private static final Pattern ERROR_FIELD = Pattern.compile("\"(?:status|reason)\"\\s*:\\s*\"([A-Z_]{1,80})\"");
  private static final Set<String> SAFE_CODES = new HashSet<>(Arrays.asList(
    "PERMISSION_DENIED", "INVALID_ARGUMENT", "UNAUTHENTICATED", "NOT_FOUND",
    "RESOURCE_EXHAUSTED", "UNAVAILABLE", "INTERNAL", "DEADLINE_EXCEEDED",
    "FAILED_PRECONDITION", "ALREADY_EXISTS", "ABORTED", "OUT_OF_RANGE",
    "UNIMPLEMENTED", "DATA_LOSS", "CANCELLED", "API_KEY_INVALID", "API_KEY_EXPIRED",
    "API_KEY_NOT_FOUND", "API_KEY_ANDROID_APP_BLOCKED", "API_KEY_SERVICE_BLOCKED",
    "API_KEY_HTTP_REFERRER_BLOCKED", "API_KEY_IP_ADDRESS_BLOCKED",
    "API_KEY_APP_BLOCKED", "API_KEY_PROJECT_BLOCKED", "CONSUMER_INVALID",
    "SERVICE_DISABLED", "SERVICE_NOT_ACTIVATED", "BILLING_DISABLED",
    "PROJECT_INVALID", "PROJECT_DELETED", "RATE_LIMIT_EXCEEDED"));
  private static final long MAX_FAILURE_AGE_NANOS = 15L * 60L * 1000000000L;
  private static final AtomicReference<Failure> LAST_FIREBASE_FAILURE = new AtomicReference<>();

  private PushDiagnostics() {
  }

  /**
   * Unwraps generic FCM/async wrappers for the visible error summary.
   * The caller must retain the original throwable for the full error report.
   */
  public static String describe(Throwable error) {
    if (error == null) {
      return "Unknown Firebase registration error";
    }

    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    Throwable current = error;
    visited.add(current);
    String detail = hasText(current.getMessage()) ? current.getMessage() : null;

    for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
      Throwable cause = current.getCause();
      if (cause == null || !visited.add(cause)) {
        break;
      }
      current = cause;
      String message = current.getMessage();
      if (hasText(message)) {
        detail = message;
      }
    }

    if (detail != null) {
      Matcher matcher = FIREBASE_ERROR_CODE.matcher(detail);
      String summary = matcher.find() ? matcher.group() : detail;
      if (isFirebaseSummary(summary)) {
        Failure last = LAST_FIREBASE_FAILURE.get();
        if (last != null) {
          long age = System.nanoTime() - last.time;
          if (age >= 0 && age <= MAX_FAILURE_AGE_NANOS)
            return summary + " [Last Firebase failure: " + last.description + "]";
          LAST_FIREBASE_FAILURE.compareAndSet(last, null);
        }
        return summary + " [Firebase diagnostics v3: no recent HTTP/transport failure captured]";
      }
      return summary;
    }

    String name = current.getClass().getSimpleName();
    return hasText(name) ? name : current.getClass().getName();
  }

  /** Records the SDK's returned status even when its optional error body cannot be read. */
  public static void recordFirebaseHttpStatus(int status) {
    try {
      if (status >= 200 && status < 300) {
        LAST_FIREBASE_FAILURE.set(null);
      } else if (status >= 300 && status <= 599) {
        LAST_FIREBASE_FAILURE.set(new Failure("HTTP " + status, System.nanoTime()));
      }
    } catch (RuntimeException ignored) {
      // Keep the original network result and retry behavior.
    }
  }

  /** Reads the SDK's already-built log string, never the connection or raw device logs. */
  public static void recordFirebaseResponse(String sdkLog) {
    try {
      if (sdkLog == null) return;
      int limit = Math.min(sdkLog.length(), 65536);
      Matcher code = HTTP_CODE.matcher(sdkLog).region(0, limit);
      if (!code.find()) return;
      int status = Integer.parseInt(code.group(1));
      if (status < 300 || status > 599) return;
      StringBuilder safe = new StringBuilder("HTTP ").append(status);
      Set<String> added = new HashSet<>();
      Matcher fields = ERROR_FIELD.matcher(sdkLog).region(0, limit);
      while (fields.find() && added.size() < 4) {
        String value = fields.group(1);
        if (SAFE_CODES.contains(value) && added.add(value)) safe.append("; ").append(value);
      }
      LAST_FIREBASE_FAILURE.set(new Failure(safe.toString(), System.nanoTime()));
    } catch (RuntimeException ignored) {
      // Diagnostic collection must not change Firebase's result or retry behavior.
    }
  }

  /** Retains only a known transport category; exception text may contain private URLs. */
  public static void recordFirebaseTransportFailure(Throwable error) {
    try {
      if (error == null) return;
      Throwable current = error;
      Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
      String category = "network error (IOException)";
      for (int depth = 0; depth <= MAX_CAUSE_DEPTH && current != null && seen.add(current); depth++) {
        if (current instanceof java.net.UnknownHostException) category = "DNS lookup failed (UnknownHostException)";
        else if (current instanceof java.net.SocketTimeoutException) category = "connection timed out (SocketTimeoutException)";
        else if (current instanceof java.net.ConnectException) category = "connection failed (ConnectException)";
        else if (current instanceof javax.net.ssl.SSLHandshakeException) category = "TLS handshake failed (SSLHandshakeException)";
        else if (current instanceof javax.net.ssl.SSLException) category = "TLS connection failed (SSLException)";
        current = current.getCause();
      }
      LAST_FIREBASE_FAILURE.set(new Failure(category, System.nanoTime()));
    } catch (RuntimeException ignored) {
      // Do not propagate failures from diagnostic collection.
    }
  }

  private static boolean isFirebaseSummary(String value) {
    return value.equals("Firebase Installations Service is unavailable. Please try again later.") ||
      value.equals("FCM Registration failed!") || value.equals("BAD CONFIG") ||
      value.equals("BAD_CONFIG") || value.equals("FIREBASE_REQUEST_ERROR") ||
      value.startsWith("Firebase servers have received too many requests");
  }

  private static final class Failure {
    final String description;
    final long time;
    Failure(String description, long time) { this.description = description; this.time = time; }
  }

  private static boolean hasText(String value) {
    if (value == null) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (!Character.isWhitespace(value.charAt(i))) {
        return true;
      }
    }
    return false;
  }
}
