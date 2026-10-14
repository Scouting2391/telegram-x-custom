import java.io.IOException;
import java.util.concurrent.ExecutionException;
import org.thunderdog.challegram.github.PushDiagnostics;

/** Dependency-free regression checks; run using run_updater_tests.py. */
public final class PushDiagnosticsTest {
  private static int checks;

  public static void main(String[] args) {
    nestedRegistrationFailure();
    missingMessagesAndCauses();
    errorCodeExtraction();
    cyclicCauses();
    depthLimit();
    firebaseResponsePrivacy();
    firebaseTransportCategories();
    diagnosticExpiry();
    System.out.println("PushDiagnostics: " + checks + " checks passed.");
  }

  private static void nestedRegistrationFailure() {
    IOException root = new IOException("SERVICE_NOT_AVAILABLE");
    ExecutionException async = new ExecutionException(root);
    IOException registration = new IOException("FCM Registration failed!", async);
    equal("nested FCM error", "SERVICE_NOT_AVAILABLE", PushDiagnostics.describe(registration));
    same("outer cause preserved", async, registration.getCause());
    same("inner cause preserved", root, async.getCause());
    equal("outer message preserved", "FCM Registration failed!", registration.getMessage());
  }

  private static void missingMessagesAndCauses() {
    equal("null input", "Unknown Firebase registration error", PushDiagnostics.describe(null));
    equal("null message and no cause", "IOException", PushDiagnostics.describe(new IOException()));
    IOException root = new IOException((String) null);
    equal("message-less nested cause", "IOException",
        PushDiagnostics.describe(new Exception(null, root)));
    equal("retain useful parent message", "Registration service unavailable",
        PushDiagnostics.describe(new Exception("Registration service unavailable", root)));
    equal("ignore blank messages", "IOException",
        PushDiagnostics.describe(new Exception(" \t\n", root)));
    Throwable anonymous = new Throwable((String) null) { };
    equal("anonymous type fallback", anonymous.getClass().getName(), PushDiagnostics.describe(anonymous));
  }

  private static void errorCodeExtraction() {
    equal("trailing uppercase code", "AUTHENTICATION_FAILED",
        PushDiagnostics.describe(new IOException("Registration rejected: AUTHENTICATION_FAILED")));
    equal("standalone code", "TOO_MANY_REGISTRATIONS",
        PushDiagnostics.describe(new IOException("TOO_MANY_REGISTRATIONS")));
    equal("human-readable cause", "Unable to resolve Firebase host",
        PushDiagnostics.describe(new IOException("FCM Registration failed!",
            new IOException("Unable to resolve Firebase host"))));
    equal("do not truncate mixed-case detail", "HTTP error: Permission denied",
        PushDiagnostics.describe(new IOException("HTTP error: Permission denied")));
  }

  private static void cyclicCauses() {
    LinkedFailure first = new LinkedFailure("first");
    LinkedFailure second = new LinkedFailure("second");
    first.next = second;
    second.next = first;
    equal("two-node cycle terminates", "second", PushDiagnostics.describe(first));
    same("cycle first link preserved", second, first.next);
    same("cycle second link preserved", first, second.next);
    LinkedFailure self = new LinkedFailure("self");
    self.next = self;
    equal("self-cycle terminates", "self", PushDiagnostics.describe(self));

    EqualFailure equalFirst = new EqualFailure("equal-first");
    EqualFailure equalSecond = new EqualFailure("equal-second");
    equalFirst.next = equalSecond;
    equal("identity-based cycle detection", "equal-second", PushDiagnostics.describe(equalFirst));
  }

  private static void depthLimit() {
    LinkedFailure[] chain = new LinkedFailure[20];
    for (int i = 0; i < chain.length; i++) {
      chain[i] = new LinkedFailure("depth-" + i);
      if (i > 0) {
        chain[i - 1].next = chain[i];
      }
    }
    equal("stop after sixteen cause links", "depth-16", PushDiagnostics.describe(chain[0]));
    equal("never inspect beyond bound", 0, chain[17].causeReads);
    equal("bounded cause reads", 0, chain[16].causeReads);
    same("deep link preserved", chain[17], chain[16].next);
  }

  private static final String UNAVAILABLE = "Firebase Installations Service is unavailable. Please try again later.";
  private static String unavailable() { return PushDiagnostics.describe(new IOException(UNAVAILABLE)); }

  private static void firebaseResponsePrivacy() {
    resetDiagnostics();
    PushDiagnostics.recordFirebaseResponse("Error when communicating with the Firebase Installations server API. HTTP response: [403 Forbidden: " +
      "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"key PRIVATE_API_KEY for fid PRIVATE_FID\",\"details\":[" +
      "{\"reason\":\"API_KEY_ANDROID_APP_BLOCKED\",\"metadata\":{\"apiKey\":\"PRIVATE_API_KEY\",\"fingerprint\":\"PRIVATE_FINGERPRINT\"}}]}}]");
    equal("surface safe restriction reason", UNAVAILABLE + " [Last Firebase failure: HTTP 403; PERMISSION_DENIED; API_KEY_ANDROID_APP_BLOCKED]", unavailable());
    equal("unrelated failures unchanged", "Other failure", PushDiagnostics.describe(new IOException("Other failure")));
    equal("bad config also gets context", "BAD CONFIG [Last Firebase failure: HTTP 403; PERMISSION_DENIED; API_KEY_ANDROID_APP_BLOCKED]",
      PushDiagnostics.describe(new IOException("BAD CONFIG")));
    IOException wrapper = new IOException("FCM Registration failed!", new IOException(UNAVAILABLE));
    String outer = wrapper.getMessage();
    Throwable cause = wrapper.getCause();
    equal("wrapped unavailable gets context", unavailable(), PushDiagnostics.describe(wrapper));
    same("original cause still unchanged", cause, wrapper.getCause());
    equal("original message still unchanged", outer, wrapper.getMessage());
    resetDiagnostics();
    PushDiagnostics.recordFirebaseResponse("HTTP response: [403 Forbidden: {\"reason\":\"SECRET_IDENTIFIER\",\"status\":\"PRIVATE_TOKEN\"}]");
    equal("unknown server values redacted", UNAVAILABLE + " [Last Firebase failure: HTTP 403]", unavailable());
    resetDiagnostics();
    PushDiagnostics.recordFirebaseResponse("HTTP response: [503 Unavailable: {\"status\":\"UNAVAILABLE\",\"reason\":\"UNAVAILABLE\"}]");
    equal("duplicate reason deduplicated", UNAVAILABLE + " [Last Firebase failure: HTTP 503; UNAVAILABLE]", unavailable());
    resetDiagnostics();
    PushDiagnostics.recordFirebaseResponse(null);
    PushDiagnostics.recordFirebaseResponse("HTTP response: [200 OK: {\"reason\":\"API_KEY_INVALID\"}]");
    PushDiagnostics.recordFirebaseResponse("HTTP response: [999 Unknown: private]");
    PushDiagnostics.recordFirebaseResponse("malformed without HTTP status");
    equal("invalid or successful response ignored", UNAVAILABLE, unavailable());
    StringBuilder large = new StringBuilder("HTTP response: [403 Forbidden: ");
    while (large.length() < 66000) large.append('x');
    large.append("{\"reason\":\"API_KEY_ANDROID_APP_BLOCKED\"}]");
    PushDiagnostics.recordFirebaseResponse(large.toString());
    equal("bounded body parsing", UNAVAILABLE + " [Last Firebase failure: HTTP 403]", unavailable());
    resetDiagnostics();
  }

  private static void firebaseTransportCategories() {
    PushDiagnostics.recordFirebaseTransportFailure(new java.net.UnknownHostException("PRIVATE_HOST PRIVATE_TOKEN"));
    equal("DNS category only", UNAVAILABLE + " [Last Firebase failure: DNS lookup failed (UnknownHostException)]", unavailable());
    PushDiagnostics.recordFirebaseTransportFailure(new IOException("private", new java.net.SocketTimeoutException("private")));
    equal("nested timeout category", UNAVAILABLE + " [Last Firebase failure: connection timed out (SocketTimeoutException)]", unavailable());
    PushDiagnostics.recordFirebaseTransportFailure(new java.net.ConnectException("private"));
    equal("connect category", UNAVAILABLE + " [Last Firebase failure: connection failed (ConnectException)]", unavailable());
    PushDiagnostics.recordFirebaseTransportFailure(new javax.net.ssl.SSLHandshakeException("PRIVATE_CERTIFICATE"));
    equal("TLS handshake category", UNAVAILABLE + " [Last Firebase failure: TLS handshake failed (SSLHandshakeException)]", unavailable());
    PushDiagnostics.recordFirebaseTransportFailure(new javax.net.ssl.SSLException("private"));
    equal("TLS category", UNAVAILABLE + " [Last Firebase failure: TLS connection failed (SSLException)]", unavailable());
    PushDiagnostics.recordFirebaseTransportFailure(new IOException("https://private.test/FID?token=PRIVATE"));
    equal("generic IO message redacted", UNAVAILABLE + " [Last Firebase failure: network error (IOException)]", unavailable());
    LinkedFailure cycle = new LinkedFailure("private"); cycle.next = cycle;
    PushDiagnostics.recordFirebaseTransportFailure(cycle);
    equal("transport cycles terminate", UNAVAILABLE + " [Last Firebase failure: network error (IOException)]", unavailable());
    PushDiagnostics.recordFirebaseTransportFailure(null);
    equal("null transport ignored", UNAVAILABLE + " [Last Firebase failure: network error (IOException)]", unavailable());
    PushDiagnostics.recordFirebaseResponse("HTTP response: [429 Too many requests: {\"status\":\"RESOURCE_EXHAUSTED\"}]");
    equal("latest response replaces transport", UNAVAILABLE + " [Last Firebase failure: HTTP 429; RESOURCE_EXHAUSTED]", unavailable());
    resetDiagnostics();
  }

  @SuppressWarnings("unchecked")
  private static java.util.concurrent.atomic.AtomicReference<Object> diagnosticSlot() {
    try {
      java.lang.reflect.Field field = PushDiagnostics.class.getDeclaredField("LAST_FIREBASE_FAILURE");
      field.setAccessible(true);
      return (java.util.concurrent.atomic.AtomicReference<Object>) field.get(null);
    } catch (Exception error) { throw new AssertionError(error); }
  }
  private static void resetDiagnostics() { diagnosticSlot().set(null); }
  private static void diagnosticExpiry() {
    try {
      Class<?> record = Class.forName(PushDiagnostics.class.getName() + "$Failure");
      java.lang.reflect.Constructor<?> ctor = record.getDeclaredConstructor(String.class, long.class);
      ctor.setAccessible(true);
      diagnosticSlot().set(ctor.newInstance("HTTP 403", System.nanoTime() - 16L * 60L * 1000000000L));
      equal("stale diagnostic discarded", UNAVAILABLE, unavailable());
      equal("expired entry removed", true, diagnosticSlot().get() == null);
      diagnosticSlot().set(ctor.newInstance("HTTP 403", System.nanoTime() + 60000000000L));
      equal("future clock value discarded", UNAVAILABLE, unavailable());
      resetDiagnostics();
    } catch (Exception error) { throw new AssertionError(error); }
  }

  private static void equal(String label, Object expected, Object actual) {
    checks++;
    if (!expected.equals(actual)) {
      throw new AssertionError(label + ": expected <" + expected + "> but got <" + actual + ">");
    }
  }

  private static void same(String label, Object expected, Object actual) {
    checks++;
    if (expected != actual) {
      throw new AssertionError(label + ": identity changed");
    }
  }

  private static class LinkedFailure extends Throwable {
    Throwable next;
    int causeReads;

    LinkedFailure(String message) {
      super(message);
    }

    @Override
    public synchronized Throwable getCause() {
      causeReads++;
      return next;
    }
  }

  private static final class EqualFailure extends LinkedFailure {
    EqualFailure(String message) {
      super(message);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof EqualFailure;
    }

    @Override
    public int hashCode() {
      return 1;
    }
  }
}
