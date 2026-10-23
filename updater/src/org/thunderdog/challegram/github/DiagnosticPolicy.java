package org.thunderdog.challegram.github;

import java.net.URL;

/** Fail-closed rules for the private diagnostic channel, independent of update feeds. */
public final class DiagnosticPolicy {
  static final long INTERVAL = 60L * 60 * 1000;
  private DiagnosticPolicy() { }
  static String repository(String value) {
    if (value == null) throw new IllegalArgumentException("Enter the private inbox repository.");
    value = value.trim();
    if (value.startsWith("https://github.com/")) value = value.substring(19);
    if (value.endsWith("/")) value = value.substring(0, value.length()-1);
    if (!value.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?/[A-Za-z0-9_.-]{1,100}") ||
        value.endsWith("/.") || value.endsWith("/..")) throw new IllegalArgumentException("Use owner/repository for your private inbox.");
    if (value.equalsIgnoreCase("Scouting2391/telegram-x-custom") || value.equalsIgnoreCase("TGX-Android/Telegram-X"))
      throw new IllegalArgumentException("The app/update repository is public. Use a separate private inbox.");
    return value;
  }
  static void token(String value) {
    if (value == null || !value.matches("github_pat_[A-Za-z0-9_]{20,240}"))
      throw new IllegalArgumentException("Use a fine-grained GitHub token restricted to the private inbox (Issues: read/write).");
  }
  static void verifyPrivate(String expected, long pinnedId, String actual, long id, boolean isPrivate,
      boolean hasIssues, boolean archived) {
    if (!isPrivate || !hasIssues || archived || id <= 0 || !expected.equalsIgnoreCase(actual) || (pinnedId != 0 && pinnedId != id))
      throw new IllegalArgumentException("Inbox must remain private, have Issues enabled, and match the paired repository.");
  }
  static URL endpoint(String repo, String suffix) throws Exception {
    if (!repository(repo).equals(repo) || !suffix.matches("|/issues|/issues/[1-9][0-9]{0,9}|/issues\\?state=all&per_page=100&page=(?:[1-9]|10)"))
      throw new IllegalArgumentException("Invalid private inbox endpoint.");
    return new URL("https://api.github.com/repos/" + repo + suffix);
  }
  static boolean due(long now, long lastAttempt, long current, long acknowledged, boolean force) {
    if (!force && current <= acknowledged) return false;
    long interval = force ? 60000 : INTERVAL;
    return lastAttempt <= 0 || now < lastAttempt || now - lastAttempt >= interval;
  }
  static String failure(int status) {
    if (status == 401) return "Upload credential expired or was revoked. Pair again.";
    if (status == 403 || status == 429) return "GitHub denied or rate-limited the request. Check the inbox permission; retry later.";
    if (status == 404) return "Private inbox is unavailable to this credential. Check its repository access.";
    if (status >= 300 && status <= 399) return "Upload stopped: GitHub redirected the request. Pair the current repository again.";
    return "Upload failed (HTTP " + status + "). History stays on this phone for retry.";
  }
}
