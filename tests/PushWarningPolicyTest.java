import android.content.Context;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class PushWarningPolicyTest {
  static Class<?> policy;
  static int checks;
  static final String TEMP = "Firebase Installations Service is unavailable. Please try again later.";
  static final long START = 10000000L, GRACE = 15L * 60 * 1000;
  static void field(String name, Object value) throws Exception {
    Field f = policy.getDeclaredField(name); f.setAccessible(true); f.set(null, value);
  }
  static Object call(String name, Class<?>[] types, Object... args) throws Exception {
    Method m = policy.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(null, args);
  }
  static void state(int value, String summary, long now) throws Exception {
    call("observeAt", new Class<?>[]{int.class, String.class, long.class}, value, summary, now);
  }
  static Object status(int value, long now) throws Exception {
    return call("filterAt", new Class<?>[]{int.class, long.class}, value, now);
  }
  static void eq(Object expected, Object actual) {
    if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual); checks++;
  }
  static void reset() throws Exception {
    field("storage", null); field("firstFailureAt", 0L); field("transientFailure", false);
    field("observed", false); field("discardSaved", false);
  }
  public static void main(String[] args) throws Exception {
    policy = Class.forName("org.thunderdog.challegram.github.PushWarningPolicy");
    reset(); eq(8, status(8, START));
    state(1, TEMP, START);
    eq(0, status(8, START)); eq(0, status(8, START + GRACE - 1));
    eq(8, status(8, START + GRACE)); eq(8, status(8, START - 1));
    for (int s : new int[]{0,1,2,3,4,5,6,7,9,42}) eq(s, status(s, START));
    state(2, null, START + 1000); state(1, TEMP, START + 2000);
    eq(8, status(8, START + GRACE));
    state(3, null, START + GRACE); eq(8, status(8, START + GRACE));
    state(1, TEMP, START + GRACE + 1); eq(0, status(8, START + GRACE + 2));
    for (String error : new String[]{"BAD_CONFIG", "API_KEY_INVALID", "EXPERIMENTAL_BUILD_DETECTED",
      "Unknown failure", TEMP + " [Last Firebase failure: HTTP 403; PERMISSION_DENIED]",
      TEMP + " [Last Firebase failure: TLS handshake failed (SSLHandshakeException)]", null}) {
      state(1, error, START); eq(8, status(8, START));
    }
    state(1, "SERVICE_NOT_AVAILABLE", START); eq(0, status(8, START));

    UpdaterDefaultsTest.Preferences saved = new UpdaterDefaultsTest.Preferences();
    Context context = new Context(); context.preferences = saved.prefs;
    reset(); call("initialize", new Class<?>[]{Context.class}, context); state(1, TEMP, START);
    eq(START, saved.values.get("first_failure_at"));
    reset(); call("initialize", new Class<?>[]{Context.class}, context);
    eq(8, status(8, START + GRACE)); // Restart must not renew the grace period.
    reset(); state(1, TEMP, START + 1000); call("initialize", new Class<?>[]{Context.class}, context);
    eq(8, status(8, START + GRACE)); // Also preserve an error observed before activity creation.
    reset(); state(3, null, START + GRACE); state(1, TEMP, START + GRACE + 1);
    call("initialize", new Class<?>[]{Context.class}, context);
    eq(0, status(8, START + GRACE + 2)); // A real recovery starts a new episode.
    state(3, null, START + GRACE + 3); eq(0L, saved.values.get("first_failure_at"));
    eq(2, saved.values.size());
    eq(false, saved.values.containsValue(TEMP));
    reset(); saved.values.put("first_failure_at", "invalid preference type");
    call("initialize", new Class<?>[]{Context.class}, context); eq(8, status(8, START));

    state(1, TEMP, START); state(99, null, START); eq(8, status(8, START));
    System.out.println("PASS: " + checks + " warning presentation checks (no device UI test)");
  }
}
