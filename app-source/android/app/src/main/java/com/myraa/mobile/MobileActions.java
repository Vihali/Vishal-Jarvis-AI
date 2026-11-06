package com.myraa.mobile;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telephony.*;
import android.view.KeyEvent;
import org.json.*;
import java.util.*;

/** Bounded Android intents for the Vishal Jarvis agent.
 *  Every action either opens a system UI the user confirms or performs a
 *  narrowly scoped, reversible device change. No accessibility, no arbitrary
 *  URLs, no shell, no silent messaging, no file or screen access. */
public final class MobileActions {
  public static final int CONTACTS_REQUEST=88;
  public static final int TORCH_REQUEST=89;
  private static final int MAX_APPS=150;
  public interface Result { void complete(String id,JSONObject result); }
  public interface Launcher { boolean installed(String pkg); Intent app(String label); boolean open(Intent intent); }
  public interface Contacts { boolean needsPermission(); List<Recipient> find(String name); }
  public static final class Recipient {
    public final String name,phone;
    public Recipient(String name,String phone){this.name=name;this.phone=phone;}
  }
  private final Activity activity;
  private final Result result;
  private final Launcher launcher;
  private final Contacts contacts;
  private Runnable pending;
  private String pendingId;
  private String pendingTorchId;
  private Boolean pendingTorchState;
  private AlertDialog chooser;
  private String chooserId;
  private final Map<String,JSONObject> completed=new LinkedHashMap<>();
  public MobileActions(Activity activity,Result result) {
    this(activity,result,new Launcher(){
      public boolean installed(String pkg){try{activity.getPackageManager().getPackageInfo(pkg,0);return true;}catch(Exception e){return false;}}
      public Intent app(String label){
        PackageManager pm=activity.getPackageManager();
        Intent main=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Intent loose=null;
        for(ResolveInfo info:pm.queryIntentActivities(main,0)){
          if(!info.activityInfo.exported)continue;
          String candidate=normalize(info.loadLabel(pm).toString());
          if(candidate.equals(normalize(label)))
            return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(info.activityInfo.packageName,info.activityInfo.name);
          if(loose==null&&candidate.length()>2&&(candidate.startsWith(normalize(label))||candidate.contains(normalize(label))))
            loose=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(info.activityInfo.packageName,info.activityInfo.name);
        }
        return loose;
      }
      public boolean open(Intent intent){try{activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return true;}catch(ActivityNotFoundException|SecurityException e){return false;}}
    },new Contacts() {
      public boolean needsPermission(){return true;}
      public List<Recipient> find(String name){
        List<Recipient> found=new ArrayList<>();Set<String> seen=new HashSet<>();
        String escaped=name.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
        try(Cursor c=activity.getContentResolver().query(Phone.CONTENT_URI,new String[]{Phone.DISPLAY_NAME,Phone.NUMBER,Phone.NORMALIZED_NUMBER},Phone.DISPLAY_NAME+" LIKE ? ESCAPE '\\'",new String[]{"%"+escaped+"%"},Phone.DISPLAY_NAME+" ASC")){
          if(c!=null)while(c.moveToNext()&&found.size()<20){
            String raw=c.getString(2);if(raw==null||raw.isEmpty())raw=c.getString(1);
            String phone=international(raw,activity);if(phone!=null&&seen.add(phone))found.add(new Recipient(c.getString(0),phone));
          }
        }
        List<Recipient> exact=new ArrayList<>();for(Recipient r:found)if(normalize(r.name).equals(normalize(name)))exact.add(r);
        return exact.isEmpty()?found:exact;
      }
    });
  }
  public MobileActions(Activity activity,Result result,Launcher launcher,Contacts contacts){this.activity=activity;this.result=result;this.launcher=launcher;this.contacts=contacts;}
  public static String normalize(String input){return input==null?"":input.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");}
  private static String international(String raw,Activity activity){
    if(raw==null)return null;String clean=raw.replaceAll("[ ()-]","");if(clean.startsWith("00"))clean="+"+clean.substring(2);
    if(clean.matches("\\+[1-9][0-9]{7,14}"))return clean.substring(1);
    String country="";try{country=activity.getSystemService(TelephonyManager.class).getNetworkCountryIso();}catch(Exception ignored){}
    if(country==null||country.length()!=2){String language=activity.getSharedPreferences("myraa.mobile.v1",0).getString("language","bn-IN");country=language.endsWith("IN")?"IN":"US";}
    String e164=PhoneNumberUtils.formatNumberToE164(raw,country.toUpperCase(Locale.ROOT));return e164==null?null:e164.substring(1);
  }
  public static Intent search(String target,String query){
    if(query==null||query.trim().isEmpty()||query.length()>1000)throw new IllegalArgumentException("Say what you want to search for.");
    Uri uri="youtube".equals(target)?Uri.parse("https://www.youtube.com/results").buildUpon().appendQueryParameter("search_query",query.trim()).build():Uri.parse("https://www.google.com/search").buildUpon().appendQueryParameter("q",query.trim()).build();
    return new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE);
  }
  public static Intent draft(String phone,String message,String pkg){
    if(phone==null||!phone.matches("[1-9][0-9]{7,14}")||message==null||message.trim().isEmpty()||message.length()>4000)throw new IllegalArgumentException("A valid recipient and message are required.");
    return new Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/"+phone).buildUpon().appendQueryParameter("text",message).build()).setPackage(pkg);
  }
  /** Only http(s) links with a real host, no embedded credentials, no local targets. */
  public static Intent web(String raw){
    if(raw==null)throw new IllegalArgumentException("Say which page to open.");
    String value=raw.trim();
    if(value.isEmpty()||value.length()>2048)throw new IllegalArgumentException("Include the full page address.");
    if(!value.matches("(?i)https?://[^\\s]+"))throw new IllegalArgumentException("Only https links can be opened. Give the full address including https://.");
    Uri uri=Uri.parse(value);
    String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT);
    String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);
    if(!scheme.equals("https")&&!scheme.equals("http"))throw new IllegalArgumentException("Only https links can be opened.");
    if(host.isEmpty()||!host.contains(".")||host.equals("localhost")||host.endsWith(".local")||host.endsWith(".internal"))throw new IllegalArgumentException("That address is not a public web page.");
    if(uri.getUserInfo()!=null)throw new IllegalArgumentException("Links with embedded credentials are not allowed.");
    return new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE);
  }
  private static JSONObject json(Object... values){
    JSONObject value=new JSONObject();
    try{for(int i=0;i<values.length;i+=2)value.put(String.valueOf(values[i]),values[i+1]);}catch(JSONException ignored){}
    return value;
  }
  private JSONObject status(String status,String message,JSONObject data){
    JSONObject value=new JSONObject();
    try{value.put("status",status).put("message",message);if(data!=null)value.put("data",data);}catch(JSONException ignored){}
    return value;
  }
  private void finish(String id,String status,String message){finish(id,status,message,null);}
  private void finish(String id,String status,String message,JSONObject data){publish(id,status(status,message,data));}
  private void publish(String id,JSONObject value){
    completed.put(id,value);while(completed.size()>64)completed.remove(completed.keySet().iterator().next());result.complete(id,value);
  }
  public void execute(String id,String name,String raw){
    if(id==null||!id.matches("[A-Za-z0-9-]{1,80}"))return;
    if(completed.containsKey(id)){result.complete(id,completed.get(id));return;}
    if(!"mobile_action".equals(name)){finish(id,"unsupported","That mobile action is unavailable.");return;}
    try{
      JSONObject args=new JSONObject(raw);String action=args.optString("action");
      if("open_app".equals(action)){openApp(id,args.optString("app"));return;}
      if("search_youtube".equals(action)||"search_web".equals(action)){
        boolean youtube=action.equals("search_youtube");Intent intent=search(youtube?"youtube":"web",args.optString("query"));String pkg=youtube?"com.google.android.youtube":"com.android.chrome";
        if(launcher.installed(pkg))intent.setPackage(pkg);else if(youtube&&launcher.installed("com.android.chrome"))intent.setPackage("com.android.chrome");
        boolean opened=launcher.open(intent);if(!opened&&intent.getPackage()!=null)opened=launcher.open(new Intent(intent).setPackage(null));
        finish(id,opened?"opened":"unavailable",opened?(youtube?"YouTube":"Browser")+" search opened.":"No app can open this search on this device.");return;
      }
      if("open_url".equals(action)){openUrl(id,args.optString("url"));return;}
      if("whatsapp_message".equals(action)){message(id,args.optString("recipient"),args.optString("message"),false);return;}
      if("send_sms".equals(action)){message(id,args.optString("recipient"),args.optString("message"),true);return;}
      if("call_number".equals(action)){dial(id,args.optString("phone"));return;}
      if("call_contact".equals(action)){callContact(id,args.optString("recipient"));return;}
      if("set_flashlight".equals(action)){setFlashlight(id,args.optBoolean("enabled",false));return;}
      if("open_wifi_settings".equals(action)){openWifiSettings(id);return;}
      if("open_settings_panel".equals(action)){openPanel(id,args.optString("panel"));return;}
      if("set_alarm".equals(action)){setAlarm(id,args.optInt("hour",-1),args.optInt("minute",-1),args.optString("label"));return;}
      if("set_timer".equals(action)){setTimer(id,args.optInt("seconds",-1),args.optString("label"));return;}
      if("create_event".equals(action)){createEvent(id,args);return;}
      if("navigate_to".equals(action)){navigate(id,args.optString("destination"));return;}
      if("share_text".equals(action)){share(id,args.optString("text"));return;}
      if("copy_text".equals(action)){copy(id,args.optString("text"));return;}
      if("media_control".equals(action)){media(id,args.optString("command"));return;}
      if("set_volume".equals(action)){volume(id,args);return;}
      if("device_status".equals(action)){deviceStatus(id);return;}
      if("list_apps".equals(action)){listApps(id);return;}
      if("open_camera".equals(action)){openCamera(id);return;}
      finish(id,"unsupported","Supported actions: open apps, open a web page, search YouTube/web, WhatsApp and SMS drafts, dial a number or saved contact, flashlight, Wi-Fi and system panels, alarms, timers, calendar events, navigation, share, copy, media and volume control, device status and installed apps.");
    }catch(IllegalArgumentException e){finish(id,"invalid",e.getMessage());}catch(Exception e){finish(id,"failed","Android could not complete this action. Nothing was sent.");}
  }
  private void openUrl(String id,String url){
    Intent intent=web(url);boolean opened=launcher.open(intent);
    finish(id,opened?"opened":"unavailable",opened?"Opened the link in your browser.":"No browser could open that link.");
  }
  private void openCamera(String id){
    Intent intent=new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
    boolean opened=launcher.open(intent);
    finish(id,opened?"opened":"unavailable",opened?"Camera opened.":"This device has no camera app available.");
  }
  private void callContact(String id,String recipient){
    if(recipient==null||recipient.trim().isEmpty()||recipient.length()>100){finish(id,"invalid","যাঁকে কল করতে চাও, তাঁর সেভ করা নামটি বলো।");return;}
    if(recipient.trim().matches("\\+?[0-9 ()-]+")){dial(id,recipient);return;}
    if(contacts.needsPermission()&&activity.checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED){
      if(pending!=null||pendingTorchId!=null){finish(id,"busy","আগের অনুমতির কাজটি শেষ করো।");return;}
      pendingId=id;pending=()->callContact(id,recipient);activity.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},CONTACTS_REQUEST);return;
    }
    List<Recipient> matches=contacts.find(recipient.trim());
    if(matches.isEmpty()){finish(id,"not_found","এই নামে ফোন নম্বর-সহ কোনো Contact পেলাম না। Contacts permission চালু আছে কি না দেখো, অথবা পুরো নামটি বলো।");return;}
    if(matches.size()==1){dialContact(id,matches.get(0));return;}
    if(chooser!=null){finish(id,"busy","আগের Contact নির্বাচনটি আগে শেষ করো।");return;}
    String[] labels=new String[matches.size()];for(int i=0;i<labels.length;i++)labels[i]=matches.get(i).name+" · +"+matches.get(i).phone;
    chooserId=id;chooser=new AlertDialog.Builder(activity).setTitle("কাকে কল করবে — "+recipient+"?").setItems(labels,(d,index)->{chooser=null;dialContact(id,matches.get(index));})
      .setNegativeButton("বাতিল",(d,w)->{chooser=null;finish(id,"cancelled","কল বাতিল করা হয়েছে।");}).setOnCancelListener(d->{chooser=null;finish(id,"cancelled","কল বাতিল করা হয়েছে।");}).show();
  }
  private void dialContact(String id,Recipient person){
    try{boolean opened=launcher.open(new Intent(Intent.ACTION_DIAL,Uri.fromParts("tel","+"+person.phone,null)));
      finish(id,opened?"dialer_opened":"unavailable",opened?person.name+"-এর নম্বর ডায়ালারে খুলেছি। নম্বরটি মিলিয়ে Call বোতাম চাপো।":"ডায়ালার খোলা যায়নি। কোনো কল করা হয়নি।");
    }catch(Exception e){finish(id,"failed","ডায়ালার খোলা যায়নি। কোনো কল করা হয়নি।");}
  }
  private void setFlashlight(String id,boolean enabled){
    if(activity.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
      if(pending!=null||pendingTorchId!=null){finish(id,"busy","আগের অনুমতির কাজটি শেষ করো।");return;}
      pendingTorchId=id;pendingTorchState=enabled;activity.requestPermissions(new String[]{Manifest.permission.CAMERA},TORCH_REQUEST);return;
    }
    applyFlashlight(id,enabled);
  }
  private void applyFlashlight(String id,boolean enabled){
    try{
      CameraManager manager=(CameraManager)activity.getSystemService(Context.CAMERA_SERVICE);String cameraId=null;
      for(String candidate:manager.getCameraIdList())if(manager.getCameraCharacteristics(candidate).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE)==Boolean.TRUE){cameraId=candidate;break;}
      if(cameraId==null){finish(id,"unavailable","এই ফোনে Flashlight হার্ডওয়্যার পাওয়া যায়নি।");return;}
      manager.setTorchMode(cameraId,enabled);finish(id,"torch_changed",enabled?"Flashlight অন করেছি।":"Flashlight বন্ধ করেছি।");
    }catch(SecurityException e){finish(id,"permission_required","Flashlight ব্যবহারের জন্য Camera permission দিতে হবে।");}
    catch(Exception e){finish(id,"failed","Flashlight পরিবর্তন করা যায়নি। অন্য কোনো অ্যাপ ক্যামেরা ব্যবহার করছে কি না দেখো।");}
  }
  private void openWifiSettings(String id){
    Intent intent=Build.VERSION.SDK_INT>=29?new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY):new Intent(Settings.ACTION_WIFI_SETTINGS);
    boolean opened=launcher.open(intent);if(!opened&&Build.VERSION.SDK_INT>=29)opened=launcher.open(new Intent(Settings.ACTION_WIFI_SETTINGS));finish(id,opened?"settings_opened":"unavailable",opened?"Wi-Fi ও Internet প্যানেল খুলেছি। Android-এর নিয়মে এখানে Wi-Fi সুইচটি তোমাকেই অন করতে হবে।":"এই ফোনে Wi-Fi সেটিংস খোলা যায়নি।");
  }
  private void openPanel(String id,String raw){
    String panel=normalize(raw);
    String action;
    if(panel.equals("bluetooth"))action=Settings.ACTION_BLUETOOTH_SETTINGS;
    else if(panel.equals("sound"))action=Settings.ACTION_SOUND_SETTINGS;
    else if(panel.equals("display"))action=Settings.ACTION_DISPLAY_SETTINGS;
    else if(panel.equals("battery"))action=Build.VERSION.SDK_INT>=22?Settings.ACTION_BATTERY_SAVER_SETTINGS:Settings.ACTION_SETTINGS;
    else if(panel.equals("location"))action=Settings.ACTION_LOCATION_SOURCE_SETTINGS;
    else if(panel.equals("apps"))action=Settings.ACTION_APPLICATION_SETTINGS;
    else if(panel.equals("notifications"))action=Settings.ACTION_NOTIFICATION_SETTINGS;
    else if(panel.equals("data"))action=Settings.ACTION_DATA_USAGE_SETTINGS;
    else if(panel.equals("airplane"))action=Settings.ACTION_AIRPLANE_MODE_SETTINGS;
    else if(panel.equals("accessibility"))action=Settings.ACTION_ACCESSIBILITY_SETTINGS;
    else if(panel.equals("nfc"))action=Settings.ACTION_NFC_SETTINGS;
    else if(panel.equals("wifi")){openWifiSettings(id);return;}
    else if(panel.equals("home"))action=Settings.ACTION_HOME_SETTINGS;
    else action=Settings.ACTION_SETTINGS;
    boolean opened=launcher.open(new Intent(action));
    finish(id,opened?"settings_opened":"unavailable",opened?"Opened the "+panel+" settings. Android does not let this app change that switch silently, so tap the control yourself.":"This device could not open those settings.");
  }
  private void setAlarm(String id,int hour,int minute,String label){
    if(hour<0||hour>23||minute<0||minute>59){finish(id,"invalid","Say the alarm time on a 24-hour clock, for example 07:30.");return;}
    Intent intent=new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR,hour).putExtra(AlarmClock.EXTRA_MINUTES,minute)
      .putExtra(AlarmClock.EXTRA_SKIP_UI,false).putExtra(AlarmClock.EXTRA_MESSAGE,limit(label,80,"Vishal Jarvis"));
    boolean opened=launcher.open(intent);
    finish(id,opened?"alarm_opened":"unavailable",opened?String.format(Locale.US,"Alarm editor opened for %02d:%02d. Confirm it in your clock app.",hour,minute):"This device has no alarm app that accepts new alarms.");
  }
  private void setTimer(String id,int seconds,String label){
    if(seconds<1||seconds>86400){finish(id,"invalid","Say a timer between 1 second and 24 hours.");return;}
    Intent intent=new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH,seconds)
      .putExtra(AlarmClock.EXTRA_SKIP_UI,false).putExtra(AlarmClock.EXTRA_MESSAGE,limit(label,80,"Vishal Jarvis"));
    boolean opened=launcher.open(intent);
    finish(id,opened?"timer_opened":"unavailable",opened?"Timer editor opened for "+seconds+" seconds. Confirm it in your clock app.":"This device has no timer app that accepts new timers.");
  }
  private void createEvent(String id,JSONObject args){
    String title=limit(args.optString("title"),200,"");
    if(title.isEmpty()){finish(id,"invalid","Say the event title.");return;}
    long begin=args.optLong("beginTime",0),end=args.optLong("endTime",0);
    long now=System.currentTimeMillis();
    if(begin<=0){finish(id,"invalid","Give the event start as a Unix timestamp in milliseconds.");return;}
    if(begin<now-366L*24*3600*1000||begin>now+5L*366*24*3600*1000){finish(id,"invalid","That event date is outside the supported range.");return;}
    if(end<=begin)end=begin+3600000L;
    if(end-begin>7L*24*3600*1000){finish(id,"invalid","Events longer than seven days are not supported.");return;}
    Intent intent=new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
      .putExtra(CalendarContract.Events.TITLE,title).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,begin)
      .putExtra(CalendarContract.EXTRA_EVENT_END_TIME,end).putExtra(CalendarContract.Events.EVENT_LOCATION,limit(args.optString("location"),200,""))
      .putExtra(CalendarContract.Events.DESCRIPTION,limit(args.optString("description"),1000,""));
    boolean opened=launcher.open(intent);
    finish(id,opened?"event_opened":"unavailable",opened?"Calendar editor opened. Review the event and tap Save; nothing was added yet.":"This device has no calendar app that accepts new events.");
  }
  private void navigate(String id,String destination){
    String target=limit(destination,300,"");
    if(target.isEmpty()){finish(id,"invalid","Say where you want to go.");return;}
    Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse("geo:0,0?q="+Uri.encode(target)));
    boolean opened=launcher.open(intent);
    finish(id,opened?"navigated":"unavailable",opened?"Maps opened for "+target+". Start navigation when you are ready.":"No maps app could handle that destination.");
  }
  private void share(String id,String text){
    String body=limit(text,4000,"");
    if(body.isEmpty()){finish(id,"invalid","There is nothing to share.");return;}
    Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,body);
    boolean opened=launcher.open(Intent.createChooser(send,"Share with"));
    finish(id,opened?"shared":"unavailable",opened?"Android share sheet opened. Choose the app yourself.":"No app can share text on this device.");
  }
  private void copy(String id,String text){
    String body=limit(text,8000,"");
    if(body.isEmpty()){finish(id,"invalid","There is nothing to copy.");return;}
    try{
      ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
      clipboard.setPrimaryClip(ClipData.newPlainText("Vishal Jarvis",body));
      finish(id,"copied","Copied to the clipboard.");
    }catch(RuntimeException e){finish(id,"failed","Android blocked clipboard access.");}
  }
  private void media(String id,String command){
    String value=normalize(command);int key;
    if(value.equals("play_pause")||value.equals("play")||value.equals("pause")||value.equals("toggle"))key=KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
    else if(value.equals("next"))key=KeyEvent.KEYCODE_MEDIA_NEXT;
    else if(value.equals("previous")||value.equals("prev"))key=KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    else {finish(id,"invalid","Media command must be play_pause, next or previous.");return;}
    try{
      AudioManager audio=(AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);
      long now=SystemClock.uptimeMillis();
      audio.dispatchMediaKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,key,0));
      audio.dispatchMediaKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_UP,key,0));
      finish(id,"media_sent","Sent the media "+value.replace('_',' ')+" key. If nothing changes, start playback in your music app first.");
    }catch(RuntimeException e){finish(id,"failed","Android blocked the media key. Nothing was changed.");}
  }
  private void volume(String id,JSONObject args){
    try{
      AudioManager audio=(AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);
      int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),current=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
      int target=current;
      if(args.has("level")){
        double percent=args.optDouble("level",-1);if(percent<0||percent>100){finish(id,"invalid","Volume level must be between 0 and 100.");return;}
        target=(int)Math.round(max*percent/100.0);
      }else if(args.has("direction")){
        String direction=normalize(args.optString("direction"));
        int step=Math.max(1,max/10);target=current+(direction.equals("down")||direction.equals("lower")?-step:step);
      }else{finish(id,"invalid","Say the volume level or whether to raise or lower it.");return;}
      target=Math.max(0,Math.min(max,target));
      audio.setStreamVolume(AudioManager.STREAM_MUSIC,target,0);
      int percent=max==0?0:(int)Math.round(target*100.0/max);
      finish(id,"volume_changed","Media volume is now "+percent+"%.",json("level",percent));
    }catch(RuntimeException e){finish(id,"failed","Android blocked the volume change. Do Not Disturb may be limiting it.");}
  }
  private void deviceStatus(String id){
    JSONObject data=new JSONObject();
    try{
      Intent battery=activity.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
      int level=battery==null?-1:battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
      int scale=battery==null?-1:battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
      int status=battery==null?0:battery.getIntExtra(BatteryManager.EXTRA_STATUS,BatteryManager.BATTERY_STATUS_UNKNOWN);
      int percent=level>=0&&scale>0?(int)Math.round(level*100.0/scale):-1;
      data.put("batteryPercent",percent);
      data.put("charging",status==BatteryManager.BATTERY_STATUS_CHARGING||status==BatteryManager.BATTERY_STATUS_FULL);
      ConnectivityManager cm=(ConnectivityManager)activity.getSystemService(Context.CONNECTIVITY_SERVICE);
      Network active=cm==null?null:cm.getActiveNetwork();NetworkCapabilities capabilities=active==null?null:cm.getNetworkCapabilities(active);
      String network="offline";
      if(capabilities!=null){
        if(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))network="wifi";
        else if(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))network="mobile data";
        else if(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))network="ethernet";
        else if(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))network="vpn";
        else network="connected";
      }
      data.put("network",network);
      StatFs storage=new StatFs(activity.getFilesDir().getAbsolutePath());
      data.put("freeStorageMb",storage.getAvailableBytes()/(1024*1024));
      AudioManager audio=(AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);
      int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
      data.put("mediaVolumePercent",max==0?0:(int)Math.round(audio.getStreamVolume(AudioManager.STREAM_MUSIC)*100.0/max));
      data.put("device",Build.MANUFACTURER+" "+Build.MODEL);
      data.put("android",Build.VERSION.RELEASE);
      data.put("sdk",Build.VERSION.SDK_INT);
      data.put("appVersion","1.2.0");
      StringBuilder summary=new StringBuilder();
      summary.append("Battery ").append(percent<0?"unknown":percent+"%").append(data.optBoolean("charging")?", charging":"").append(". ");
      summary.append("Network: ").append(network).append(". ");
      summary.append("Free storage: ").append(data.opt("freeStorageMb")).append(" MB. ");
      summary.append("Media volume: ").append(data.opt("mediaVolumePercent")).append("%. ");
      summary.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE).append('.');
      finish(id,"info",summary.toString(),data);
    }catch(Exception e){finish(id,"failed","Android did not return device status.");}
  }
  private void listApps(String id){
    try{
      PackageManager pm=activity.getPackageManager();
      Intent main=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
      Set<String> seen=new TreeSet<>();
      for(ResolveInfo info:pm.queryIntentActivities(main,0)){
        if(!info.activityInfo.exported)continue;
        String label=normalize(info.loadLabel(pm).toString());
        if(!label.isEmpty())seen.add(label);
        if(seen.size()>=MAX_APPS)break;
      }
      JSONArray apps=new JSONArray();
      for(String label:seen)apps.put(label);
      int count=apps.length();
      finish(id,"apps","Found "+count+" launchable apps on this device.",json("apps",apps));
    }catch(Exception e){finish(id,"failed","Android did not return the installed app list.");}
  }
  private static String limit(String value,int max,String fallback){
    String trimmed=value==null?"":value.trim();
    if(trimmed.isEmpty())return fallback;
    return trimmed.length()>max?trimmed.substring(0,max):trimmed;
  }
  private void dial(String id,String raw){
    String number=raw==null?"":raw.trim().replaceAll("[\\s()-]","");
    if(number.startsWith("00")) number="+"+number.substring(2);
    if(!number.matches("\\+?[0-9]{7,15}")){finish(id,"invalid","সঠিক ফোন নম্বরটি দেশের কোড-সহ বলুন।");return;}
    try {
      Intent intent=new Intent(Intent.ACTION_DIAL,Uri.fromParts("tel",number,null));
      boolean opened=launcher.open(intent);
      finish(id,opened?"dialer_opened":"unavailable",opened?"ফোনের ডায়ালার খুলেছি। নম্বরটি দেখে Call বোতাম চাপুন।":"এই ডিভাইসে ফোনের ডায়ালার খোলা যাচ্ছে না।");
    } catch(Exception e){finish(id,"failed","ডায়ালার খোলা যায়নি। কোনো কল করা হয়নি।");}
  }
  private void openApp(String id,String input){
    String app=normalize(input);if(app.isEmpty()||app.length()>100){finish(id,"invalid","Say which app to open.");return;}
    Intent intent=null;
    if(Arrays.asList("wp","wa","whats app","whatsapp","व्हाट्सएप").contains(app)){
      String pkg=whatsapp();if(pkg==null){finish(id,"not_installed","WhatsApp is not installed on this Android device.");return;}intent=activity.getPackageManager().getLaunchIntentForPackage(pkg);if(intent==null)intent=new Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/")).setPackage(pkg);
    }else if(Arrays.asList("youtube","you tube","यूट्यूब").contains(app))intent=new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.youtube.com")).setPackage(launcher.installed("com.google.android.youtube")?"com.google.android.youtube":launcher.installed("com.android.chrome")?"com.android.chrome":null);
    else if(Arrays.asList("chrome","google chrome","browser","क्रोम").contains(app))intent=new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com")).setPackage(launcher.installed("com.android.chrome")?"com.android.chrome":null);
    else {
      intent=launcher.app(input);
      if(intent==null)intent=systemApp(app);
    }
    if(intent==null){finish(id,"not_installed","No installed app matches that name. Say the exact app name shown on your home screen.");return;}
    boolean opened=launcher.open(intent);finish(id,opened?"opened":"unavailable",opened?"Opened "+input+".":"Android could not open "+input+".");
  }
  /** Common system apps and launcher categories when no labelled app matches. */
  private Intent systemApp(String app){
    String category=null;
    if(app.equals("phone")||app.equals("dialer")||app.equals("calls"))return new Intent(Intent.ACTION_DIAL);
    if(app.equals("settings")||app.equals("system settings"))return new Intent(Settings.ACTION_SETTINGS);
    if(app.equals("camera"))return new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
    if(app.contains("calendar"))category=Intent.CATEGORY_APP_CALENDAR;
    else if(app.contains("calculator"))category=Intent.CATEGORY_APP_CALCULATOR;
    else if(app.contains("contact"))category=Intent.CATEGORY_APP_CONTACTS;
    else if(app.contains("email")||app.contains("gmail")||app.contains("mail"))category=Intent.CATEGORY_APP_EMAIL;
    else if(app.contains("gallery")||app.contains("photos"))category=Intent.CATEGORY_APP_GALLERY;
    else if(app.contains("map"))category=Intent.CATEGORY_APP_MAPS;
    else if(app.contains("message")||app.contains("sms"))category=Intent.CATEGORY_APP_MESSAGING;
    else if(app.contains("music")||app.contains("song"))category=Intent.CATEGORY_APP_MUSIC;
    else if(app.contains("store")||app.contains("play store"))category=Intent.CATEGORY_APP_MARKET;
    else if(app.contains("file"))category=Intent.CATEGORY_APP_FILES;
    else return null;
    Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(category);
    return activity.getPackageManager().resolveActivity(intent,0)==null?null:intent;
  }
  private String whatsapp(){return launcher.installed("com.whatsapp")?"com.whatsapp":launcher.installed("com.whatsapp.w4b")?"com.whatsapp.w4b":null;}
  private void message(String id,String recipient,String text,boolean sms){
    if(!sms&&whatsapp()==null){finish(id,"not_installed","WhatsApp is not installed on this Android device.");return;}
    if(recipient==null||recipient.trim().isEmpty()||recipient.length()>100||text==null||text.trim().isEmpty()||text.length()>4000){finish(id,"invalid","Tell me the recipient and the complete message.");return;}
    if(recipient.trim().matches("\\+?[0-9 ()-]+")){
      String phone=recipient.replaceAll("[ ()+-]","");if(phone.startsWith("00"))phone=phone.substring(2);boolean international=recipient.trim().startsWith("+")||recipient.trim().startsWith("00")||phone.length()>=11;if(!international||!phone.matches("[1-9][0-9]{7,14}")){finish(id,"invalid","Include the country code in the recipient phone number, for example +91.");return;}
      openDraft(id,new Recipient(recipient,phone),text,sms);return;
    }
    if(contacts.needsPermission()&&activity.checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED){
      if(pending!=null||pendingTorchId!=null){finish(id,"busy","Finish the current permission request first.");return;}
      pendingId=id;pending=()->message(id,recipient,text,sms);activity.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},CONTACTS_REQUEST);return;
    }
    List<Recipient> matches=contacts.find(recipient.trim());
    if(matches.isEmpty()){finish(id,"not_found","No matching contact with a usable phone number was found. Give the full saved name or an international phone number.");return;}
    if(matches.size()==1){openDraft(id,matches.get(0),text,sms);return;}
    if(chooser!=null){finish(id,"busy","Choose the recipient from the current list first.");return;}
    String[] labels=new String[matches.size()];for(int i=0;i<labels.length;i++)labels[i]=matches.get(i).name+" · +"+matches.get(i).phone;
    chooserId=id;chooser=new AlertDialog.Builder(activity).setTitle("Which "+recipient+"?").setItems(labels,(d,index)->{chooser=null;openDraft(id,matches.get(index),text,sms);})
      .setNegativeButton("Cancel",(d,w)->{chooser=null;finish(id,"cancelled",sms?"Message cancelled. Nothing was sent.":"Message cancelled. Nothing was sent.");}).setOnCancelListener(d->{chooser=null;finish(id,"cancelled","Message cancelled. Nothing was sent.");}).show();
  }
  private void openDraft(String id,Recipient person,String text,boolean sms){
    if(sms){
      Intent intent=new Intent(Intent.ACTION_SENDTO,Uri.parse("smsto:+".concat(person.phone))).putExtra("sms_body",text);
      boolean opened=launcher.open(intent);
      finish(id,opened?"sms_draft_opened":"unavailable",opened?"SMS draft opened for "+person.name+". Review the message and tap Send in your messaging app; Vishal Jarvis has not sent it.":"No messaging app could open this SMS draft. Nothing was sent.");
      return;
    }
    boolean opened=launcher.open(draft(person.phone,text,whatsapp()));
    finish(id,opened?"draft_opened":"unavailable",opened?"WhatsApp draft opened for "+person.name+". Review it and tap Send in WhatsApp; Vishal Jarvis has not sent it.":"WhatsApp could not open this recipient. Nothing was sent.");
  }
  public void permissionResult(){Runnable retry=pending;String id=pendingId;pending=null;pendingId=null;if(retry==null)return;if(activity.checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED)retry.run();else finish(id,"permission_required","Contacts access was denied. Allow Contacts permission in Android app settings, then try again.");}
  public void torchPermissionResult(){String id=pendingTorchId;Boolean enabled=pendingTorchState;pendingTorchId=null;pendingTorchState=null;if(id==null||enabled==null)return;if(activity.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)applyFlashlight(id,enabled);else finish(id,"permission_required","Flashlight ব্যবহারের জন্য Camera permission অনুমতি দাও। Android Settings থেকে Vishal Jarvis-এর Camera permission চালু করে আবার চেষ্টা করো।");}
  public void cancel(String id){if(id==null||!id.matches("[A-Za-z0-9-]{1,80}"))return;if(completed.containsKey(id))return;if(id.equals(pendingId)){pending=null;pendingId=null;}if(id.equals(pendingTorchId)){pendingTorchId=null;pendingTorchState=null;}if(id.equals(chooserId)&&chooser!=null){chooser.dismiss();chooser=null;chooserId=null;}finish(id,"cancelled","Action cancelled. Nothing was sent.");}
  public void close(){pending=null;pendingId=null;pendingTorchId=null;pendingTorchState=null;if(chooser!=null){chooser.dismiss();chooser=null;}}
}
