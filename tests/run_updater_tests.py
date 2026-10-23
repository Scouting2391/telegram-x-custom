#!/usr/bin/env python3
"""Exercise production validation methods with minimal framework test doubles.

These tests cover trust decisions, not Android UI or the actual installer.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
STUBS = {
    'android/os/Looper.java': 'package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }',
    'android/os/Handler.java': 'package android.os; public class Handler { public Handler(Looper x){} public boolean post(Runnable r){return true;} }',
    'android/os/Build.java': 'package android.os; public class Build { public static class VERSION { public static int SDK_INT=31; } }',
    'android/content/Context.java': '''package android.content; public class Context {
      public String name="org.thunderdog.challegram"; public android.content.pm.PackageManager manager;
      public android.content.SharedPreferences preferences;
      public android.content.SharedPreferences getSharedPreferences(String name,int mode){return preferences;}
      public String getPackageName(){return name;} public android.content.pm.PackageManager getPackageManager(){return manager;}
    }''',
    'android/content/pm/PackageManager.java': '''package android.content.pm; public class PackageManager {
      public static final int GET_SIGNATURES=64, GET_SIGNING_CERTIFICATES=134217728;
      public PackageInfo installed, candidate;
      public PackageInfo getPackageInfo(String p,int f) throws NameNotFoundException{return installed;}
      public PackageInfo getPackageArchiveInfo(String p,int f){return candidate;}
      public static class NameNotFoundException extends Exception {}
    }''',
    'android/content/pm/PackageInfo.java': '''package android.content.pm; public class PackageInfo {
      public String packageName,versionName; public int versionCode;
      public Signature[] signatures; public SigningInfo signingInfo; public ApplicationInfo applicationInfo;
      public long getLongVersionCode(){return versionCode;}
    }''',
    'android/content/pm/ApplicationInfo.java': 'package android.content.pm; public class ApplicationInfo { public int minSdkVersion=24; }',
    'android/content/pm/SigningInfo.java': 'package android.content.pm; public class SigningInfo { public Signature[] values; public Signature[] getApkContentsSigners(){return values;} }',
    'android/content/pm/Signature.java': 'package android.content.pm; public class Signature { private byte[] v; public Signature(byte[] x){v=x.clone();} public byte[] toByteArray(){return v.clone();} }',
    'org/json/JSONObject.java': '''package org.json; public class JSONObject {
      private java.util.Map<String,Object> values=new java.util.HashMap<>();
      public JSONObject put(String k,Object v){values.put(k,v);return this;}
      public Object get(String k){return values.get(k);}
    }''',
}

TEST = r'''
import android.content.Context;
import android.content.pm.*;
import android.os.Build;
import java.io.File;
import java.lang.reflect.*;
import java.net.URL;
import java.security.MessageDigest;
import org.json.JSONObject;

public class UpdaterPolicyTest {
  static Class<?> helper;
  static int checks;
  static Object call(String name, Class<?>[] types, Object... values) throws Throwable {
    Method m=helper.getDeclaredMethod(name,types); m.setAccessible(true);
    try{return m.invoke(null,values);}catch(InvocationTargetException e){throw e.getCause();}
  }
  interface Work { void run() throws Throwable; }
  static void rejected(Work w) throws Throwable {
    try{w.run();}catch(Exception expected){checks++;return;}
    throw new AssertionError("An unsafe input was accepted");
  }
  static void eq(Object a,Object b) {if(!a.equals(b))throw new AssertionError(a+" != "+b);checks++;}
  static Object repo(String text) throws Throwable {return call("normalizeRepository",new Class[]{String.class},text);}
  static void url(String value,boolean api,boolean initial) throws Throwable {
    call("validateUrl",new Class[]{URL.class,String.class,boolean.class,boolean.class},new URL(value),"owner/custom-tgx",api,initial);
  }
  static PackageInfo info(String cert,int version) {
    PackageInfo p=new PackageInfo();p.packageName="org.thunderdog.challegram";
    p.versionName="0.29.1.1817-universal";p.versionCode=version;p.applicationInfo=new ApplicationInfo();
    p.signatures=new Signature[]{new Signature(cert.getBytes(java.nio.charset.StandardCharsets.UTF_8))};
    p.signingInfo=new SigningInfo();p.signingInfo.values=p.signatures;return p;
  }
  public static void main(String[] args) throws Throwable {
    helper=Class.forName("org.thunderdog.challegram.github.GitHubUpdater");
    eq(repo("owner/custom-tgx"),"owner/custom-tgx");
    eq(repo("https://github.com/owner/custom-tgx/"),"owner/custom-tgx");
    eq(repo(""),"");
    for(String v:new String[]{"TGX-Android/Telegram-X","tgx-android/telegram-x","../repo","owner/..",
      "owner/repo/path","owner/repo?token=x","owner/repo#ref","https://evil.test/owner/repo","owner/repo\n"}) {
      rejected(()->repo(v));
    }
    url("https://api.github.com/repos/owner/custom-tgx/releases/latest",true,true);checks++;
    url("https://github.com/owner/custom-tgx/releases/download/v1/app.apk",false,true);checks++;
    url("https://release-assets.githubusercontent.com/path?token=x",false,false);checks++;
    for(String v:new String[]{"http://github.com/owner/custom-tgx/releases/download/v1/app.apk",
      "https://github.com:444/owner/custom-tgx/releases/download/v1/app.apk",
      "https://user:pass@github.com/owner/custom-tgx/releases/download/v1/app.apk",
      "https://evil.test/app.apk","https://github.com/other/repo/releases/download/v1/app.apk",
      "https://github.com/owner/custom-tgx/releases/download/v1/app.apk#x"}) rejected(()->url(v,false,true));
    rejected(()->url("https://api.github.com/repos/other/repo/releases/latest",true,false));
    rejected(()->url("https://release-assets.githubusercontent.com/a",false,true));
    byte[] hash=MessageDigest.getInstance("SHA-256").digest("abc".getBytes("UTF-8"));
    String hex="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    Class<?>[] transfer={long.class,long.class,byte[].class,String.class};
    call("verifyTransfer",transfer,3L,3L,hash,hex);checks++;
    rejected(()->call("verifyTransfer",transfer,2L,3L,hash,hex));
    rejected(()->call("verifyTransfer",transfer,4L,3L,hash,hex));
    rejected(()->call("verifyTransfer",transfer,3L,3L,hash,"0"+hex.substring(1)));
    Class<?>[] positive={JSONObject.class,String.class};
    eq(call("positiveLong",positive,new JSONObject().put("v",1817301),"v"),1817301L);
    for(Object v:new Object[]{0,-1,"1817301",1.5,Double.NaN,Double.POSITIVE_INFINITY,null}) {
      rejected(()->call("positiveLong",positive,new JSONObject().put("v",v),"v"));
    }
    Class<?> asset=Class.forName(helper.getName()+"$Asset");
    Constructor<?> ac=asset.getDeclaredConstructor(String.class,long.class,String.class,URL.class);ac.setAccessible(true);
    Object a=ac.newInstance("app.apk",3L,hex,null);
    Class<?> update=Class.forName(helper.getName()+"$Update");
    Constructor<?> uc=update.getDeclaredConstructor(String.class,String.class,long.class,String.class,asset);uc.setAccessible(true);
    Object u=uc.newInstance("owner/custom-tgx","org.thunderdog.challegram",1817301L,"0.29.1.1817-universal",a);
    Context c=new Context();c.manager=new PackageManager();c.manager.installed=info("our-key",1813300);
    Class<?>[] validate={Context.class,File.class,update};
    c.manager.candidate=info("our-key",1817301);call("verifyPackage",validate,c,new File("app.apk"),u);checks++;
    c.manager.candidate=info("official-other-key",1817301);rejected(()->call("verifyPackage",validate,c,new File("app.apk"),u));
    c.manager.candidate=info("our-key",1817301);c.manager.candidate.packageName="other.package";rejected(()->call("verifyPackage",validate,c,new File("app.apk"),u));
    c.manager.candidate=info("our-key",1813300);rejected(()->call("verifyPackage",validate,c,new File("app.apk"),u));
    c.manager.candidate=info("our-key",1817301);c.manager.installed.versionCode=1817301;rejected(()->call("verifyPackage",validate,c,new File("app.apk"),u));
    c.manager.installed.versionCode=1813300;c.manager.candidate=info("our-key",1817301);c.manager.candidate.versionName="wrong";rejected(()->call("verifyPackage",validate,c,new File("app.apk"),u));
    c.manager.candidate=info("our-key",1817301);c.manager.candidate.applicationInfo.minSdkVersion=32;rejected(()->call("verifyPackage",validate,c,new File("app.apk"),u));
    c.manager.candidate=null;rejected(()->call("verifyPackage",validate,c,new File("app.apk"),u));
    c.manager.candidate=info("our-key",1817301);c.manager.candidate.signingInfo.values=null;rejected(()->call("verifyPackage",validate,c,new File("app.apk"),u));
    Build.VERSION.SDK_INT=24;c.manager.candidate=info("our-key",1817301);call("verifyPackage",validate,c,new File("app.apk"),u);checks++;
    c.manager.candidate=info("other-key",1817301);rejected(()->call("verifyPackage",validate,c,new File("app.apk"),u));
    System.out.println("PASS: "+checks+" updater trust-policy checks (framework test doubles; no device UI test)");
  }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True)
    parser.add_argument('--android-jar', required=True)
    args = parser.parse_args()
    suffix = '.exe' if os.name == 'nt' else ''
    javac, java = (Path(args.java_home) / ('bin/' + name + suffix) for name in ('javac', 'java'))
    test_root = ROOT / '.test-work'
    test_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='tgx-updater-tests-', dir=test_root) as tmp:
        folder = Path(tmp)
        assert folder.resolve().is_relative_to(test_root.resolve())
        prod = folder / 'production'
        prod.mkdir()
        sources = list((ROOT / 'updater/src').rglob('*.java'))
        subprocess.run([str(javac), '--release', '8', '-Xlint:-options', '-cp', args.android_jar,
                        '-d', str(prod), *map(str, sources)], check=True)
        stubs = []
        for name, text in STUBS.items():
            path = folder / 'stubs' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding='utf-8')
            stubs.append(path)
        test = folder / 'UpdaterPolicyTest.java'
        test.write_text(TEST, encoding='utf-8')
        classes = folder / 'tests'
        classes.mkdir()
        cp = os.pathsep.join([str(prod), args.android_jar])
        subprocess.run([str(javac), '--release', '8', '-Xlint:-options', '-cp', cp, '-d', str(classes),
                        *map(str, stubs), str(test)], check=True)
        subprocess.run([str(java), '-cp', os.pathsep.join([str(classes), cp]), 'UpdaterPolicyTest'], check=True)
        subprocess.run([str(javac), '--release', '8', '-Xlint:-options', '-cp', cp,
                        '-d', str(classes), str(ROOT / 'tests/UpdaterDefaultsTest.java')], check=True)
        subprocess.run([str(java), '-cp', os.pathsep.join([str(classes), cp]), 'UpdaterDefaultsTest'], check=True)
        subprocess.run([str(javac), '--release', '8', '-Xlint:-options', '-cp', os.pathsep.join([str(classes), cp]),
                        '-d', str(classes), str(ROOT / 'tests/PushWarningPolicyTest.java')], check=True)
        subprocess.run([str(java), '-cp', os.pathsep.join([str(classes), cp]), 'PushWarningPolicyTest'], check=True)
        subprocess.run([str(javac), '--release', '8', '-Xlint:-options', '-cp', str(prod),
                        '-d', str(classes), str(ROOT / 'tests/PushDiagnosticsTest.java')], check=True)
        subprocess.run([str(java), '-cp', os.pathsep.join([str(classes), str(prod)]), 'PushDiagnosticsTest'], check=True)
        subprocess.run([str(javac), '--release', '8', '-Xlint:-options', '-cp', cp,
                        '-d', str(classes), str(ROOT / 'tests/DiagnosticCaptureTest.java')], check=True)
        subprocess.run([str(java), '-cp', os.pathsep.join([str(classes), cp]),
                        'org.thunderdog.challegram.github.DiagnosticCaptureTest'], check=True)


if __name__ == '__main__':
    main()
