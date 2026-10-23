package org.thunderdog.challegram.github;

import android.content.Context;
import android.content.SharedPreferences;

/** Presentation-only grace period. Never changes the actual push/token state. */
public final class PushWarningPolicy {
  private static final long GRACE_MILLIS = 15L * 60 * 1000;
  private static SharedPreferences storage;
  private static long firstFailureAt;
  private static boolean transientFailure, observed, discardSaved;

  private PushWarningPolicy() { }

  public static synchronized void initialize(Context context) {
    if (storage != null) return;
    try {
      storage = context.getSharedPreferences("personal_push_warning_v1", Context.MODE_PRIVATE);
      long saved = storage.getLong("first_failure_at", 0);
      boolean savedTransient = storage.getBoolean("transient_failure", false);
      if (!observed) {
        firstFailureAt = saved;
        transientFailure = savedTransient;
      } else if (!discardSaved && transientFailure && savedTransient && saved > 0) {
        firstFailureAt = firstFailureAt > 0 ? Math.min(firstFailureAt, saved) : saved;
      }
      persist();
    } catch (RuntimeException ignored) {
      transientFailure = false;
      firstFailureAt = 0;
    }
  }

  public static synchronized void observeTokenState(int state, String summary) {
    try { observeAt(state, summary, System.currentTimeMillis()); }
    catch (RuntimeException ignored) { transientFailure = false; firstFailureAt = 0; }
  }

  private static void observeAt(int state, String summary, long now) {
    observed = true;
    if (state == 1) {
      transientFailure = isTemporary(summary);
      if (transientFailure) {
        if (firstFailureAt <= 0) firstFailureAt = now;
      } else firstFailureAt = 0;
    } else if (state == 0 || state == 3) {
      firstFailureAt = 0;
      transientFailure = false;
      discardSaved = true;
    } else if (state != 2) {
      firstFailureAt = 0;
      transientFailure = false;
    }
    // INITIALIZING (2) is a retry, not evidence that registration recovered.
    persist();
  }

  private static boolean isTemporary(String value) {
    if (value == null) return false;
    boolean generic = value.equals("SERVICE_NOT_AVAILABLE") || value.equals("UNAVAILABLE") ||
      value.equals("FCM Registration failed!") ||
      value.startsWith("Firebase Installations Service is unavailable. Please try again later.");
    if (!generic) return false;
    return !value.contains("PERMISSION_DENIED") && !value.contains("BAD_CONFIG") &&
      !value.contains("API_KEY_") && !value.contains("HTTP 4") && !value.contains("TLS ");
  }

  /** Only the two settings screens call this; core status and actions remain raw. */
  public static synchronized int filterStatus(int status) {
    return filterAt(status, System.currentTimeMillis());
  }

  private static int filterAt(int status, long now) {
    long age = now - firstFailureAt;
    return status == 8 && transientFailure && firstFailureAt > 0 && age >= 0 && age < GRACE_MILLIS ? 0 : status;
  }

  private static void persist() {
    if (storage != null) storage.edit().putLong("first_failure_at", firstFailureAt)
      .putBoolean("transient_failure", transientFailure).apply();
  }
}
