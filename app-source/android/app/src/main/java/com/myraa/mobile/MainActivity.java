package com.myraa.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.Manifest;
import android.content.pm.PackageManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.webkit.*;
import android.widget.TextView;
import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone native lifecycle, credentials, networking and platform voice.
 *  Only bundled content gets the JS bridge; the avatar is the existing PMX runtime. */
public class MainActivity extends Activity {
  private static final String ORIGIN = "https://appassets.androidplatform.net";
  private static final String KEY_ALIAS = "myraa.mobile.gemini.v1";
  private static final int MICROPHONE_REQUEST = 77;
  private final ExecutorService liveNetwork = Executors.newSingleThreadExecutor();
  private final AtomicInteger liveGeneration = new AtomicInteger();
  private volatile HttpsURLConnection liveConnection;
  private String pendingLiveId;
  private static final int PERMISSION_REQUEST = 90;
  private boolean lastAccessibility = false;
  private static final int MAX_TOOL_ROUNDS = 4;
  private static final int MAX_CALLS_PER_ROUND = 4;
  private static final int MAX_HISTORY_TURNS = 40;
  private final Map<String, ActionWaiter> actionWaiters = new ConcurrentHashMap<>();
  private final Object memoryLock = new Object();
  private MobileActions mobileActions;
  private Runnable pendingUiAction;
  private boolean allowBackgroundLive;
  private final BroadcastReceiver stopLiveReceiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){cancelLive();if(web!=null)web.evaluateJavascript("window.dispatchEvent(new Event('myraa:stop-live'))",null);}};
  private WebView web;
  private SharedPreferences prefs;
  private TextToSpeech tts;
  private volatile boolean ttsReady;
  private volatile boolean foreground;
  private volatile HttpsURLConnection activeConnection;
  private final ExecutorService network = Executors.newSingleThreadExecutor();
  private final ExecutorService settingsNetwork = Executors.newSingleThreadExecutor();
  private final AtomicInteger settingsGeneration = new AtomicInteger();
  private final Object settingsLock = new Object();
  private volatile HttpsURLConnection settingsConnection;
  private GeminiAccess provider = new GeminiAccess();
  private final AtomicInteger generation = new AtomicInteger();
  private final Object chatLock = new Object();
  private boolean busy;
  private int rendererFailures;
  private String personality;

  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    prefs = getSharedPreferences("myraa.mobile.v1", MODE_PRIVATE);
    getWindow().setStatusBarColor(Color.rgb(16,19,23));
    getWindow().setNavigationBarColor(Color.rgb(16,19,23));
    try { personality = new String(readLimited(getAssets().open("personality.txt"), 16000), StandardCharsets.UTF_8); }
    catch (Exception ignored) { personality = "You are Vishal Jarvis, created by Vishal. Default to natural West Bengal Indian Bengali. Be honest about your capabilities."; }
    tts = new TextToSpeech(this, status -> {
      ttsReady = status == TextToSpeech.SUCCESS;
      if (ttsReady) tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
        @Override public void onStart(String id) { emit(json("type", "speech", "active", true)); }
        @Override public void onDone(String id) { emit(json("type", "speech", "active", false)); }
        @Override public void onError(String id) { emit(json("type", "speech", "active", false)); error("tts", "The installed speech engine could not speak. Text remains available.", null); }
      });
    });
    mobileActions=new MobileActions(this,(id,result)->{
      ActionWaiter waiter=actionWaiters.remove(id);
      if(waiter!=null)waiter.complete(result);
      emit(json("type","mobile-action-result","id",id,"result",result));
    });
    if(Build.VERSION.SDK_INT>=33)registerReceiver(stopLiveReceiver,new IntentFilter(LiveVoiceService.STOP),Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(stopLiveReceiver,new IntentFilter(LiveVoiceService.STOP));
    createWebView();
  }

  @SuppressWarnings("SetJavaScriptEnabled") private void createWebView() {
    try {
      android.content.pm.PackageInfo provider = WebView.getCurrentWebViewPackage();
      if (provider != null && Integer.parseInt(provider.versionName.split("\\.")[0]) < 100) {
        showWebViewError("Update Android System WebView to version 100 or later, then tap to retry. Your settings and conversations are safe."); return;
      }
      web = new WebView(this);
    } catch (RuntimeException error) {
      showWebViewError("Android WebView could not start. Check that Android System WebView is installed and enabled, then tap to retry."); return;
    }
    web.setBackgroundColor(Color.rgb(16,19,23));
    WebSettings settings = web.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setMediaPlaybackRequiresUserGesture(false);
    settings.setSupportMultipleWindows(false);
    CookieManager.getInstance().setAcceptCookie(false);
    web.addJavascriptInterface(new NativeBridge(), "MyraaNative");
    web.setWebViewClient(new WebViewClient() {
      @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        android.util.Log.e("MyraaWeb", "Resource error " + error.getErrorCode() + ": " + request.getUrl().getPath());
      }
      @Override public void onPageFinished(WebView view, String url) { android.util.Log.i("MyraaWeb", "Bundled page ready"); }
      @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri uri = request.getUrl();
        if (!"https".equals(uri.getScheme()) || !"appassets.androidplatform.net".equals(uri.getHost())) return blocked();
        String file = uri.getPath();
        if (file == null || !file.startsWith("/assets/") || file.contains("..") || file.contains("\\")) return blocked();
        file = file.substring(8);
        try {
          String ext = file.substring(file.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
          String mime = "application/octet-stream";
          if (ext.equals("html")) mime = "text/html";
          if (ext.equals("js")) mime = "text/javascript";
          if (ext.equals("css")) mime = "text/css";
          if (ext.equals("json")) mime = "application/json";
          if (ext.equals("png")) mime = "image/png";
          if (ext.equals("jpg") || ext.equals("jpeg")) mime = "image/jpeg";
          if (ext.equals("bmp")) mime = "image/bmp";
          Map<String,String> headers = new HashMap<>();
          headers.put("X-Content-Type-Options", "nosniff");
          headers.put("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self' wss://generativelanguage.googleapis.com; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'");
          return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers, getAssets().open(file));
        } catch (Exception ignored) { return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, new ByteArrayInputStream(new byte[0])); }
      }
      @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
      @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        cancelLive();
        view.destroy(); web = null;
        if (++rendererFailures <= 2) createWebView();
        else {
          TextView message = new TextView(MainActivity.this);
          message.setText("Vishal Jarvis's graphics engine stopped. Your settings and history are safe. Tap to retry, or restart the app.");
          message.setPadding(30,60,30,30); message.setOnClickListener(v -> { rendererFailures = 0; createWebView(); }); setContentView(message);
        }
        return true;
      }
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override public boolean onConsoleMessage(ConsoleMessage message) {
        // Log only errors from bundled scripts, never page data or provider payloads.
        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) android.util.Log.e("MyraaWeb", "Bundled page reported an error.");
        return true;
      }
      @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
        new AlertDialog.Builder(MainActivity.this).setTitle("Vishal Jarvis").setMessage(message)
          .setPositiveButton("Confirm", (d,w) -> result.confirm()).setNegativeButton("Cancel", (d,w) -> result.cancel())
          .setOnCancelListener(d -> result.cancel()).show(); return true;
      }
      @Override public void onPermissionRequest(PermissionRequest request) {
        runOnUiThread(() -> {
          if (foreground && ORIGIN.equals(request.getOrigin().toString().replaceAll("/$", "")) &&
              web != null && (ORIGIN + "/assets/mobile.html").equals(web.getUrl()) &&
              checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
              request.getResources().length == 1 && PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(request.getResources()[0]))
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
          else request.deny();
        });
      }
    });
    setContentView(web);
    web.loadUrl(ORIGIN + "/assets/mobile.html");
  }

  private void showWebViewError(String text) {
    TextView message = new TextView(this); message.setText(text); message.setTextColor(Color.WHITE);
    message.setBackgroundColor(Color.rgb(16,19,23)); message.setPadding(30,70,30,30);
    message.setOnClickListener(v -> createWebView()); setContentView(message);
  }

  private WebResourceResponse blocked() { return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", null, new ByteArrayInputStream(new byte[0])); }
  private JSONObject state() {
    JSONObject permissions = json(
      "microphone", granted(Manifest.permission.RECORD_AUDIO),
      "contacts", granted(Manifest.permission.READ_CONTACTS),
      "camera", granted(Manifest.permission.CAMERA),
      "notifications", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
      "overlay", Settings.canDrawOverlays(this),
      "phone", granted(Manifest.permission.CALL_PHONE),
      "sms", granted(Manifest.permission.SEND_SMS),
      "accessibility", MessageAutomationService.available());
    JSONObject value = json("model", prefs.getString("model", "gemini-3.5-flash"), "quality", prefs.getString("quality", "low"),
      "language", prefs.getString("language", "bn-IN"), "spokenReplies", prefs.getBoolean("spokenReplies", false),
      "keyConfigured", prefs.contains("keyCipher"), "keyVerified", prefs.getLong("keyVerifiedAt", 0) > 0 && prefs.getString("verifiedModel", "").equals(prefs.getString("model", "gemini-3.5-flash")),
      "providerErrorCode", prefs.getString("providerErrorCode", ""), "ttsAvailable", ttsReady, "version", "1.3.0",
      "floatingControlsAllowed", Settings.canDrawOverlays(this), "liveModel", prefs.getString("liveModel", "gemini-3.1-flash-live-preview"),
      "permissions", permissions, "memory", memoryArray());

    try { value.put("history", new JSONArray(prefs.getString("history", "[]"))); } catch (Exception ignored) { try { value.put("history", new JSONArray()); } catch (Exception unused) {} }
    return value;
  }

  private boolean granted(String permission) { return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }

  private JSONArray memoryArray() {
    try { return new JSONArray(prefs.getString("memory", "[]")); } catch (Exception ignored) { return new JSONArray(); }
  }

  /** Long-term memory: short, deduplicated, user-approved facts; secrets are refused. */
  private boolean addFact(String raw) {
    String text = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    if (text.length() < 3 || text.length() > 240) return false;
    if (text.matches("(?i).*(api[ _-]?key|password|passcode|otp|secret|card number|aadhaar|ssn|cvv).*")) return false;
    synchronized (memoryLock) {
      JSONArray memory = memoryArray();
      for (int i = 0; i < memory.length(); i++) {
        JSONObject fact = memory.optJSONObject(i);
        if (fact != null && fact.optString("text", "").equalsIgnoreCase(text)) return true;
      }
      while (memory.length() >= 30) memory.remove(0);
      memory.put(json("id", UUID.randomUUID().toString(), "text", text, "at", System.currentTimeMillis()));
      return prefs.edit().putString("memory", memory.toString()).commit();
    }
  }

  private boolean removeFact(String id) {
    if (id == null || id.isEmpty()) return false;
    synchronized (memoryLock) {
      JSONArray memory = memoryArray(), next = new JSONArray();
      for (int i = 0; i < memory.length(); i++) {
        JSONObject fact = memory.optJSONObject(i);
        if (fact != null && !id.equals(fact.optString("id", ""))) next.put(fact);
      }
      return prefs.edit().putString("memory", next.toString()).commit();
    }
  }

  private void cancelActionWaiters() {
    for (String id : new ArrayList<>(actionWaiters.keySet())) {
      ActionWaiter waiter = actionWaiters.remove(id);
      if (waiter != null) waiter.complete(json("status", "cancelled", "message", "Action cancelled."));
      final String pendingId = id;
      runOnUiThread(() -> mobileActions.cancel(pendingId));
    }
  }

  public final class NativeBridge {
    @JavascriptInterface public String getState() { return state().toString(); }
    @JavascriptInterface public void configure(String raw) {
      configureSettings(raw, false);
    }
    @JavascriptInterface public void checkConnection(String raw) {
      configureSettings(raw, true);
    }
    private void configureSettings(String raw, boolean checkOnly) {
      try {
        JSONObject input = new JSONObject(raw);
        String model = input.optString("model", "gemini-3.5-flash");
        if (!model.matches("[A-Za-z0-9._-]{1,100}")) throw new Exception("Enter a valid Gemini model identifier.");
        String liveModel = input.optString("liveModel", prefs.getString("liveModel", "gemini-3.1-flash-live-preview"));
        if (!liveModel.matches("[A-Za-z0-9._-]{1,100}")) throw new Exception("Enter a valid Live voice model identifier.");
        String quality = input.optString("quality", "low");
        if (!Arrays.asList("low", "balanced", "high").contains(quality)) throw new Exception("Invalid graphics preset.");
        String language = input.optString("language", "bn-IN");
        if (!Arrays.asList("bn-IN", "hi-IN", "en-IN", "en-US").contains(language)) throw new Exception("Invalid language.");
        String key = input.optString("key", "").trim();
        final int token = settingsGeneration.incrementAndGet();
        emit(json("type", "settings", "status", "checking", "message", checkOnly ? "Checking Google key and model access…" : key.isEmpty() ? "Saving settings…" : "Checking the new key before saving…"));
        settingsNetwork.execute(() -> {
          try {
            if (settingsGeneration.get() != token) return;
            String candidate = key.isEmpty() && checkOnly ? readKey() : key;
            boolean verified = !candidate.isEmpty();
            if (checkOnly && !verified) throw new GeminiAccess.Failure("KEY_MISSING", 0, "Enter a new key in this field, or save one before checking the connection.");
            if (verified) provider.verify(candidate, model, connection -> {
              settingsConnection = connection;
              if (connection != null && settingsGeneration.get() != token) connection.disconnect();
            });
            synchronized (settingsLock) {
              if (settingsGeneration.get() != token) return;
              SharedPreferences.Editor edit = prefs.edit();
              if (!checkOnly) {
                if (!key.isEmpty()) writeKey(edit, key);
                edit.putString("model", model).putString("quality", quality).putString("language", language)
                  .putBoolean("spokenReplies", input.optBoolean("spokenReplies", false))
                  .putString("liveModel", liveModel);
              }
              if (verified && (!checkOnly || key.isEmpty())) edit.putLong("keyVerifiedAt", System.currentTimeMillis()).putString("verifiedModel", model).remove("providerErrorCode");
              if (!edit.commit()) throw new Exception("The settings could not be saved on this device.");
            }
            if (settingsGeneration.get() == token) emit(json("type", "settings", "status", checkOnly ? "checked" : "saved", "state", state(),
              "message", checkOnly ? "Google accepted the key and this model supports text chat. Send a message to test replies." : "Settings saved."));
          } catch (Exception failure) {
            if (settingsGeneration.get() == token) {
              GeminiAccess.Failure issue = failure instanceof GeminiAccess.Failure ? (GeminiAccess.Failure) failure : null;
              emit(json("type", "settings", "status", "error", "code", issue == null ? "CONNECTION_FAILED" : issue.code,
                "message", issue == null ? "Could not check or save settings. Check your connection and try again. The previous key and settings were kept." : issue.getMessage() + " The previous key and settings were kept."));
            }
          }
        });
      } catch (Exception e) { emit(json("type", "settings", "status", "error", "code", "SETTINGS_INVALID", "message", "Check the model identifier, graphics, language and key fields. Previous settings were kept.")); }
    }
    @JavascriptInterface public void chat(String text, String id) { startChat(text, id); }
    @JavascriptInterface public void cancel() {
      generation.incrementAndGet();
      HttpsURLConnection connection = activeConnection; if (connection != null) connection.disconnect();
      cancelActionWaiters();
      synchronized (chatLock) { busy = false; }
    }
    @JavascriptInterface public void startLive(String id) {
      if (id == null || !id.matches("[A-Za-z0-9-]{1,80}")) return;
      runOnUiThread(() -> {
        cancelLive();
        if (!foreground) { emit(json("type", "live-error", "id", id, "message", "Open Vishal Jarvis to start live voice.")); return; }
        ArrayList<String> needed=new ArrayList<>();
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.RECORD_AUDIO);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!needed.isEmpty()){pendingLiveId=id;requestPermissions(needed.toArray(new String[0]),MICROPHONE_REQUEST);}else provisionLive(id);
      });
    }
    @JavascriptInterface public void mobileAction(String id,String name,String args) {
      if(args==null||args.length()>10000)return;
      runOnUiThread(()->{
        if(!foreground&&!LiveVoiceService.overlayVisible){emit(json("type","mobile-action-result","id",id,"result",json("status","permission_required","message","Return to Vishal Jarvis or enable Floating voice controls in Settings to switch apps by voice while Vishal Jarvis is behind another app.")));return;}
        try {
          if(!foreground&&"send_message".equals(new JSONObject(args).optString("action"))){
            if(pendingUiAction!=null){emit(json("type","mobile-action-result","id",id,"result",json("status","busy","message","Finish the current recipient selection first.")));return;}
            pendingUiAction=()->mobileActions.execute(id,name,args);startActivity(new Intent(MainActivity.this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));return;
          }
        }catch(JSONException ignored){}
        mobileActions.execute(id,name,args);
      });
    }
    @JavascriptInterface public void cancelMobileAction(String id){if(id!=null)runOnUiThread(()->mobileActions.cancel(id));}
    @JavascriptInterface public void openFloatingSettings(){runOnUiThread(()->startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))));}
    @JavascriptInterface public void endLive() { runOnUiThread(() -> cancelLive()); }
    @JavascriptInterface public void speak(String text) { speakText(text); }
    @JavascriptInterface public void stopSpeech() { if (tts != null) tts.stop(); emit(json("type", "speech", "active", false)); }
    @JavascriptInterface public void clearKey() {
      cancel();
      synchronized (settingsLock) {
        settingsGeneration.incrementAndGet();
        HttpsURLConnection connection = settingsConnection; if (connection != null) connection.disconnect();
        prefs.edit().remove("keyCipher").remove("keyIv").remove("keyVerifiedAt").remove("verifiedModel").remove("providerErrorCode").commit();
      }
      emit(json("type", "settings", "status", "cleared", "state", state(), "message", "Saved API key removed."));
    }
    @JavascriptInterface public void clearHistory() { cancel(); prefs.edit().putString("history", "[]").commit(); }
    @JavascriptInterface public void openSettings() { runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))); }
    @JavascriptInterface public String rememberFact(String text) {
      boolean saved = addFact(text);
      String message = saved ? "Saved to memory." : "That fact was not saved. Keep it under 240 characters and never store passwords, OTPs or keys.";
      emit(json("type", "memory", "status", saved ? "saved" : "rejected", "state", state(), "message", message));
      return json("status", saved ? "saved" : "rejected", "message", message).toString();
    }
    @JavascriptInterface public void deleteFact(String id) {
      boolean removed = removeFact(id);
      emit(json("type", "memory", "status", removed ? "deleted" : "unchanged", "state", state(), "message", removed ? "Memory updated." : "That memory entry was already gone."));
    }
    @JavascriptInterface public void clearMemory() {
      synchronized (memoryLock) { prefs.edit().putString("memory", "[]").commit(); }
      emit(json("type", "memory", "status", "cleared", "state", state(), "message", "All remembered facts were deleted."));
    }
    @JavascriptInterface public void copyText(String text) {
      String body = text == null ? "" : text;
      if (body.isEmpty() || body.length() > 20000) return;
      runOnUiThread(() -> {
        try {
          android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
          clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Vishal Jarvis", body));
          emit(json("type", "clipboard", "status", "copied", "message", "Copied to the clipboard."));
        } catch (RuntimeException failure) {
          emit(json("type", "clipboard", "status", "failed", "message", "Android blocked clipboard access."));
        }
      });
    }
    @JavascriptInterface public void shareText(String text) {
      String body = text == null ? "" : text;
      if (body.isEmpty() || body.length() > 4000) return;
      final String id = "share-" + UUID.randomUUID();
      runOnUiThread(() -> mobileActions.execute(id, "mobile_action", json("action", "share_text", "text", body).toString()));
    }
    @JavascriptInterface public void requestPermission(String name) {
      runOnUiThread(() -> {
        if (name == null) return;
        ArrayList<String> needed = new ArrayList<>();
        if ("microphone".equals(name)) { if (!granted(Manifest.permission.RECORD_AUDIO)) needed.add(Manifest.permission.RECORD_AUDIO); }
        else if ("contacts".equals(name)) { if (!granted(Manifest.permission.READ_CONTACTS)) needed.add(Manifest.permission.READ_CONTACTS); }
        else if ("camera".equals(name)) { if (!granted(Manifest.permission.CAMERA)) needed.add(Manifest.permission.CAMERA); }
        else if ("notifications".equals(name)) { if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) needed.add(Manifest.permission.POST_NOTIFICATIONS); }
        else if ("call".equals(name)) {
          if (!granted(Manifest.permission.CALL_PHONE)) needed.add(Manifest.permission.CALL_PHONE);
          if (!granted(Manifest.permission.READ_PHONE_STATE)) needed.add(Manifest.permission.READ_PHONE_STATE);
        }
        else if ("sms".equals(name)) {
          if (!granted(Manifest.permission.SEND_SMS)) needed.add(Manifest.permission.SEND_SMS);
          if (!granted(Manifest.permission.READ_PHONE_STATE)) needed.add(Manifest.permission.READ_PHONE_STATE);
        }
        else if ("accessibility".equals(name)) {
          ComponentName sender = new ComponentName(MainActivity.this, MessageAutomationService.class);
          Intent direct = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
          try { direct.putExtra(":settings:fragment_args_key", sender.flattenToString());
            direct.putExtra(":settings:show_fragment_args", android.os.Bundle.EMPTY);
          } catch (RuntimeException ignored) {}
          try { startActivity(direct); }
          catch (RuntimeException e) { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
          emit(json("type", "permissions", "state", state(), "message",
            "Enable “Vishal Jarvis message sender” in Accessibility so the agent can press Send itself."));
          return;
        }
        else if ("overlay".equals(name)) {
          if (!Settings.canDrawOverlays(MainActivity.this)) startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
          return;
        } else return;
        if (needed.isEmpty()) { emit(json("type", "permissions", "state", state(), "message", "That permission is already granted.")); return; }
        requestPermissions(needed.toArray(new String[0]), PERMISSION_REQUEST);
      });
    }
  }

  /** Result of one streamed model round: visible text, raw parts for echo, and tool calls. */
  private static final class Round {
    final StringBuilder text = new StringBuilder();
    final JSONArray parts = new JSONArray();
    final JSONArray calls = new JSONArray();
  }

  /** Bridges a fire-and-forget Android action into the agent loop. */
  private static final class ActionWaiter {
    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile JSONObject value;
    void complete(JSONObject result) { value = result; latch.countDown(); }
  }

  private void startChat(String input, String id) {
    final String text = input == null ? "" : input.trim();
    if (text.isEmpty() || text.length() > 8000) { error("text", "Enter a message between 1 and 8000 characters.", id); return; }
    synchronized (chatLock) { if (busy) { error("text", "A reply is already running. Stop it or wait.", id); return; } busy = true; }
    final int token = generation.incrementAndGet();
    network.execute(() -> {
      try {
        JSONArray history = new JSONArray(prefs.getString("history", "[]"));
        String reply = runAgent(text, history, id, token);
        if (generation.get() != token) return;
        synchronized (chatLock) {
          JSONArray next = new JSONArray();
          for (int i = Math.max(0, history.length() - 98); i < history.length(); i++) next.put(history.getJSONObject(i));
          next.put(json("role", "user", "text", text, "at", System.currentTimeMillis()));
          next.put(json("role", "model", "text", reply, "at", System.currentTimeMillis()));
          if (!prefs.edit().putString("history", next.toString()).commit()) throw new Exception("The reply arrived but history could not be saved.");
        }
        if (generation.get() != token) return;
        emit(json("type", "reply", "id", id, "text", reply));
        if (prefs.getBoolean("spokenReplies", false)) runOnUiThread(() -> { if (generation.get() == token) speakText(reply); });
      } catch (Exception e) {
        if (generation.get() == token && e instanceof GeminiAccess.Failure) {
          GeminiAccess.Failure issue = (GeminiAccess.Failure)e;
          SharedPreferences.Editor edit = prefs.edit().putString("providerErrorCode", issue.code);
          if (issue.status != 429 && issue.status < 500) edit.putLong("keyVerifiedAt", 0);
          edit.commit();
        }
        if (generation.get() == token) error("text", e instanceof java.net.SocketTimeoutException ? "The provider timed out. Your previous conversation is safe." : e.getMessage() == null ? "Network request failed. Check your connection." : e.getMessage(), id);
      } finally {
        synchronized (chatLock) { if (generation.get() == token) busy = false; }
      }
    });
  }

  /** Streams the model, executes any requested Android tools, and loops until it answers in text. */
  private String runAgent(String text, JSONArray history, String id, int token) throws Exception {
    if (generation.get() != token) throw new InterruptedException("cancelled");
    String key = readKey();
    if (key.isEmpty()) throw new Exception("Add your Gemini API key in Settings.");
    String model = prefs.getString("model", "gemini-3.5-flash");
    if (!model.matches("[A-Za-z0-9._-]{1,100}")) throw new Exception("Invalid saved model identifier.");
    JSONArray contents = new JSONArray();
    for (int i = Math.max(0, history.length() - MAX_HISTORY_TURNS); i < history.length(); i++) {
      JSONObject turn = history.getJSONObject(i);
      contents.put(json("role", turn.getString("role"), "parts", new JSONArray().put(json("text", turn.getString("text")))));
    }
    contents.put(json("role", "user", "parts", new JSONArray().put(json("text", text))));
    String instruction = MobileTools.systemPrompt(personality, memoryArray());
    JSONArray tools = new JSONArray().put(json("functionDeclarations", MobileTools.declarations()));
    StringBuilder visible = new StringBuilder();
    boolean toolsSuppressed = false;
    for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
      if (generation.get() != token) throw new InterruptedException("cancelled");
      Round streamed;
      try {
        streamed = streamRound(key, model, instruction, toolsSuppressed ? null : tools, contents, id, token);
      } catch (GeminiAccess.Failure failure) {
        // Some saved models reject tools outright. Text chat must still work, so retry
        // once without them and tell the user device actions are unavailable.
        if (toolsSuppressed || round != 0 || visible.length() > 0 || failure.status != 400) throw failure;
        toolsSuppressed = true;
        emit(json("type", "capability", "tools", false,
          "message", "This model does not support device actions. Replying in text only; pick another model in Settings to let Vishal Jarvis act."));
        streamed = streamRound(key, model, instruction, null, contents, id, token);
      }
      visible.append(streamed.text);
      if (streamed.calls.length() == 0) return finishReply(visible);
      if (round == MAX_TOOL_ROUNDS) break;
      JSONArray responses = new JSONArray();
      for (int i = 0; i < streamed.calls.length() && i < MAX_CALLS_PER_ROUND; i++) {
        JSONObject call = streamed.calls.optJSONObject(i);
        if (call == null) continue;
        String name = call.optString("name", "");
        JSONObject args = call.optJSONObject("args");
        if (args == null) args = new JSONObject();
        if (generation.get() != token) throw new InterruptedException("cancelled");
        emit(json("type", "tool-call", "id", id, "name", name, "args", args));
        JSONObject outcome = runTool(name, args);
        emit(json("type", "tool-result", "id", id, "name", name, "result", outcome));
        responses.put(json("functionResponse", json("name", name, "response", json("result", outcome))));
      }
      if (responses.length() == 0) break;
      contents.put(json("role", "model", "parts", streamed.parts));
      contents.put(json("role", "user", "parts", responses));
    }
    if (visible.length() == 0) throw new Exception("I could not finish that request. Try saying it in one step.");
    return finishReply(visible);
  }

  private static String finishReply(StringBuilder visible) throws Exception {
    String reply = visible.toString().trim();
    if (reply.isEmpty()) throw new Exception("The provider returned no text reply.");
    return reply;
  }

  /** Executes one tool call for the model and always returns a truthful status object. */
  private JSONObject runTool(String name, JSONObject args) {
    if (MobileTools.REMEMBER.equals(name)) {
      boolean saved = addFact(args.optString("fact", ""));
      if (saved) emit(json("type", "memory", "status", "saved", "state", state()));
      return json("status", saved ? "saved" : "rejected", "message", saved
        ? "Saved to long-term memory. It will be available in future conversations."
        : "That fact was not saved. Keep it short and never store passwords, OTPs, card numbers or API keys.");
    }
    if (!MobileTools.ACTION.equals(name)) return json("status", "unsupported", "message", "That tool is not available in the Vishal Jarvis Android app.");
    final String actionId = "tool-" + UUID.randomUUID();
    ActionWaiter waiter = new ActionWaiter();
    actionWaiters.put(actionId, waiter);
    try {
      final String payload = args.toString();
      runOnUiThread(() -> mobileActions.execute(actionId, MobileTools.ACTION, payload));
      if (!waiter.latch.await(120, TimeUnit.SECONDS)) {
        return json("status", "timeout", "message", "That action did not finish. If a chooser or permission prompt opened, answer it and ask again.");
      }
      JSONObject result = waiter.value;
      return result == null ? json("status", "failed", "message", "Android returned no result for that action.") : result;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return json("status", "cancelled", "message", "The action was cancelled.");
    } finally {
      actionWaiters.remove(actionId);
    }
  }

  /** One streamed Gemini round. Deltas are forwarded to the UI as they arrive. */
  private Round streamRound(String key, String model, String instruction, JSONArray tools, JSONArray contents, String id, int token) throws Exception {
    JSONObject request = json(
      "systemInstruction", json("parts", new JSONArray().put(json("text", instruction))),
      "contents", contents,
      "tools", tools,
      "toolConfig", tools == null ? null : json("functionCallingConfig", json("mode", "AUTO")),
      "generationConfig", json("maxOutputTokens", 2048));
    HttpsURLConnection connection = null;
    try {
      connection = (HttpsURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":streamGenerateContent?alt=sse").openConnection();
      activeConnection = connection;
      connection.setInstanceFollowRedirects(false); connection.setRequestMethod("POST");
      connection.setConnectTimeout(15000); connection.setReadTimeout(90000); connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setRequestProperty("Accept", "text/event-stream");
      connection.setRequestProperty("x-goog-api-key", key);
      try (OutputStream out = connection.getOutputStream()) { out.write(request.toString().getBytes(StandardCharsets.UTF_8)); }
      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) {
        String body = connection.getErrorStream() == null ? "" : new String(readLimited(connection.getErrorStream(), 65536), StandardCharsets.UTF_8);
        throw GeminiAccess.failure(status, body);
      }
      Round round = new Round();
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (generation.get() != token) break;
          if (line.isEmpty() || !line.startsWith("data:")) continue;
          String payload = line.substring(5).trim();
          if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
          JSONObject chunk;
          try { chunk = new JSONObject(payload); } catch (JSONException malformed) { continue; }
          JSONObject failure = chunk.optJSONObject("error");
          if (failure != null) throw GeminiAccess.failure(failure.optInt("code", 500), chunk.toString());
          JSONArray candidates = chunk.optJSONArray("candidates");
          if (candidates == null) continue;
          JSONObject candidate = candidates.optJSONObject(0);
          if (candidate == null) continue;
          JSONObject content = candidate.optJSONObject("content");
          if (content == null) continue;
          JSONArray parts = content.optJSONArray("parts");
          if (parts == null) continue;
          for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) continue;
            JSONObject call = part.optJSONObject("functionCall");
            // Every echoed functionCall must get exactly one functionResponse, so the
            // excess calls are dropped from both lists together.
            if (call != null && round.calls.length() >= MAX_CALLS_PER_ROUND) continue;
            if (round.parts.length() < 200) round.parts.put(part);
            if (call != null) { round.calls.put(call); continue; }
            String piece = part.optString("text", "");
            if (piece.isEmpty() || part.optBoolean("thought", false)) continue;
            if (round.text.length() + piece.length() > 100000) continue;
            round.text.append(piece);
            emit(json("type", "delta", "id", id, "text", piece));
          }
        }
      }
      return round;
    } finally {
      if (connection != null) connection.disconnect();
      if (activeConnection == connection) activeConnection = null;
    }
  }

  private void speakText(String text) {
    runOnUiThread(() -> {
      if (!foreground) return;
      if (!ttsReady || tts == null) { error("tts", "No ready Android text-to-speech engine is available. Text chat still works.", null); return; }
      int language = tts.setLanguage(Locale.forLanguageTag(prefs.getString("language", "bn-IN")));
      if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) { error("tts", "This speech engine does not have the selected language installed.", null); return; }
      String limited = text.substring(0, Math.min(text.length(), TextToSpeech.getMaxSpeechInputLength()));
      if (tts.speak(limited, TextToSpeech.QUEUE_FLUSH, null, "myraa-reply") == TextToSpeech.ERROR) error("tts", "The installed speech engine could not start.", null);
    });
  }

  private SecretKey secretKey() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
    if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
    KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
    return generator.generateKey();
  }
  private void writeKey(SharedPreferences.Editor edit, String key) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, secretKey());
    edit.putString("keyCipher", Base64.encodeToString(cipher.doFinal(key.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP))
      .putString("keyIv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
  }
  private String readKey() throws Exception {
    if (!prefs.contains("keyCipher")) return "";
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, Base64.decode(prefs.getString("keyIv", ""), Base64.NO_WRAP)));
    return new String(cipher.doFinal(Base64.decode(prefs.getString("keyCipher", ""), Base64.NO_WRAP)), StandardCharsets.UTF_8);
  }
  private static byte[] readLimited(InputStream input, int limit) throws IOException {
    try (InputStream source = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] bytes = new byte[8192]; int count;
      while ((count = source.read(bytes)) != -1) { if (out.size() + count > limit) throw new IOException("Response exceeds the size limit."); out.write(bytes, 0, count); }
      return out.toByteArray();
    }
  }
  private static JSONObject json(Object... values) {
    JSONObject result = new JSONObject();
    try { for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i+1]); } catch (JSONException ignored) {}
    return result;
  }
  private void emit(JSONObject event) { runOnUiThread(() -> { if (web != null && !isFinishing()) web.evaluateJavascript("window.MyraaEvents&&window.MyraaEvents(" + event.toString() + ")", null); }); }
  private void error(String service, String message, String id) { JSONObject value = json("type", "error", "service", service, "message", message); if (id != null) try { value.put("id", id); } catch (JSONException ignored) {} emit(value); }

  private void cancelLive() {
    pendingLiveId = null; liveGeneration.incrementAndGet(); allowBackgroundLive=false; stopService(new Intent(this,LiveVoiceService.class));
    HttpsURLConnection connection = liveConnection; liveConnection = null; if (connection != null) connection.disconnect();
  }
  private void provisionLive(String id) {
    pendingLiveId = null; final int mine = liveGeneration.incrementAndGet();
    if(foreground&&(Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)){
      try{startForegroundService(new Intent(this,LiveVoiceService.class));allowBackgroundLive=true;}catch(RuntimeException ignored){allowBackgroundLive=false;}
    }
    liveNetwork.execute(() -> {
      try {
        String key = readKey(); if (key.isEmpty()) throw new IOException("Missing key");
        String token = provider.createLiveToken(key, connection -> {
          if (liveGeneration.get() != mine) { if (connection != null) connection.disconnect(); }
          else liveConnection = connection;
        });
        runOnUiThread(() -> {
          if (liveGeneration.get() != mine || !foreground) return;
          String model = prefs.getString("liveModel", "gemini-3.1-flash-live-preview");
          if (!model.matches("[A-Za-z0-9._-]{1,100}")) model = "gemini-3.1-flash-live-preview";
          emit(json("type", "live-token", "id", id, "token", token, "model", model, "voice", "Aoede",
            "backgroundLive", allowBackgroundLive,
            "instruction", MobileTools.systemPrompt(personality, memoryArray()) + "\nYou are on a live voice call: reply in one or two short spoken sentences, use the mobile_action tool for device tasks, and never invent a result. Messages stay drafts until the user taps Send, and calls only open the dialer."));
        });
      } catch (Exception error) {
        if (liveGeneration.get() == mine) emit(json("type", "live-error", "id", id, "message",
          error instanceof GeminiAccess.Failure ? error.getMessage() : "Could not start Gemini Live. Check the saved key, network and Live model access in Settings."));
      }
    });
  }
  @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == PERMISSION_REQUEST) { emit(json("type", "permissions", "state", state())); return; }
    if(request==MobileActions.CONTACTS_REQUEST){mobileActions.permissionResult();return;}
    if(request==MobileActions.PHONE_REQUEST){mobileActions.phonePermissionResult();emit(json("type","permissions","state",state()));return;}
    if(request==MobileActions.TORCH_REQUEST){mobileActions.torchPermissionResult();return;}
    if (request != MICROPHONE_REQUEST || pendingLiveId == null) return;
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      if (foreground) provisionLive(pendingLiveId);
    } else {
      String id = pendingLiveId; pendingLiveId = null;
      emit(json("type", "live-error", "id", id, "message", "Microphone permission was denied. You can enable it in Android app settings."));
    }
  }
  @Override protected void onPause() {
    foreground=false;super.onPause();boolean keep=allowBackgroundLive;
    if(web!=null){web.evaluateJavascript("window.dispatchEvent(new CustomEvent('myraa:lifecycle',{detail:{active:false,backgroundLive:"+keep+",permissionPrompt:"+(pendingLiveId!=null)+"}}))",null);if(!keep)web.onPause();}
    if(pendingLiveId==null&&!keep)cancelLive();if(tts!=null)tts.stop();
  }
  @Override protected void onResume() {
    foreground=true;super.onResume();if(pendingUiAction!=null){Runnable action=pendingUiAction;pendingUiAction=null;action.run();}if(pendingLiveId!=null&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)provisionLive(pendingLiveId);
    if(web!=null){web.onResume();web.evaluateJavascript("window.dispatchEvent(new CustomEvent('myraa:lifecycle',{detail:{active:true,backgroundLive:"+allowBackgroundLive+"}}))",null);}
    if(web!=null&&MessageAutomationService.available()!=lastAccessibility){lastAccessibility=MessageAutomationService.available();emit(json("type","permissions","state",state()));}
  }
  @Override protected void onStop(){super.onStop();if(!allowBackgroundLive&&pendingLiveId==null){cancelLive();if(web!=null)web.evaluateJavascript("window.dispatchEvent(new CustomEvent('myraa:lifecycle',{detail:{active:false}}))",null);}}
  @Override protected void onDestroy() {
    pendingUiAction=null;if(mobileActions!=null)mobileActions.close();try{unregisterReceiver(stopLiveReceiver);}catch(IllegalArgumentException ignored){}
    cancelLive(); liveNetwork.shutdownNow();
    synchronized (settingsLock) { settingsGeneration.incrementAndGet(); if (settingsConnection != null) settingsConnection.disconnect(); }
    settingsNetwork.shutdownNow();
    generation.incrementAndGet(); if (activeConnection != null) activeConnection.disconnect(); network.shutdownNow();
    if (tts != null) { tts.stop(); tts.shutdown(); }
    if (web != null) { web.removeJavascriptInterface("MyraaNative"); web.destroy(); web = null; }
    super.onDestroy();
  }
}
