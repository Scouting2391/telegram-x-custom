package org.thunderdog.challegram.github;

import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import javax.crypto.*;
import java.nio.charset.StandardCharsets;

/** Real production policy, history, crypto and upload protocol, without Android UI/network. */
public class DiagnosticCaptureTest {
  static int checks;
  static final long NOW=1800000000000L;
  static void eq(Object a,Object b){if(!a.equals(b))throw new AssertionError(a+" != "+b);checks++;}
  static void ok(boolean x){eq(true,x);}
  interface Work{void run() throws Exception;}
  static void rejected(Work w)throws Exception{try{w.run();}catch(Exception expected){checks++;return;}throw new AssertionError("Unsafe input accepted");}
  static DiagnosticHistory history(){DiagnosticHistory h=new DiagnosticHistory();h.initialize(1817306,null,NOW,null);return h;}
  static DiagnosticUpload.Repository repo(boolean privacy,long id){return new DiagnosticUpload.Repository("owner/private",id,privacy,true,false);}
  static class Api implements DiagnosticUpload.Api {
    int reads,writes; long number; String sent; boolean changePrivacy; boolean mismatchResponse; boolean throwWrite;
    List<DiagnosticUpload.Issue> items=new ArrayList<>();
    public DiagnosticUpload.Repository repository(){return repo(!(changePrivacy && reads++>0),7);}
    public List<DiagnosticUpload.Issue> issues(int page){return page==1 ? items : Collections.emptyList();}
    public DiagnosticUpload.Issue write(long n,String title,String body)throws Exception{
      writes++;number=n;sent=body;
      if(throwWrite)throw new java.io.IOException("fake lost response");
      return new DiagnosticUpload.Issue(mismatchResponse?"wrong":title,n==0?8:n,false);
    }
  }
  static class Connection extends javax.net.ssl.HttpsURLConnection {
    int status, reads; Connection(URL url,int status){super(url);this.status=status;}
    public int getResponseCode(){return status;}
    public java.io.InputStream getInputStream(){reads++;throw new AssertionError("Error/redirect body must not be read");}
    public void disconnect(){} public boolean usingProxy(){return false;} public void connect(){}
    public String getCipherSuite(){return "fixture";}
    public java.security.cert.Certificate[] getLocalCertificates(){return null;}
    public java.security.cert.Certificate[] getServerCertificates(){return null;}
  }
  static Connection connection;
  static int opens, response=302;
  static void transportChecks()throws Exception {
    URL.setURLStreamHandlerFactory(protocol -> protocol.equals("https") ? new URLStreamHandler(){
      protected java.net.URLConnection openConnection(URL url){opens++;connection=new Connection(url,response);return connection;}
    } : null);
    Class<?> hub=Class.forName("org.thunderdog.challegram.github.DiagnosticsHub");
    Method request=hub.getDeclaredMethod("request",String.class,String.class,String.class,String.class,org.json.JSONObject.class,long.class);
    request.setAccessible(true);
    for(int status:new int[]{301,302,307,308,401,403,404,429,500}) {
      response=status;
      try{request.invoke(null,"owner/private","/issues","GET","github_pat_fixture_12345678901234567890",null,0L);throw new AssertionError("HTTP failure accepted");}
      catch(InvocationTargetException e){eq("ApiFailure",e.getCause().getClass().getSimpleName());}
      eq(false,connection.getInstanceFollowRedirects());eq(0,connection.reads);
      eq("api.github.com",connection.getURL().getHost());eq("https",connection.getURL().getProtocol());
      eq("Bearer github_pat_fixture_12345678901234567890",connection.getRequestProperty("Authorization"));
      eq(15000,connection.getReadTimeout());eq(15000,connection.getConnectTimeout());
    }
    int previous=opens;
    rejected(()->request.invoke(null,"owner/private","/issues?token=bad","GET","secret",null,0L));eq(previous,opens);
    rejected(()->request.invoke(null,"owner/private","/issues","GET","secret",null,1L));eq(previous,opens);
  }
  public static void main(String[]args)throws Exception {
    for(String kind:new String[]{"TOKEN","HTTP","REASON","TRANSPORT"}) {
      for(String unsafe:new String[]{"my-token-123","https://private.invalid/a","PERMISSION_DENIED secret","200\nsecret","200|secret","",null})
        ok(!DiagnosticHistory.valid(kind,unsafe));
    }
    ok(!DiagnosticHistory.valid("RAW","API_KEY_INVALID"));
    for(String reason:new String[]{"PERMISSION_DENIED","API_KEY_ANDROID_APP_BLOCKED","UNAVAILABLE"})ok(DiagnosticHistory.valid("REASON",reason));
    DiagnosticHistory h=history();
    h.record("HTTP","503",NOW);h.record("HTTP","503",NOW+1);eq(1,h.size(NOW));eq(1L,h.sequence());
    h.record("HTTP","503",NOW+60000);eq(2,h.size(NOW+60000));
    h.record("TOKEN","READY",NOW+61000);eq(3,h.size(NOW+61000));
    String saved=h.serialize(NOW+61000),body=h.report(31,NOW+61000);
    ok(body.contains("READY"));ok(body.contains("not proof"));ok(body.contains("1817306"));
    DiagnosticHistory restored=new DiagnosticHistory();restored.initialize(1817307,saved,NOW+61000,null);eq(3,restored.size(NOW+61000));eq(3L,restored.sequence());
    restored.record("TOKEN","ERROR",NOW+62000);ok(restored.report(31,NOW+62000).contains("1817307 | TOKEN | ERROR"));
    restored.record("RAW","github_pat_secret",NOW);ok(!restored.serialize(NOW).contains("secret"));
    for(int i=0;i<205;i++)h.record("HTTP",Integer.toString(300+i),NOW+i*60000);
    eq(200,h.size(NOW+204*60000));ok(h.serialize(NOW+204*60000).length()<40000);
    long last=h.sequence();eq(0,h.size(NOW+DiagnosticHistory.RETENTION+205*60000));
    DiagnosticHistory expired=new DiagnosticHistory();expired.initialize(1817306,h.serialize(NOW+DiagnosticHistory.RETENTION+205*60000),NOW+DiagnosticHistory.RETENTION+205*60000,null);
    expired.record("TOKEN","READY",NOW+DiagnosticHistory.RETENTION+206*60000);eq(last+1,expired.sequence());
    DiagnosticHistory corrupt=new DiagnosticHistory();corrupt.initialize(1817306,"TGXDIAG1 3\n1|"+NOW+"|1817305|TOKEN|secret-account\n2|"+NOW+"|1817305|HTTP|403\n",NOW,null);
    eq(1,corrupt.size(NOW));ok(!corrupt.report(31,NOW).contains("secret-account"));
    DiagnosticHistory early=new DiagnosticHistory();early.record("TOKEN","ERROR",NOW);early.initialize(1817306,saved,NOW+61000,null);eq(4,early.size(NOW+61000));
    DiagnosticHistory listener=history();listener.initialize(1817306,null,NOW,()->{throw new IllegalStateException();});listener.record("HTTP","503",NOW);eq(1,listener.size(NOW));
    // Exercise the actual existing Firebase hooks with private strings, including successful recovery.
    DiagnosticHistory.GLOBAL.initialize(1817306,null,System.currentTimeMillis(),null);
    PushDiagnostics.recordFirebaseResponse("HTTP response: [403 Forbidden: {\"status\":\"PERMISSION_DENIED\",\"reason\":\"API_KEY_ANDROID_APP_BLOCKED\",\"message\":\"private-name private-key private-token\"}]");
    PushDiagnostics.recordFirebaseTransportFailure(new UnknownHostException("https://secret.invalid/?token=private-token"));
    PushDiagnostics.recordFirebaseHttpStatus(200);DiagnosticHistory.tokenState(3);
    String capture=DiagnosticHistory.GLOBAL.report(31,System.currentTimeMillis());
    for(String safe:new String[]{"403","PERMISSION_DENIED","API_KEY_ANDROID_APP_BLOCKED","DNS","200","READY"})ok(capture.contains(safe));
    for(String secret:new String[]{"private-name","private-key","private-token","secret.invalid","http"})ok(!capture.contains(secret));
    eq("owner/private",DiagnosticPolicy.repository("https://github.com/owner/private/"));
    for(String invalid:new String[]{"Scouting2391/telegram-x-custom","TGX-Android/Telegram-X","../private","owner/..","owner/repo?key=x","https://evil.invalid/owner/private","owner/repo/path"})rejected(()->DiagnosticPolicy.repository(invalid));
    DiagnosticPolicy.token("github_pat_12345678901234567890");checks++;
    for(String invalid:new String[]{"ghp_12345678901234567890","github_pat_abc\r\nBearer bad","github_pat_",""})rejected(()->DiagnosticPolicy.token(invalid));
    DiagnosticPolicy.verifyPrivate("owner/private",7,"OWNER/private",7,true,true,false);checks++;
    rejected(()->DiagnosticPolicy.verifyPrivate("owner/private",7,"owner/private",7,false,true,false));
    rejected(()->DiagnosticPolicy.verifyPrivate("owner/private",7,"owner/private",8,true,true,false));
    rejected(()->DiagnosticPolicy.verifyPrivate("owner/private",7,"other/private",7,true,true,false));
    rejected(()->DiagnosticPolicy.verifyPrivate("owner/private",7,"owner/private",7,true,false,false));
    rejected(()->DiagnosticPolicy.verifyPrivate("owner/private",7,"owner/private",7,true,true,true));
    rejected(()->DiagnosticPolicy.verifyPrivate("owner/private",0,"owner/private",0,true,true,false));
    eq("https://api.github.com/repos/owner/private/issues/1",DiagnosticPolicy.endpoint("owner/private","/issues/1").toString());
    DiagnosticPolicy.endpoint("owner/private","/issues?state=all&per_page=100&page=10");checks++;
    for(String invalid:new String[]{"/issues/0","/issues/-1","/issues?state=all&per_page=100&page=11","/issues?access_token=secret","/../public/issues","//evil.invalid","/issues/1#x"})rejected(()->DiagnosticPolicy.endpoint("owner/private",invalid));
    ok(!DiagnosticPolicy.due(NOW,NOW-60001,5,5,false));ok(!DiagnosticPolicy.due(NOW,NOW-60001,6,5,false));
    ok(DiagnosticPolicy.due(NOW,NOW-DiagnosticPolicy.INTERVAL,6,5,false));ok(DiagnosticPolicy.due(NOW,NOW-60000,5,5,true));
    ok(!DiagnosticPolicy.due(NOW,NOW-59999,6,5,true));ok(DiagnosticPolicy.due(NOW,0,1,0,false));
    Api fresh=new Api();DiagnosticUpload.send("owner/private",7,"marker","safe body",fresh);eq(1,fresh.writes);eq(0L,fresh.number);eq("safe body",fresh.sent);
    Api existing=new Api();existing.items.add(new DiagnosticUpload.Issue("marker",3,false));DiagnosticUpload.send("owner/private",7,"marker","new body",existing);eq(3L,existing.number);eq(1,existing.writes);
    Api race=new Api();race.changePrivacy=true;rejected(()->DiagnosticUpload.send("owner/private",7,"marker","private body",race));eq(0,race.writes);
    Api duplicate=new Api();duplicate.items.add(new DiagnosticUpload.Issue("marker",3,false));duplicate.items.add(new DiagnosticUpload.Issue("marker",4,false));rejected(()->DiagnosticUpload.send("owner/private",7,"marker","safe",duplicate));eq(0,duplicate.writes);
    Api pr=new Api();pr.items.add(new DiagnosticUpload.Issue("marker",3,true));DiagnosticUpload.send("owner/private",7,"marker","safe",pr);eq(0L,pr.number);
    Api wrong=new Api();wrong.mismatchResponse=true;rejected(()->DiagnosticUpload.send("owner/private",7,"marker","safe",wrong));
    Api lost=new Api();lost.throwWrite=true;rejected(()->DiagnosticUpload.send("owner/private",7,"marker","safe",lost));
    // After a lost POST response, listing the marker finds the existing issue instead of creating another.
    lost.throwWrite=false;lost.items.add(new DiagnosticUpload.Issue("marker",8,false));DiagnosticUpload.send("owner/private",7,"marker","safe",lost);eq(8L,lost.number);
    Api full=new Api(){public List<DiagnosticUpload.Issue> issues(int page){return Collections.nCopies(100,new DiagnosticUpload.Issue("other",1,false));}};
    rejected(()->DiagnosticUpload.send("owner/private",7,"marker","safe",full));eq(0,full.writes);
    Api publicInbox=new Api(){public DiagnosticUpload.Repository repository(){return repo(false,7);}};
    rejected(()->DiagnosticUpload.send("owner/private",7,"marker","safe",publicInbox));eq(0,publicInbox.writes);
    // Actual AES-GCM implementation; the device-keystore provider itself needs an Android device.
    KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);SecretKey key=generator.generateKey();
    byte[] clear="github_pat_12345678901234567890".getBytes(StandardCharsets.UTF_8);
    byte[] encrypted=DiagnosticSecret.encrypt(key,clear);
    ok(Arrays.equals(clear,DiagnosticSecret.decrypt(key,encrypted)));ok(!new String(encrypted,StandardCharsets.ISO_8859_1).contains("github_pat_"));
    ok(!Arrays.equals(encrypted,DiagnosticSecret.encrypt(key,clear)));
    encrypted[encrypted.length-1]^=1;rejected(()->DiagnosticSecret.decrypt(key,encrypted));
    rejected(()->DiagnosticSecret.decrypt(generator.generateKey(),DiagnosticSecret.encrypt(key,clear)));
    rejected(()->DiagnosticSecret.decrypt(key,new byte[27]));
    transportChecks();
    System.out.println("PASS: "+checks+" diagnostic privacy, retention, upload protocol and encryption checks (no device/private-inbox test)");
  }
}
