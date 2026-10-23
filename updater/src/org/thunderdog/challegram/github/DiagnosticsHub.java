package org.thunderdog.challegram.github;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.InputType;
import android.util.AtomicFile;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.HttpsURLConnection;

/** Local history plus opt-in private GitHub uploads. Never changes push behavior. */
public final class DiagnosticsHub {
  private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r,"TGX-PrivateDiagnostics"); t.setDaemon(true); return t;
  });
  private static final AtomicBoolean RETRY_QUEUED = new AtomicBoolean();
  private static final AtomicBoolean QUEUED = new AtomicBoolean();
  private static final AtomicLong EPOCH = new AtomicLong();
  private static volatile Context app;
  private static volatile SharedPreferences prefs;
  private static volatile String state = "Local capture only. No reports are uploaded until you pair a private inbox.";
  private DiagnosticsHub() { }

  public static synchronized void initialize(Context context) {
    if (app != null) return;
    try {
      Context application = context.getApplicationContext();
      SharedPreferences settings = application.getSharedPreferences("personal_diagnostic_upload_v1",Context.MODE_PRIVATE);
      long build = application.getPackageManager().getPackageInfo(application.getPackageName(),0).versionCode;
      AtomicFile file = historyFile(application);
      String saved = null;
      if (file.getBaseFile().exists() && file.getBaseFile().length() <= 40000)
        saved = new String(file.readFully(),StandardCharsets.UTF_8);
      DiagnosticHistory.GLOBAL.initialize(build,saved,System.currentTimeMillis(),() -> kick(1));
      prefs = settings; app = application;
      if (settings.getBoolean("enabled",false)) state = "Private uploads enabled. Waiting for the next check.";
      kick(1);
    } catch (Exception ignored) {
      state = "Diagnostic storage is unavailable. Push registration is unchanged.";
    }
  }
  private static AtomicFile historyFile(Context context) {
    return new AtomicFile(new File(context.getNoBackupFilesDir(),"push-diagnostic-history.txt"));
  }
  private static void persistHistory() throws Exception {
    AtomicFile file = historyFile(app); FileOutputStream out = null;
    try {
      out=file.startWrite(); out.write(DiagnosticHistory.GLOBAL.serialize(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8)); file.finishWrite(out);
    } catch (Exception e) { file.failWrite(out); throw e; }
  }
  private static void kick(long seconds) {
    if (app == null || !QUEUED.compareAndSet(false,true)) return;
    IO.schedule(() -> {
      QUEUED.set(false);
      try { persistHistory(); upload(false,EPOCH.get()); }
      catch (Exception ignored) { state="Diagnostic storage is unavailable. History remains in memory."; }
    },seconds,TimeUnit.SECONDS);
  }
  private static void retryLater() {
    if (!RETRY_QUEUED.compareAndSet(false,true)) return;
    IO.schedule(() -> { RETRY_QUEUED.set(false); kick(1); },3600,TimeUnit.SECONDS);
  }
  private static void active(long epoch) throws Exception {
    if (epoch != EPOCH.get()) throw new Cancelled();
  }
  private static JSONObject request(String repo,String suffix,String method,String token,JSONObject body,long epoch) throws Exception {
    active(epoch);
    URL url = DiagnosticPolicy.endpoint(repo,suffix);
    HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
    connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
    connection.setConnectTimeout(15000); connection.setReadTimeout(15000); connection.setRequestMethod(method);
    connection.setRequestProperty("Accept","application/vnd.github+json");
    connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
    connection.setRequestProperty("User-Agent","Telegram-X-Private-Diagnostics/1");
    connection.setRequestProperty("Authorization","Bearer " + token);
    try {
      if (body != null) {
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 40000) throw new IllegalArgumentException();
        connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
        connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
        active(epoch);
        try (java.io.OutputStream output=connection.getOutputStream()) { output.write(bytes); }
      }
      int status=connection.getResponseCode(); active(epoch);
      if (status != 200 && status != 201) throw new ApiFailure(status);
      ByteArrayOutputStream output=new ByteArrayOutputStream();
      try (InputStream input=connection.getInputStream()) {
        byte[] block=new byte[8192]; int n;
        while ((n=input.read(block)) != -1) {
          active(epoch); if (output.size()+n > 1024*1024) throw new IllegalArgumentException(); output.write(block,0,n);
        }
      }
      String text=new String(output.toByteArray(),StandardCharsets.UTF_8);
      // A wrapper keeps array responses separate from repository/issue objects.
      return suffix.contains("?") ? new JSONObject().put("items",new JSONArray(text)) : new JSONObject(text);
    } finally { connection.disconnect(); }
  }
  private static long integer(JSONObject object,String key) throws Exception {
    Object value=object.get(key);
    if (!(value instanceof Integer || value instanceof Long) || ((Number)value).longValue() <= 0) throw new IllegalArgumentException();
    return ((Number)value).longValue();
  }
  private static long verify(String repo,long pin,String token,long epoch) throws Exception {
    JSONObject object=request(repo,"","GET",token,null,epoch);
    long id=integer(object,"id");
    DiagnosticPolicy.verifyPrivate(repo,pin,object.getString("full_name"),id,
      object.get("private") == Boolean.TRUE,object.get("has_issues") == Boolean.TRUE,object.get("archived") != Boolean.FALSE);
    return id;
  }
  private static void upload(boolean force,long epoch) {
    if (prefs == null || !prefs.getBoolean("enabled",false)) return;
    try {
      long now=System.currentTimeMillis();
      long sequence=DiagnosticHistory.GLOBAL.sequence();
      if (!DiagnosticPolicy.due(now,prefs.getLong("attempt_at",0),sequence,prefs.getLong("acknowledged",0),force)) {
        if (sequence > prefs.getLong("acknowledged",0)) retryLater();
        return;
      }
      active(epoch);
      prefs.edit().putLong("attempt_at",now).apply();
      String stored=DiagnosticSecret.load(app);
      if (stored == null) { state="Pair this phone with a private inbox to enable uploads."; return; }
      JSONObject config=new JSONObject(stored);
      String repo=DiagnosticPolicy.repository(config.getString("repository"));
      String token=config.getString("token"); DiagnosticPolicy.token(token);
      String marker=config.getString("marker");
      if (!marker.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException();
      final long id=integer(config,"id");
      String title="Telegram X diagnostics " + marker;
      // Snapshot body and its sequence together; acknowledge only events actually sent.
      String report;
      synchronized (DiagnosticHistory.GLOBAL) {
        sequence=DiagnosticHistory.GLOBAL.sequence();
        report=DiagnosticHistory.GLOBAL.report(Build.VERSION.SDK_INT,System.currentTimeMillis());
      }
      DiagnosticUpload.send(repo,id,title,report,new DiagnosticUpload.Api() {
        @Override public DiagnosticUpload.Repository repository() throws Exception {
          JSONObject object=request(repo,"","GET",token,null,epoch);
          return new DiagnosticUpload.Repository(object.getString("full_name"),integer(object,"id"),
            object.get("private")==Boolean.TRUE,object.get("has_issues")==Boolean.TRUE,object.get("archived")!=Boolean.FALSE);
        }
        @Override public java.util.List<DiagnosticUpload.Issue> issues(int page) throws Exception {
          JSONArray items=request(repo,"/issues?state=all&per_page=100&page="+page,"GET",token,null,epoch).getJSONArray("items");
          java.util.List<DiagnosticUpload.Issue> result=new java.util.ArrayList<>();
          for(int i=0;i<items.length();i++) {
            JSONObject item=items.getJSONObject(i);
            result.add(new DiagnosticUpload.Issue(item.getString("title"),integer(item,"number"),item.has("pull_request")));
          }
          return result;
        }
        @Override public DiagnosticUpload.Issue write(long number,String title,String body) throws Exception {
          JSONObject result=request(repo,number==0 ? "/issues" : "/issues/"+number,number==0 ? "POST" : "PATCH",token,
            new JSONObject().put("title",title).put("body",body),epoch);
          return new DiagnosticUpload.Issue(result.getString("title"),integer(result,"number"),result.has("pull_request"));
        }
      });
      active(epoch);
      prefs.edit().putLong("acknowledged",sequence).putLong("sent_at",System.currentTimeMillis()).apply();
      state="Private report uploaded. New events are batched at most hourly while Telegram X is running.";
    } catch (Cancelled ignored) { return; }
    catch (ApiFailure failure) { state=DiagnosticPolicy.failure(failure.status); }
    catch (IllegalArgumentException ignored) { state="Upload stopped: inbox identity, privacy, or pairing data could not be verified. Check setup and pair again."; }
    catch (Exception ignored) { state="Upload unavailable. History stays on this phone; check connectivity or pair again if the credential expired."; }
    if (prefs.getBoolean("enabled",false) && epoch==EPOCH.get()) retryLater();
  }
  private static void pair(Activity activity,String repository,String token) {
    final String repo;
    try { repo=DiagnosticPolicy.repository(repository); DiagnosticPolicy.token(token); }
    catch (IllegalArgumentException error) { toast(activity,error.getMessage()); return; }
    if (app == null) { toast(activity,"Diagnostic storage is unavailable."); return; }
    long epoch=EPOCH.incrementAndGet(); state="Checking the private inbox…";
    IO.execute(() -> {
      try {
        long id=verify(repo,0,token,epoch); active(epoch);
        String marker=UUID.randomUUID().toString();
        try {
          String old=DiagnosticSecret.load(app);
          if (old != null) {
            JSONObject previous=new JSONObject(old);
            if (repo.equalsIgnoreCase(previous.getString("repository")) && id==integer(previous,"id") &&
                previous.getString("marker").matches("[a-f0-9-]{36}")) marker=previous.getString("marker");
          }
        } catch (Exception ignored) { }
        JSONObject config=new JSONObject().put("repository",repo).put("id",id).put("token",token).put("marker",marker);
        DiagnosticSecret.save(app,config.toString()); active(epoch);
        synchronized (DiagnosticsHub.class) {
          active(epoch);
          prefs.edit().putBoolean("enabled",true).putString("repository",repo).putLong("acknowledged",0).putLong("attempt_at",0).apply();
        }
        state="Private inbox paired."; upload(true,epoch);
      } catch (Cancelled ignored) { }
      catch (ApiFailure failure) { state=DiagnosticPolicy.failure(failure.status); }
      catch (Exception ignored) { state="Pairing failed. Verify that the repository is private and the token has Issues read/write access. No new pairing was enabled."; }
    });
    toast(activity,"Checking the inbox. Reopen Diagnostics for status.");
  }
  private static synchronized void disconnect() {
    EPOCH.incrementAndGet();
    if (prefs != null) prefs.edit().putBoolean("enabled",false).remove("repository").remove("acknowledged").remove("attempt_at").apply();
    state="Uploads disconnected. Local capture continues. Revoke the inbox token in GitHub if no longer needed.";
    IO.execute(() -> { try { DiagnosticSecret.delete(app); } catch (Exception ignored) { state="Uploads disabled. Revoke the inbox token in GitHub to finish removing access."; } });
  }
  public static void showSettings(Activity activity) {
    initialize(activity);
    LinearLayout layout=new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL);
    int pad=(int)(20*activity.getResources().getDisplayMetrics().density); layout.setPadding(pad,pad/2,pad,pad/2);
    TextView explanation=new TextView(activity);
    explanation.setText("Automatically saves technical failures and recoveries for 7 days (up to 200 events). Messages, accounts, tokens and raw error text are excluded.\n\n"+
      "Optional uploads go to one issue in your private GitHub inbox, at most hourly while this app is running. Codex must be granted access and configured to check that inbox; pairing alone does not notify Codex.\n\n"+
      "Use a fine-grained token for only the private inbox, with Issues read/write. Paste it only here on the phone. It is encrypted using this phone's Android Keystore.\n\n"+state+
      (prefs!=null && prefs.getLong("sent_at",0)>0 ? "\nLast upload: "+new java.util.Date(prefs.getLong("sent_at",0)) : ""));
    layout.addView(explanation);
    final EditText repository=new EditText(activity); repository.setSingleLine(true); repository.setHint("owner/private-inbox");
    repository.setText(prefs==null ? "Scouting2391/telegram-x-diagnostics" : prefs.getString("repository","Scouting2391/telegram-x-diagnostics")); layout.addView(repository);
    final EditText secret=new EditText(activity); secret.setSingleLine(true); secret.setHint("Fine-grained GitHub token (phone only)");
    secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); secret.setSaveEnabled(false);
    if (Build.VERSION.SDK_INT>=26) secret.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    layout.addView(secret);
    Button preview=new Button(activity); preview.setText("View captured history"); layout.addView(preview);
    preview.setOnClickListener(v -> new AlertDialog.Builder(activity).setTitle("Captured diagnostics")
      .setMessage(DiagnosticHistory.GLOBAL.report(Build.VERSION.SDK_INT,System.currentTimeMillis())).setPositiveButton("Close",null).show());
    Button share=new Button(activity); share.setText("Share captured history"); layout.addView(share);
    share.setOnClickListener(v -> {
      Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,DiagnosticHistory.GLOBAL.report(Build.VERSION.SDK_INT,System.currentTimeMillis()));
      try { activity.startActivity(Intent.createChooser(send,"Share technical diagnostics")); }
      catch (android.content.ActivityNotFoundException ignored) { toast(activity,"No sharing app is available."); }
    });
    Button pause=new Button(activity); pause.setText("Disconnect uploads"); layout.addView(pause);
    pause.setOnClickListener(v -> { if (app != null) disconnect(); secret.setText(""); toast(activity,state); });
    ScrollView scroll=new ScrollView(activity); scroll.addView(layout);
    boolean alreadySecure=(activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE)!=0;
    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Push diagnostics").setView(scroll)
      .setPositiveButton("Pair and enable",null).setNeutralButton("Send now",null).setNegativeButton("Close",null).create();
    dialog.setOnDismissListener(d -> { secret.setText(""); if (!alreadySecure) activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE); });
    dialog.setOnShowListener(d -> {
      dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
        String token=secret.getText().toString().trim(); secret.setText(""); pair(activity,repository.getText().toString(),token); dialog.dismiss();
      });
      dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
        if (prefs==null || !prefs.getBoolean("enabled",false)) { toast(activity,"Pair a private inbox first, or use Share captured history."); return; }
        long epoch=EPOCH.get(); IO.execute(() -> upload(true,epoch)); toast(activity,"Upload requested (manual retries are limited to once a minute). Reopen Diagnostics for status.");
      });
    });
    dialog.show();
  }
  private static void toast(Context context,String text) { Toast.makeText(context,text,Toast.LENGTH_LONG).show(); }
  private static final class ApiFailure extends Exception { final int status; ApiFailure(int status){this.status=status;} }
  private static final class Cancelled extends Exception { }
}
