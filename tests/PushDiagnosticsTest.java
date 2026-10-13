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
