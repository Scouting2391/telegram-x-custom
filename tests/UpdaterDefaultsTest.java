import android.content.SharedPreferences;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Exercises production preference initialization without Android UI or network access. */
public class UpdaterDefaultsTest {
  static int checks;
  static final String CHANNEL = "Scouting2391/telegram-x-custom";

  static class Preferences {
    final Map<String, Object> values = new HashMap<>();
    int writes;
    final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
      SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
        switch (method.getName()) {
          case "getString": case "getBoolean": return values.containsKey(args[0]) ? values.get(args[0]) : args[1];
          case "contains": return values.containsKey(args[0]);
          case "edit":
            Map<String, Object> pending = new HashMap<>();
            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
              new Class<?>[]{SharedPreferences.Editor.class}, (editor, editMethod, editArgs) -> {
                switch (editMethod.getName()) {
                  case "putString": case "putBoolean": pending.put((String) editArgs[0], editArgs[1]); return editor;
                  case "apply": values.putAll(pending); writes++; return null;
                  default: throw new AssertionError("Unexpected edit: " + editMethod.getName());
                }
              });
          default: throw new AssertionError("Unexpected preference operation: " + method.getName());
        }
      });
  }

  static void initialize(Preferences prefs) throws Exception {
    Method method = Class.forName("org.thunderdog.challegram.github.GitHubUpdater")
      .getDeclaredMethod("initializeDefaults", SharedPreferences.class);
    method.setAccessible(true);
    method.invoke(null, prefs.prefs);
  }

  static void eq(Object expected, Object actual) {
    if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    checks++;
  }

  public static void main(String[] args) throws Exception {
    Preferences fresh = new Preferences();
    initialize(fresh);
    eq(CHANNEL, fresh.values.get("repository"));
    eq(true, fresh.values.get("automatic"));
    eq(true, fresh.values.get("default_channel_initialized"));
    initialize(fresh);
    eq(1, fresh.writes);

    Preferences legacy = new Preferences();
    legacy.values.put("repository", "");
    legacy.values.put("setup_shown", true);
    legacy.values.put("automatic", false);
    initialize(legacy);
    eq(CHANNEL, legacy.values.get("repository"));
    eq(false, legacy.values.get("automatic"));

    Preferences configured = new Preferences();
    configured.values.put("repository", "owner/another-custom-channel");
    configured.values.put("automatic", false);
    configured.values.put("checked_at", 123L);
    configured.values.put("pending", "existing pending download");
    initialize(configured);
    eq("owner/another-custom-channel", configured.values.get("repository"));
    eq(false, configured.values.get("automatic"));
    eq(123L, configured.values.get("checked_at"));
    eq("existing pending download", configured.values.get("pending"));

    // Clearing the channel after migration remains an explicit, persistent choice.
    fresh.values.put("repository", "");
    fresh.values.put("automatic", false);
    initialize(fresh);
    eq("", fresh.values.get("repository"));
    eq(false, fresh.values.get("automatic"));
    eq(1, fresh.writes);
    System.out.println("PASS: " + checks + " updater default/migration checks (no device UI test)");
  }
}
