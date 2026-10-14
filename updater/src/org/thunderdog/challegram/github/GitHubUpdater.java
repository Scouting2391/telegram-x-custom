/*
 * Telegram X personal GitHub updater. GPL-3.0-or-later.
 * This class uses only Android framework and Java APIs (minimum Android 7 / API 24).
 * It never accesses Google Play and contains no credentials or signing keys.
 */
package org.thunderdog.challegram.github;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HttpsURLConnection;

/** A public custom-release feed, separate from the official Telegram X release feed. */
public final class GitHubUpdater {
  private static final String TAG = "TGX-GitHubUpdater";
  private static final String PREFS = "personal_github_updater_v1";
  private static final String MANIFEST_NAME = "tgx-update.json";
  private static final long CHECK_INTERVAL = 6L * 60 * 60 * 1000;
  private static final long REMIND_INTERVAL = 24L * 60 * 60 * 1000;
  private static final long MAX_APK_BYTES = 512L * 1024 * 1024;
  private static final long MAX_MANIFEST_BYTES = 64 * 1024;
  private static final int MAX_API_BYTES = 1024 * 1024;
  private static final long TRANSFER_TIMEOUT = 10L * 60 * 1000;
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final AtomicReference<Task> RUNNING = new AtomicReference<>();
  private static WeakReference<AlertDialog> visibleDialog = new WeakReference<>(null);
  private static final ExecutorService IO = Executors.newSingleThreadExecutor(new ThreadFactory() {
    @Override public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, "TGX-GitHubUpdates");
      thread.setDaemon(true);
      return thread;
    }
  });

  private GitHubUpdater() { }

  /** Hook into the activity's existing automatic update check. Safe to call on every resume. */
  public static void check(final Activity activity) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      final WeakReference<Activity> ref = new WeakReference<>(activity);
      MAIN.post(new Runnable() {
        @Override public void run() { Activity live = live(ref); if (live != null) check(live); }
      });
      return;
    }
    if (!usable(activity) || RUNNING.get() != null) return;
    SharedPreferences prefs = prefs(activity);
    Update pending = readPending(activity);
    if (pending != null && prefs.getBoolean("awaiting_install_permission", false)) {
      prefs.edit().putBoolean("awaiting_install_permission", false).apply();
      if (canInstall(activity)) startReady(activity, pending, false);
      else toast(activity, "Install permission was not enabled. Open GitHub updates to try again.");
      return;
    }
    String repository = prefs.getString("repository", "");
    if (repository.length() == 0) {
      if (!prefs.getBoolean("setup_shown", false) && !dialogShowing()) showSettings(activity);
      return;
    }
    if (!prefs.getBoolean("automatic", true) || dialogShowing()) return;
    if (pending != null) {
      if (elapsed(prefs.getLong("ready_prompt_at", 0), REMIND_INTERVAL)) startReady(activity, pending, true);
      return;
    }
    if (elapsed(prefs.getLong("checked_at", 0), CHECK_INTERVAL)) startCheck(activity, false);
  }

  /** Hook into the manual Check for updates action. This also exposes configuration. */
  public static void showSettings(final Activity activity) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      final WeakReference<Activity> ref = new WeakReference<>(activity);
      MAIN.post(new Runnable() {
        @Override public void run() { Activity live = live(ref); if (live != null) showSettings(live); }
      });
      return;
    }
    if (!usable(activity)) return;
    if (RUNNING.get() != null) { toast(activity, "An update task is already running."); return; }
    if (dialogShowing()) return;
    final SharedPreferences prefs = prefs(activity);
    final LinearLayout layout = new LinearLayout(activity);
    layout.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (24 * activity.getResources().getDisplayMetrics().density);
    layout.setPadding(padding, padding / 2, padding, 0);
    TextView explanation = new TextView(activity);
    explanation.setText("Use the public GitHub repository that publishes your custom Telegram X updates. " +
      "The original Telegram X downloads cannot update this custom app.");
    layout.addView(explanation);
    final EditText repository = new EditText(activity);
    repository.setSingleLine(true);
    repository.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    repository.setHint("owner/repository");
    repository.setText(prefs.getString("repository", ""));
    layout.addView(repository);
    final CheckBox automatic = new CheckBox(activity);
    automatic.setText("Check automatically when the app is open");
    automatic.setChecked(prefs.getBoolean("automatic", true));
    layout.addView(automatic);
    TextView note = new TextView(activity);
    String installedBuild = "";
    try { installedBuild = "\nInstalled build: " + versionCode(installed(activity)); }
    catch (PackageManager.NameNotFoundException ignored) { }
    note.setText("Updates come only from this GitHub channel. Android will ask you to confirm installation." + installedBuild);
    layout.addView(note);
    final WeakReference<Activity> ref = new WeakReference<>(activity);
    final AlertDialog dialog = new AlertDialog.Builder(activity)
      .setTitle("GitHub updates").setView(layout)
      .setPositiveButton("Save", null).setNeutralButton("Check now", null)
      .setNegativeButton("Cancel", null).create();
    dialog.setOnShowListener(ignored -> {
      prefs.edit().putBoolean("setup_shown", true).apply();
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
        Activity live = live(ref);
        if (live == null) return;
        if (saveSettings(live, repository, automatic)) {
          dialog.dismiss();
          toast(live, repository.getText().toString().trim().length() == 0 ?
            "GitHub update channel cleared." : "GitHub update settings saved.");
        }
      });
      dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
        Activity live = live(ref);
        if (live == null || !saveSettings(live, repository, automatic)) return;
        if (prefs.getString("repository", "").length() == 0) {
          repository.setError("Enter the custom update repository first.");
          return;
        }
        dialog.dismiss();
        Update pending = readPending(live);
        if (pending != null) startReady(live, pending, true);
        else startCheck(live, true);
      });
    });
    show(dialog);
  }

  private static boolean saveSettings(Activity activity, EditText field, CheckBox automatic) {
    String text = field.getText().toString().trim();
    String repository;
    try { repository = normalizeRepository(text); }
    catch (UpdateException error) { field.setError(error.getMessage()); return false; }
    SharedPreferences prefs = prefs(activity);
    SharedPreferences.Editor editor = prefs.edit().putString("repository", repository)
      .putBoolean("automatic", automatic.isChecked());
    if (!repository.equals(prefs.getString("repository", ""))) {
      editor.remove("checked_at").remove("prompted_version").remove("prompted_at")
        .remove("pending").remove("ready_prompt_at").remove("awaiting_install_permission");
      deleteQuietly(new File(directory(activity), "update.apk"));
      deleteQuietly(new File(directory(activity), "update.apk.part"));
    }
    editor.apply();
    return true;
  }

  private static String normalizeRepository(String value) throws UpdateException {
    if (value.length() == 0) return "";
    if (value.startsWith("https://github.com/")) value = value.substring(19);
    if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    if (!value.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?/[A-Za-z0-9_.-]{1,100}"))
      throw new UpdateException("Enter owner/repository or its github.com link.");
    String repo = value.substring(value.indexOf('/') + 1);
    if (repo.equals(".") || repo.equals("..")) throw new UpdateException("Enter a valid repository name.");
    if (value.equalsIgnoreCase("TGX-Android/Telegram-X"))
      throw new UpdateException("Use your custom update repository. Official APKs have a different signature.");
    return value;
  }

  private static void startCheck(Activity activity, boolean manual) {
    final String repository = prefs(activity).getString("repository", "");
    if (repository.length() == 0) { if (manual) showSettings(activity); return; }
    final Task task = new Task(activity, repository, manual);
    if (!begin(task, manual ? "Checking GitHub for updates…" : null, false)) return;
    prefs(activity).edit().putLong("checked_at", System.currentTimeMillis()).apply();
    IO.execute(() -> {
      try {
        Update update = fetchUpdate(task);
        task.ensureRunning();
        if (update == null) {
          if (manual) task.onUi(live -> message(live, "Up to date", "This custom update channel has no newer compatible release."));
        } else {
          SharedPreferences prefs = prefs(task.app);
          boolean reminded = prefs.getLong("prompted_version", 0) == update.versionCode &&
            !elapsed(prefs.getLong("prompted_at", 0), REMIND_INTERVAL);
          if (manual || !reminded) task.onUi(live -> offerUpdate(live, update));
        }
      } catch (Exception error) { fail(task, error, "Could not check for updates"); }
      finally { finish(task); }
    });
  }

  private static Update fetchUpdate(Task task) throws Exception {
    URL url = new URL("https://api.github.com/repos/" + task.repository + "/releases/latest");
    byte[] api = read(task, url, MAX_API_BYTES, -1, null, true);
    JSONObject release = new JSONObject(new String(api, StandardCharsets.UTF_8));
    if (release.getBoolean("draft") || release.getBoolean("prerelease"))
      throw new UpdateException("This update channel has no stable published release.");
    JSONArray assets = release.getJSONArray("assets");
    if (assets.length() > 100) throw new UpdateException("This release contains too many files.");
    Asset manifest = null, apk = null;
    int apkCount = 0;
    for (int i = 0; i < assets.length(); i++) {
      JSONObject entry = assets.getJSONObject(i);
      String name = entry.getString("name");
      if (MANIFEST_NAME.equals(name)) {
        if (manifest != null) throw new UpdateException("This release contains duplicate update information.");
        manifest = asset(entry, task.repository, MAX_MANIFEST_BYTES);
      } else if (name.toLowerCase(Locale.US).endsWith(".apk")) {
        apkCount++;
        apk = asset(entry, task.repository, MAX_APK_BYTES);
      }
    }
    if (manifest == null) throw new UpdateException("The latest release is missing tgx-update.json. Its publisher must prepare a custom update release.");
    if (apkCount != 1 || apk == null) throw new UpdateException("The custom release must contain one universal APK.");
    byte[] data = read(task, manifest.url, MAX_MANIFEST_BYTES, manifest.size, manifest.sha256, false);
    JSONObject metadata = new JSONObject(new String(data, StandardCharsets.UTF_8));
    if (metadata.getInt("schemaVersion") != 1) throw new UpdateException("This release uses an unsupported update format.");
    String packageName = metadata.getString("packageName");
    if (!task.app.getPackageName().equals(packageName)) throw new UpdateException("This release is for a different app.");
    long version = positiveLong(metadata, "versionCode");
    if (!apk.name.equals(metadata.getString("apkFileName"))) throw new UpdateException("The release APK does not match its update information.");
    String versionName = displayVersion(metadata.getString("versionName"));
    if (metadata.has("minSdkVersion") && metadata.getInt("minSdkVersion") > Build.VERSION.SDK_INT)
      throw new UpdateException("The newest release requires a newer Android version.");
    if (version <= versionCode(installed(task.app))) return null;
    return new Update(task.repository, packageName, version, versionName, apk);
  }

  private static Asset asset(JSONObject data, String repository, long maximum) throws Exception {
    if (!"uploaded".equals(data.getString("state"))) throw new UpdateException("The release upload is not complete yet.");
    long size = positiveLong(data, "size");
    if (size > maximum) throw new UpdateException("The release file is larger than this updater allows.");
    String name = data.getString("name");
    if (name.length() > 180 || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0)
      throw new UpdateException("The release contains an invalid filename.");
    String digest = data.optString("digest", "");
    if (!digest.matches("sha256:[A-Fa-f0-9]{64}"))
      throw new UpdateException("GitHub has not supplied a SHA-256 checksum for this release. Ask its publisher to upload the files again.");
    URL url = new URL(data.getString("browser_download_url"));
    validateUrl(url, repository, false, true);
    return new Asset(name, size, digest.substring(7).toLowerCase(Locale.US), url);
  }

  private static void offerUpdate(final Activity activity, final Update update) {
    if (dialogShowing()) return;
    prefs(activity).edit().putLong("prompted_version", update.versionCode)
      .putLong("prompted_at", System.currentTimeMillis()).apply();
    final WeakReference<Activity> ref = new WeakReference<>(activity);
    show(new AlertDialog.Builder(activity).setTitle("Telegram X update available")
      .setMessage("Version " + update.versionName + " is available from your GitHub update channel.\n\nDownload size: " +
        String.format(Locale.US, "%.1f MB", update.apk.size / 1048576.0) + ".")
      .setPositiveButton("Download and install", (dialog, which) -> {
        Activity live = live(ref); if (live != null) startDownload(live, update);
      }).setNegativeButton("Later", null).create());
  }

  private static void startDownload(Activity activity, final Update update) {
    final Task task = new Task(activity, update.repository, true);
    if (!begin(task, "Downloading update…", true)) return;
    IO.execute(() -> {
      File part = new File(directory(task.app), "update.apk.part");
      try {
        File folder = directory(task.app);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new UpdateException("Could not create the private update folder.");
        long free = folder.getUsableSpace();
        if (free > 0 && free < update.apk.size + 16L * 1024 * 1024)
          throw new UpdateException("There is not enough free space to download this update.");
        deleteQuietly(part);
        download(task, update.apk, part);
        verifyPackage(task.app, part, update);
        task.ensureRunning();
        File target = new File(folder, "update.apk");
        if (!part.renameTo(target)) throw new UpdateException("Could not save the downloaded update.");
        boolean saved = prefs(task.app).edit().putString("pending", update.toJson().toString())
          .putBoolean("awaiting_install_permission", false).putLong("ready_prompt_at", 0).commit();
        if (!saved) { deleteQuietly(target); throw new UpdateException("Could not save the update information. Please retry."); }
        task.onUi(live -> openInstaller(live, update));
      } catch (Exception error) { fail(task, error, "Could not download the update"); }
      finally { deleteQuietly(part); finish(task); }
    });
  }

  private static void startReady(Activity activity, final Update update, final boolean prompt) {
    final Task task = new Task(activity, update.repository, true);
    if (!begin(task, "Verifying downloaded update…", false)) return;
    IO.execute(() -> {
      try {
        File file = new File(directory(task.app), "update.apk");
        verifyFile(task, file, update.apk);
        verifyPackage(task.app, file, update);
        task.onUi(live -> {
          if (prompt) offerReady(live, update);
          else openInstaller(live, update);
        });
      } catch (Exception error) {
        if (!task.cancelled) clearPending(task.app);
        fail(task, error, "Could not verify the downloaded update");
      } finally { finish(task); }
    });
  }

  private static void offerReady(final Activity activity, final Update update) {
    if (dialogShowing()) return;
    prefs(activity).edit().putLong("ready_prompt_at", System.currentTimeMillis()).apply();
    final WeakReference<Activity> ref = new WeakReference<>(activity);
    show(new AlertDialog.Builder(activity).setTitle("Update ready")
      .setMessage("Version " + update.versionName + " has been downloaded and verified.")
      .setPositiveButton("Install", (dialog, which) -> {
        Activity live = live(ref); if (live != null) startReady(live, update, false);
      }).setNegativeButton("Later", null).create());
  }

  private static void openInstaller(final Activity activity, final Update update) {
    if (!usable(activity)) return;
    if (!canInstall(activity)) {
      final WeakReference<Activity> ref = new WeakReference<>(activity);
      show(new AlertDialog.Builder(activity).setTitle("Allow app updates")
        .setMessage("Android needs permission for Telegram X to install this update. Enable “Allow from this source”, then return here.")
        .setPositiveButton("Open Settings", (dialog, which) -> {
          Activity live = live(ref); if (live == null) return;
          try {
            prefs(live).edit().putBoolean("awaiting_install_permission", true).apply();
            live.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
              Uri.parse("package:" + live.getPackageName())));
          } catch (ActivityNotFoundException | SecurityException error) {
            prefs(live).edit().putBoolean("awaiting_install_permission", false).apply();
            message(live, "Installation permission", "Open Android Settings → Apps → Telegram X → Install unknown apps. Allow this source, then choose Check now in GitHub updates.");
          }
        }).setNegativeButton("Later", null).create());
      return;
    }
    try {
      Uri uri = new Uri.Builder().scheme("content").authority(activity.getPackageName() + ".provider")
        .appendPath("media").appendPath("github-updates").appendPath("update.apk").build();
      Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      intent.setClipData(ClipData.newRawUri("Telegram X update", uri));
      prefs(activity).edit().putLong("ready_prompt_at", System.currentTimeMillis()).apply();
      activity.startActivity(intent);
    } catch (ActivityNotFoundException | SecurityException error) {
      message(activity, "Could not open the installer", "Android could not open this APK. The update remains downloaded; check install permissions and try Check now again.");
    }
  }

  private static boolean canInstall(Context context) {
    return Build.VERSION.SDK_INT < 26 || context.getPackageManager().canRequestPackageInstalls();
  }

  private static Update readPending(Context context) {
    SharedPreferences prefs = prefs(context);
    String json = prefs.getString("pending", "");
    if (json.length() == 0) return null;
    try {
      JSONObject data = new JSONObject(json);
      String repository = normalizeRepository(data.getString("repository"));
      if (repository.length() == 0 || !repository.equals(prefs.getString("repository", ""))) throw new JSONException("Changed repository");
      String packageName = data.getString("packageName");
      if (!context.getPackageName().equals(packageName)) throw new JSONException("Wrong package");
      long code = positiveLong(data, "versionCode");
      if (code <= versionCode(installed(context))) { clearPending(context); return null; }
      long size = positiveLong(data, "size");
      String sha256 = data.getString("sha256");
      if (size > MAX_APK_BYTES || !sha256.matches("[a-f0-9]{64}")) throw new JSONException("Invalid pending digest");
      if (!new File(directory(context), "update.apk").isFile()) throw new IOException("Missing pending file");
      return new Update(repository, packageName, code, displayVersion(data.getString("versionName")),
        new Asset(data.getString("apkFileName"), size, sha256, null));
    } catch (Exception error) {
      clearPending(context);
      return null;
    }
  }

  private static void clearPending(Context context) {
    prefs(context).edit().remove("pending").remove("awaiting_install_permission").remove("ready_prompt_at").apply();
    deleteQuietly(new File(directory(context), "update.apk"));
  }

  private static void verifyPackage(Context context, File file, Update update) throws Exception {
    PackageInfo installed = installed(context);
    int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    PackageInfo candidate = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), flags);
    if (candidate == null || !context.getPackageName().equals(candidate.packageName) || !update.packageName.equals(candidate.packageName))
      throw new UpdateException("The downloaded APK is not a valid update for this app.");
    if (versionCode(candidate) != update.versionCode || !update.versionName.equals(candidate.versionName))
      throw new UpdateException("The APK version does not match the published update information.");
    if (versionCode(candidate) <= versionCode(installed)) throw new UpdateException("This APK is not newer than your installed app.");
    if (candidate.applicationInfo != null && candidate.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)
      throw new UpdateException("This update requires a newer Android version.");
    String[] trusted = signers(installed), downloaded = signers(candidate);
    if (trusted.length == 0 || downloaded.length == 0 || !Arrays.equals(trusted, downloaded))
      throw new UpdateException("The APK has a different signing certificate. It cannot safely update this custom installation.");
  }

  private static PackageInfo installed(Context context) throws PackageManager.NameNotFoundException {
    return context.getPackageManager().getPackageInfo(context.getPackageName(),
      Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
  }

  private static long versionCode(PackageInfo info) {
    return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : (info.versionCode & 0xffffffffL);
  }

  private static String[] signers(PackageInfo info) throws Exception {
    Signature[] signatures = Build.VERSION.SDK_INT >= 28 ?
      (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
    if (signatures == null) return new String[0];
    String[] hashes = new String[signatures.length];
    for (int i = 0; i < signatures.length; i++) hashes[i] = hex(MessageDigest.getInstance("SHA-256").digest(signatures[i].toByteArray()));
    Arrays.sort(hashes);
    return hashes;
  }

  private static byte[] read(Task task, URL url, long maximum, long expectedSize, String sha256, boolean api) throws Exception {
    HttpsURLConnection connection = connect(task, url, api);
    try {
      checkLength(connection, maximum, expectedSize);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      long count = 0;
      try (InputStream input = connection.getInputStream()) {
        byte[] buffer = new byte[16384];
        int amount;
        while ((amount = input.read(buffer)) != -1) {
          task.ensureRunning();
          count += amount;
          if (count > maximum || (expectedSize >= 0 && count > expectedSize)) throw new UpdateException("The downloaded file has an unexpected size.");
          output.write(buffer, 0, amount);
          digest.update(buffer, 0, amount);
        }
      }
      verifyTransfer(count, expectedSize, digest.digest(), sha256);
      return output.toByteArray();
    } finally { connection.disconnect(); task.connection = null; }
  }

  private static void download(Task task, Asset asset, File file) throws Exception {
    HttpsURLConnection connection = connect(task, asset.url, false);
    try {
      checkLength(connection, MAX_APK_BYTES, asset.size);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      long count = 0;
      int lastProgress = -1;
      try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(file)) {
        byte[] buffer = new byte[65536];
        int amount;
        while ((amount = input.read(buffer)) != -1) {
          task.ensureRunning();
          count += amount;
          if (count > asset.size || count > MAX_APK_BYTES) throw new UpdateException("The APK has an unexpected size.");
          output.write(buffer, 0, amount);
          digest.update(buffer, 0, amount);
          int progress = (int) (count * 100 / asset.size);
          if (progress != lastProgress) { task.progress(progress); lastProgress = progress; }
        }
        output.getFD().sync();
      }
      verifyTransfer(count, asset.size, digest.digest(), asset.sha256);
    } finally { connection.disconnect(); task.connection = null; }
  }

  private static void verifyFile(Task task, File file, Asset asset) throws Exception {
    if (!file.isFile() || file.length() != asset.size) throw new UpdateException("The downloaded APK is missing or incomplete. Check again to download it.");
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = new FileInputStream(file)) {
      byte[] buffer = new byte[65536];
      int amount;
      while ((amount = input.read(buffer)) != -1) { task.ensureRunning(); digest.update(buffer, 0, amount); }
    }
    verifyTransfer(file.length(), asset.size, digest.digest(), asset.sha256);
  }

  private static void verifyTransfer(long actualSize, long expectedSize, byte[] digest, String expectedHash) throws UpdateException {
    if (expectedSize >= 0 && actualSize != expectedSize) throw new UpdateException("The download was incomplete. Please retry.");
    if (expectedHash != null && !MessageDigest.isEqual(hex(digest).getBytes(StandardCharsets.US_ASCII), expectedHash.getBytes(StandardCharsets.US_ASCII)))
      throw new UpdateException("The download checksum does not match GitHub. The file will not be installed.");
  }

  private static HttpsURLConnection connect(Task task, URL initial, boolean api) throws Exception {
    URL current = initial;
    for (int hop = 0; hop <= 5; hop++) {
      task.ensureRunning();
      validateUrl(current, task.repository, api, hop == 0);
      HttpsURLConnection connection = (HttpsURLConnection) current.openConnection();
      task.connection = connection;
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(30000);
      connection.setRequestProperty("User-Agent", "Telegram-X-Personal-GitHub-Updater/1");
      connection.setRequestProperty("Accept", api ? "application/vnd.github+json" : "application/octet-stream");
      connection.setRequestProperty("Accept-Encoding", "identity");
      if (api) connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
      int status;
      try { status = connection.getResponseCode(); }
      catch (Exception error) { connection.disconnect(); task.connection = null; throw error; }
      if (status == 200) return connection;
      if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
        String location = connection.getHeaderField("Location");
        connection.disconnect();
        task.connection = null;
        if (location == null || hop == 5) throw new UpdateException("GitHub returned an invalid download redirect.");
        current = new URL(current, location);
        continue;
      }
      connection.disconnect();
      task.connection = null;
      if (status == 403 || status == 429) throw new UpdateException("GitHub is limiting update requests or denying access. Use a public repository and try again later.");
      if (status == 404) throw new UpdateException("The public repository or its latest custom release could not be found.");
      throw new UpdateException("GitHub could not provide this update (HTTP " + status + "). Please retry later.");
    }
    throw new UpdateException("GitHub returned too many download redirects.");
  }

  private static void validateUrl(URL url, String repository, boolean api, boolean initial) throws UpdateException {
    if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null || url.getRef() != null ||
        (url.getPort() != -1 && url.getPort() != 443)) throw new UpdateException("The update URL is not an allowed HTTPS address.");
    String host = url.getHost().toLowerCase(Locale.US);
    String path = url.getPath().toLowerCase(Locale.US);
    String repo = repository.toLowerCase(Locale.US);
    if (api) {
      if (!"api.github.com".equals(host) || !path.equals("/repos/" + repo + "/releases/latest"))
        throw new UpdateException("The update check redirected outside the selected GitHub repository.");
    } else if ("github.com".equals(host)) {
      if (!path.startsWith("/" + repo + "/releases/download/"))
        throw new UpdateException("The update download points outside the selected GitHub repository.");
    } else if (initial || !("release-assets.githubusercontent.com".equals(host) ||
        "objects.githubusercontent.com".equals(host) || "github-releases.githubusercontent.com".equals(host))) {
      throw new UpdateException("The update download redirected outside GitHub's release servers.");
    }
  }

  private static void checkLength(HttpsURLConnection connection, long maximum, long expected) throws UpdateException {
    long size = connection.getContentLengthLong();
    if (size > maximum || (size >= 0 && expected >= 0 && size != expected))
      throw new UpdateException("The server reported an unexpected file size.");
    String encoding = connection.getContentEncoding();
    if (encoding != null && !"identity".equalsIgnoreCase(encoding))
      throw new UpdateException("The server returned an unsupported download encoding.");
  }

  private static long positiveLong(JSONObject data, String field) throws JSONException, UpdateException {
    Object raw = data.get(field);
    if (!(raw instanceof Number) || !raw.toString().matches("[1-9][0-9]{0,18}"))
      throw new UpdateException("The release contains invalid update information.");
    try { long value = Long.parseLong(raw.toString()); if (value > 0) return value; }
    catch (NumberFormatException ignored) { }
    throw new UpdateException("The release contains invalid update information.");
  }

  private static String displayVersion(String version) throws UpdateException {
    if (version.length() == 0 || version.length() > 100 || !version.matches("[A-Za-z0-9._+() -]+"))
      throw new UpdateException("The release contains an invalid version name.");
    return version;
  }

  private static boolean begin(final Task task, String text, boolean determinate) {
    if (!RUNNING.compareAndSet(null, task)) {
      if (task.manual) task.onUi(live -> toast(live, "An update task is already running."));
      return false;
    }
    Activity activity = live(task.activity);
    if (text != null && activity != null) {
      final ProgressDialog progress = new ProgressDialog(activity);
      progress.setTitle("GitHub updates");
      progress.setMessage(text);
      progress.setProgressStyle(determinate ? ProgressDialog.STYLE_HORIZONTAL : ProgressDialog.STYLE_SPINNER);
      progress.setMax(100);
      progress.setIndeterminate(!determinate);
      progress.setCancelable(true);
      progress.setCanceledOnTouchOutside(false);
      progress.setOnCancelListener(dialog -> task.cancel());
      task.dialog = new WeakReference<>(progress);
      show(progress);
    }
    return true;
  }

  private static void finish(Task task) {
    task.active = false;
    RUNNING.compareAndSet(task, null);
    MAIN.post(() -> { ProgressDialog dialog = task.dialog.get(); if (dialog != null && dialog.isShowing()) dialog.dismiss(); });
  }

  private static void fail(Task task, Exception error, String title) {
    if (task.cancelled) return;
    final String detail = error instanceof UpdateException ? error.getMessage() :
      error instanceof JSONException ? "The custom release has invalid update information. Ask its publisher to check tgx-update.json." :
      "Could not finish the request. Check your Internet connection and try again.";
    Log.w(TAG, title + ": " + detail);
    if (task.manual) task.onUi(live -> message(live, title, detail));
  }

  private interface UiAction { void run(Activity activity); }

  private static final class Task {
    final Context app;
    final WeakReference<Activity> activity;
    final String repository;
    final boolean manual;
    final long started = SystemClock.elapsedRealtime();
    volatile boolean cancelled;
    volatile boolean active = true;
    volatile HttpsURLConnection connection;
    WeakReference<ProgressDialog> dialog = new WeakReference<>(null);
    Task(Activity activity, String repository, boolean manual) {
      this.app = activity.getApplicationContext();
      this.activity = new WeakReference<>(activity);
      this.repository = repository;
      this.manual = manual;
    }
    void ensureRunning() throws UpdateException {
      if (cancelled) throw new UpdateException("Update task cancelled.");
      if (SystemClock.elapsedRealtime() - started > TRANSFER_TIMEOUT) throw new UpdateException("The update request timed out. Please retry.");
      if (!repository.equals(prefs(app).getString("repository", ""))) throw new UpdateException("The update repository changed. Please check again.");
    }
    void cancel() {
      cancelled = true;
      final HttpsURLConnection current = connection;
      if (current != null) {
        Thread closer = new Thread(() -> current.disconnect(), "TGX-CancelUpdate");
        closer.setDaemon(true);
        closer.start();
      }
    }
    void onUi(final UiAction action) {
      MAIN.post(() -> {
        ProgressDialog progress = dialog.get();
        if (progress != null && progress.isShowing()) progress.dismiss();
        Activity live = live(activity);
        if (!cancelled && live != null && repository.equals(prefs(app).getString("repository", ""))) action.run(live);
      });
    }
    void progress(final int value) {
      MAIN.post(() -> { ProgressDialog progress = dialog.get(); if (!cancelled && active && progress != null && progress.isShowing()) progress.setProgress(value); });
    }
  }

  private static final class Asset {
    final String name, sha256;
    final long size;
    final URL url;
    Asset(String name, long size, String sha256, URL url) { this.name = name; this.size = size; this.sha256 = sha256; this.url = url; }
  }

  private static final class Update {
    final String repository, packageName, versionName;
    final long versionCode;
    final Asset apk;
    Update(String repository, String packageName, long versionCode, String versionName, Asset apk) {
      this.repository = repository; this.packageName = packageName; this.versionCode = versionCode; this.versionName = versionName; this.apk = apk;
    }
    JSONObject toJson() throws JSONException {
      return new JSONObject().put("repository", repository).put("packageName", packageName)
        .put("versionCode", versionCode).put("versionName", versionName).put("apkFileName", apk.name)
        .put("size", apk.size).put("sha256", apk.sha256);
    }
  }

  private static final class UpdateException extends Exception {
    UpdateException(String message) { super(message); }
  }

  private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
  private static File directory(Context context) { return new File(context.getFilesDir(), "media/github-updates"); }
  private static boolean usable(Activity activity) { return activity != null && !activity.isFinishing() && !activity.isDestroyed(); }
  private static Activity live(WeakReference<Activity> ref) { Activity activity = ref.get(); return usable(activity) ? activity : null; }
  private static boolean elapsed(long previous, long interval) { long now = System.currentTimeMillis(); return previous <= 0 || now < previous || now - previous >= interval; }
  private static boolean dialogShowing() { AlertDialog dialog = visibleDialog.get(); return dialog != null && dialog.isShowing(); }
  private static void show(AlertDialog dialog) { visibleDialog = new WeakReference<>(dialog); dialog.show(); }
  private static void message(Activity activity, String title, String text) { show(new AlertDialog.Builder(activity).setTitle(title).setMessage(text).setPositiveButton("OK", null).create()); }
  private static void toast(Context context, String text) { Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_LONG).show(); }
  private static void deleteQuietly(File file) { if (file.isFile() && !file.delete()) Log.w(TAG, "Could not remove a previous update file."); }
  private static String hex(byte[] bytes) { StringBuilder result = new StringBuilder(bytes.length * 2); for (byte value : bytes) result.append(String.format(Locale.US, "%02x", value & 0xff)); return result.toString(); }
}
