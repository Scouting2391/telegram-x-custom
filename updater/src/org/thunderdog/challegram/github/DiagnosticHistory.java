package org.thunderdog.challegram.github;

import java.util.ArrayList;
import java.util.List;

/** Bounded technical events only. No throwable text, tokens, accounts, or device IDs. */
public final class DiagnosticHistory {
  static final DiagnosticHistory GLOBAL = new DiagnosticHistory();
  static final long RETENTION = 7L * 24 * 60 * 60 * 1000;
  static final int LIMIT = 200;
  private final List<Event> events = new ArrayList<>();
  private long sequence, build;
  private Runnable listener;

  public synchronized void initialize(long build, String saved, long now, Runnable listener) {
    List<Event> pending = new ArrayList<>(events);
    events.clear(); sequence = 0; this.build = build;
    if (saved != null && saved.length() <= 40000) {
      String[] lines = saved.split("\n");
      try {
        if (!lines[0].startsWith("TGXDIAG1 ")) throw new IllegalArgumentException();
        sequence = number(lines[0].substring(9));
        for (int i = 1; i < lines.length; i++) {
          String[] p = lines[i].split("\\|", -1);
          if (p.length != 5) continue;
          Event e = new Event(number(p[0]), number(p[1]), number(p[2]), p[3], p[4]);
          if (e.id <= sequence && e.id > 0 && valid(e.kind, e.detail) &&
              e.time <= now + 300000 && now - e.time <= RETENTION &&
              (events.isEmpty() || e.id > events.get(events.size()-1).id)) events.add(e);
        }
      } catch (RuntimeException ignored) { events.clear(); sequence = 0; }
    }
    for (Event e : pending) add(e.kind, e.detail, e.time);
    trim(now);
    this.listener = listener;
  }

  public synchronized void record(String kind, String detail, long now) {
    if (!valid(kind, detail) || now <= 0 || sequence == Long.MAX_VALUE) return;
    trim(now);
    if (!events.isEmpty()) {
      Event last = events.get(events.size()-1);
      long age = now - last.time;
      if (last.kind.equals(kind) && last.detail.equals(detail) && age >= 0 && age < 60000) return;
    }
    add(kind, detail, now);
    if (listener != null) try { listener.run(); } catch (RuntimeException ignored) { }
  }

  public static void tokenState(int state) {
    String value = state == 0 ? "NONE" : state == 1 ? "ERROR" : state == 2 ? "INITIALIZING" : state == 3 ? "READY" : "OTHER";
    GLOBAL.record("TOKEN", value, System.currentTimeMillis());
  }

  static boolean valid(String kind, String detail) {
    if (kind == null || detail == null) return false;
    if (kind.equals("HTTP")) return detail.matches("[2-5][0-9]{2}");
    if (kind.equals("REASON")) return PushDiagnostics.safeReason(detail);
    if (kind.equals("TRANSPORT")) return detail.matches("DNS|TIMEOUT|CONNECT|TLS_HANDSHAKE|TLS|IO");
    if (kind.equals("TOKEN")) return detail.matches("NONE|ERROR|INITIALIZING|READY|OTHER");
    return false;
  }

  private void add(String kind, String detail, long now) {
    if (sequence == Long.MAX_VALUE) return;
    events.add(new Event(++sequence, now, build, kind, detail));
    while (events.size() > LIMIT) events.remove(0);
  }
  private void trim(long now) { events.removeIf(e -> now - e.time > RETENTION || e.time > now + 300000); }
  private static long number(String text) {
    if (!text.matches("[0-9]{1,19}")) throw new IllegalArgumentException();
    long n = Long.parseLong(text); if (n < 0) throw new IllegalArgumentException(); return n;
  }
  public synchronized long sequence() { return sequence; }
  public synchronized int size(long now) { trim(now); return events.size(); }
  public synchronized String serialize(long now) {
    trim(now);
    StringBuilder out = new StringBuilder("TGXDIAG1 ").append(sequence).append('\n');
    for (Event e : events) out.append(e.id).append('|').append(e.time).append('|').append(e.build)
      .append('|').append(e.kind).append('|').append(e.detail).append('\n');
    return out.toString();
  }
  public synchronized String report(int sdk, long now) {
    trim(now);
    StringBuilder out = new StringBuilder("Telegram X private push diagnostics v1\n\n")
      .append("Android API: ").append(Math.max(0, sdk)).append("\nCustom build: ").append(build)
      .append("\nReport sequence: ").append(sequence).append("\n\n")
      .append("Registration events only; READY is not proof that a notification was delivered.\n")
      .append("Latest 200 events within 7 days; repeated identical events within a minute are omitted.\n")
      .append("Times are UTC Unix milliseconds. No message text, account IDs, credentials, raw errors, or hardware identifiers.\n\n")
      .append("Sequence | UTC ms | Build | Event | Detail\n--- | --- | --- | --- | ---\n");
    for (Event e : events) out.append(e.id).append(" | ").append(e.time).append(" | ").append(e.build)
      .append(" | ").append(e.kind).append(" | ").append(e.detail).append('\n');
    return out.toString();
  }
  private static final class Event {
    final long id, time, build;
    final String kind, detail;
    Event(long id,long time,long build,String kind,String detail) {
      this.id=id; this.time=time; this.build=build; this.kind=kind; this.detail=detail;
    }
  }
}
