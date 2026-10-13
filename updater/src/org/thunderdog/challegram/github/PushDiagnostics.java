package org.thunderdog.challegram.github;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Describes push failures without changing registration or the original exception. */
public final class PushDiagnostics {
  private static final int MAX_CAUSE_DEPTH = 16;
  private static final Pattern FIREBASE_ERROR_CODE = Pattern.compile("(?<=: )[A-Z_]+$");

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
      return matcher.find() ? matcher.group() : detail;
    }

    String name = current.getClass().getSimpleName();
    return hasText(name) ? name : current.getClass().getName();
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
