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
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.*;
import android.view.KeyEvent;
import org.json.*;
import java.util.*;

/** Bounded Android intents and direct device actions for the Vishal Jarvis agent.
 *
 *  Direct actions (calls, SMS, third-party chat sends) are permission-gated, always
 *  preceded by the user's explicit instruction, and report only what actually happened.
 *  There is no accessibility access outside MessageAutomationService, no shell, no
 *  arbitrary URL intent and no silent background sending. */
public final class MobileActions {
  public static final int CONTACTS_REQUEST=88;
  public static final int TORCH_REQUEST=89;
  public static final int PHONE_REQUEST=91;
  private static final int MAX_APPS=150;
  private static final long PENDING_TTL=5*60*1000L;
  private static final String SMS_RECEIPT="com.myraa.mobile.SMS_RECEIPT";

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
  private String pendingPhonePermission;
  private String pendingTorchId;
  private Boolean pendingTorchState;
  private AlertDialog chooser;
  private String chooserId;
  private JSONObject pendingSend;
  private final Map<String,JSONObject> completed=new LinkedHashMap<>();
  private boolean permissionsGranted=false;
  private final String smsToken=UUID.randomUUID().toString();
  private boolean smsReceiverRegistered;
  private final BroadcastReceiver smsReceiver=new BroadcastReceiver(){
    @Override public void onReceive(Context context,Intent intent){
      if(intent==null||!SMS_RECEIPT.equals(intent.getAction()))return;
      if(!smsToken.equals(intent.getStringExtra("token")))return;
      String id=intent.getStringExtra("id"),to=intent.getStringExtra("to");
      if(id==null||to==null)return;
      boolean ok=getResultCode()==Activity.RESULT_OK;
      result.complete(id,status(ok?"sms_delivered_to_network":"sms_failed",ok
        ?"SMS accepted by the network for +"+to+"."
        :"Android could not send the SMS (code "+getResultCode()+"). Nothing was sent.",null));
    }
  };

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
    try {
      IntentFilter filter=new IntentFilter(SMS_RECEIPT);
      if(Build.VERSION.SDK_INT>=33)activity.registerReceiver(smsReceiver,filter,Context.RECEIVER_EXPORTED);
      else activity.registerReceiver(smsReceiver,filter);
      smsReceiverRegistered=true;
    } catch(RuntimeException ignored){smsReceiverRegistered=false;}
  }
  /** Test seam: injected fakes run with permissions treated as granted. */
  public MobileActions(Activity activity,Result result,Launcher launcher,Contacts contacts){this.activity=activity;this.result=result;this.launcher=launcher;this.contacts=contacts;this.permissionsGranted=true;}
  public static String normalize(String input){return input==null?"":input.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");}
  private static String international(String raw,Activity activity){
    if(raw==null)return null;String clean=raw.replaceAll("[ ()-]","");if(clean.startsWith("00"))clean="+"+clean.substring(2);
    if(clean.matches("\\+[1-9][0-9]{7,14}"))return clean.substring(1);
    String country="";try{country=activity.getSystemService(TelephonyManager.class).getNetworkCountryIso();}catch(Exception ignored){}
    if(country==null||country.length()!=2){String language=activity.getSharedPreferences("myraa.mobile.v1",0).getString("language","bn-IN");country=language.endsWith("IN")?"IN":"US";}
    String e164=PhoneNumberUtils.formatNumberToE164(raw,country.toUpperCase(Locale.ROOT));return e164==null?null:e164.substring(1);
  }
  /** Digits only, no plus sign, using the device's country for local numbers. */
  private static String numberDigits(String raw,Activity activity){
    if(raw==null)return null;
    String clean=raw.trim().replaceAll("[^0-9+]","");
    if(clean.isEmpty())return null;
    if(clean.startsWith("00"))clean="+"+clean.substring(2);
    if(clean.matches("\\+[1-9][0-9]{7,14}"))return clean.substring(1);
    if(clean.length()>=11&&clean.matches("[1-9][0-9]{7,14}"))return clean;
    String e164=international(raw,activity);
    return e164;
  }
  public static Intent search(String target,String query){
    if(query==null||query.trim().isEmpty()||query.length()>1000)throw new IllegalArgumentException("Say what you want to search for.");
    Uri uri="youtube".equals(target)?Uri.parse("https://www.youtube.com/results").buildUpon().appendQueryParameter("search_query",query.trim()).build():Uri.parse("https://www.google.com/search").buildUpon().appendQueryParameter("q",query.trim()).build();
    return new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE);
  }
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
  private static JSONObject status(String status,String message,JSONObject data){
    JSONObject value=new JSONObject();
    try{value.put("status",status).put("message",message);if(data!=null)value.put("data",data);}catch(JSONException ignored){}
    return value;
  }
  private void finish(String id,String status,String message){finish(id,status,message,null);}
  private void finish(String id,String status,String message,JSONObject data){publish(id,status(status,message,data));}
  private void publish(String id,JSONObject value){
    completed.put(id,value);while(completed.size()>64)completed.remove(completed.keySet().iterator().next());result.complete(id,value);
  }
  private boolean granted(String permission){return permissionsGranted||activity.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;}
  private void requestCallPermission(String id,Runnable retry){
    if(pending!=null||pendingTorchId!=null){finish(id,"busy","Finish the current permission request first.");return;}
    pendingId=id;pending=retry;pendingPhonePermission=Manifest.permission.CALL_PHONE;
    activity.requestPermissions(new String[]{Manifest.permission.CALL_PHONE},PHONE_REQUEST);
  }
  private void requestSmsPermission(String id,Runnable retry){
    if(pending!=null||pendingTorchId!=null){finish(id,"busy","Finish the current permission request first.");return;}
    pendingId=id;pending=retry;pendingPhonePermission=Manifest.permission.SEND_SMS;
    activity.requestPermissions(new String[]{Manifest.permission.SEND_SMS},PHONE_REQUEST);
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
      if("call".equals(action)){call(id,args.optString("recipient"),args.optString("sim"));return;}
      if("send_message".equals(action)){sendMessage(id,args);return;}
      if("cancel_send".equals(action)){cancelSend(id);return;}
      if("sim_info".equals(action)){simInfo(id);return;}
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
      finish(id,"unsupported","Supported actions: open apps, open a web page, search YouTube/web, call a number or saved contact (direct call, optional SIM), send WhatsApp/Telegram/Instagram/Messenger/SMS messages after confirmation, flashlight, Wi-Fi and system panels, alarms, timers, calendar events, navigation, share, copy, media and volume control, device status, SIM info and installed apps.");
    }catch(IllegalArgumentException e){finish(id,"invalid",e.getMessage());}catch(Exception e){finish(id,"failed","Android could not complete this action. Nothing was sent.");}
  }

  // ---------------------------------------------------------------- direct calls

  private void call(String id,String recipient,String sim){
    String value=recipient==null?"":recipient.trim();
    if(value.isEmpty()||value.length()>100){finish(id,"invalid","Whom should I call? Give a saved name or a phone number.");return;}
    String digits=numberDigits(value,activity);
    if(digits!=null){placeCall(id,digits,value,sim);return;}
    if(contacts.needsPermission()&&!granted(Manifest.permission.READ_CONTACTS)){
      if(pending!=null||pendingTorchId!=null){finish(id,"busy","Finish the current permission request first.");return;}
      pendingId=id;pending=()->call(id,recipient,sim);
      activity.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},CONTACTS_REQUEST);return;
    }
    List<Recipient> matches=contacts.find(value);
    if(matches.isEmpty()){finish(id,"not_found","No contact with a phone number matches “"+value+"”. Say the full saved name or the number itself.");return;}
    if(matches.size()==1){placeCall(id,matches.get(0).phone,matches.get(0).name,sim);return;}
    if(chooser!=null){finish(id,"busy","Choose the contact from the current list first.");return;}
    String[] labels=new String[matches.size()];for(int i=0;i<labels.length;i++)labels[i]=matches.get(i).name+" · +"+matches.get(i).phone;
    chooserId=id;chooser=new AlertDialog.Builder(activity).setTitle("কাকে কল করবে — "+value+"?").setItems(labels,(d,index)->{chooser=null;placeCall(id,matches.get(index).phone,matches.get(index).name,sim);})
      .setNegativeButton("বাতিল",(d,w)->{chooser=null;finish(id,"cancelled","Call cancelled. Nobody was called.");})
      .setOnCancelListener(d->{chooser=null;finish(id,"cancelled","Call cancelled. Nobody was called.");}).show();
  }

  private void placeCall(String id,String digits,String label,String sim){
    if(!granted(Manifest.permission.CALL_PHONE)){requestCallPermission(id,()->placeCall(id,digits,label,sim));return;}
    List<PhoneAccountHandle> accounts=callAccounts();
    PhoneAccountHandle handle=null;
    int choice=simIndex(sim);
    if(choice>0 && (accounts.isEmpty() || choice>accounts.size())){
      finish(id,"unavailable","I cannot select that SIM. Phone-state access or that account is unavailable. Use the phone’s default call account or choose it in the system dialer.");return;
    }
    if(accounts.size()>1){
      if(choice<1){
        finish(id,"needs_sim","This phone has "+accounts.size()+" SIMs: "+simLabels(accounts)+". Which SIM should I call from?",json("sims",simArray(accounts)));
        return;
      }
      handle=accounts.get(Math.min(choice,accounts.size())-1);
    }else if(accounts.size()==1){
      handle=accounts.get(0);
    }
    try{
      Intent intent=new Intent(Intent.ACTION_CALL,Uri.fromParts("tel","+"+digits,null));
      if(handle!=null)intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,handle);
      boolean placed=launcher.open(intent);
      if(placed){
        String simNote=accounts.size()>1&&choice>0?" on SIM "+choice:"";
        finish(id,"call_started","Calling "+label+simNote+". The call is being placed now.");
      }else{
        finish(id,"failed","Android did not start the call. Check the Phone permission and that a dialer app exists.");
      }
    }catch(SecurityException e){
      finish(id,"permission_required","Allow the Phone permission for Vishal Jarvis so it can place calls directly.");
    }catch(RuntimeException e){
      finish(id,"failed","Android did not start the call. Nothing was dialled.");
    }
  }

  private int simIndex(String sim){
    String value=normalize(sim);
    if(value.isEmpty()||value.equals("default")||value.equals("any")||value.equals("auto"))return -1;
    String digits=value.replaceAll("[^0-9]","");
    if(digits.isEmpty())return -1;
    try{return Integer.parseInt(digits);}catch(NumberFormatException e){return -1;}
  }

  private List<PhoneAccountHandle> callAccounts(){
    List<PhoneAccountHandle> accounts=new ArrayList<>();
    if(!granted(Manifest.permission.READ_PHONE_STATE))return accounts;
    try{
      TelecomManager telecom=(TelecomManager)activity.getSystemService(Context.TELECOM_SERVICE);
      if(telecom==null)return accounts;
      List<PhoneAccountHandle> found=telecom.getCallCapablePhoneAccounts();
      if(found!=null)accounts.addAll(found);
    }catch(RuntimeException ignored){}
    return accounts;
  }

  private String simLabels(List<PhoneAccountHandle> accounts){
    StringBuilder text=new StringBuilder();
    for(int i=0;i<accounts.size();i++){
      if(i>0)text.append(", ");
      text.append(i+1).append(") ").append(simLabel(accounts.get(i)));
    }
    return text.toString();
  }

  private String simLabel(PhoneAccountHandle handle){
    try{
      TelecomManager telecom=(TelecomManager)activity.getSystemService(Context.TELECOM_SERVICE);
      PhoneAccount account=telecom.getPhoneAccount(handle);
      if(account!=null){
        CharSequence label=account.getLabel();
        if(label!=null&&label.length()>0)return label.toString();
      }
    }catch(RuntimeException ignored){}
    return "SIM "+(handle.getId()==null?"":handle.getId());
  }

  private JSONArray simArray(List<PhoneAccountHandle> accounts){
    JSONArray array=new JSONArray();
    for(int i=0;i<accounts.size();i++)array.put(json("sim",i+1,"label",simLabel(accounts.get(i))));
    return array;
  }

  private void simInfo(String id){
    if(!granted(Manifest.permission.READ_PHONE_STATE)){
      finish(id,"unavailable","SIM details are unavailable without phone-state access. No SIM access request was made.");return;
    }
    List<PhoneAccountHandle> accounts=callAccounts();
    if(accounts.isEmpty()){finish(id,"info","This phone reports no SIM accounts.",json("sims",new JSONArray()));return;}
    finish(id,"info","This phone has "+accounts.size()+" SIM"+(accounts.size()==1?"":"s")+": "+simLabels(accounts)+".",json("sims",simArray(accounts)));
  }

  // ---------------------------------------------------------------- sending

  private void sendMessage(String id,JSONObject args){
    String app=normalize(args.optString("app","whatsapp"));
    if(app.isEmpty())app="whatsapp";
    String text=args.optString("text","").trim();
    if(text.isEmpty()||text.length()>4000){finish(id,"invalid","Tell me the complete message to send.");return;}
    if(args.optBoolean("confirm",false)){confirmSend(id);return;}
    String recipient=args.optString("recipient","").trim();
    if(recipient.isEmpty()||recipient.length()>100){finish(id,"invalid","Whom should I message? Give a saved name, a phone number or the app username.");return;}
    prepareMessage(id,app,recipient,text,args.optString("sim"));
  }

  private void prepareMessage(String id,String app,String recipient,String text,String sim){
    if(app.equals("sms")){
      resolveTarget(id,recipient,new Target(){
        public void use(String label,String key,String digits){
          if(digits==null){finish(id,"not_found","That contact has no usable phone number.");return;}
          storePending(id,app,label,key,digits,text,sim);
        }
      });
      return;
    }
    if(app.equals("whatsapp")){
      resolveTarget(id,recipient,new Target(){
        public void use(String label,String key,String digits){
          if(digits==null){finish(id,"not_found","That contact has no usable phone number. Give the full number with country code.");return;}
          storePending(id,app,label,"+"+digits,digits,text,sim);
        }
      });
      return;
    }
    String handle=recipient.replaceAll("^@","").trim();
    if(!handle.matches("[A-Za-z0-9._]{2,32}")){
      finish(id,"invalid","For "+appLabel(app)+" I need the exact username (for example @name), not a phone number.");
      return;
    }
    storePending(id,app,"@"+handle,handle,null,text,sim);
  }

  private interface Target { void use(String label,String key,String digits); }

  /** Accepts a phone number or resolves a saved contact name, asking when ambiguous. */
  private void resolveTarget(String id,String recipient,Target target){
    String digits=numberDigits(recipient,activity);
    if(digits!=null){target.use(recipient,recipient,digits);return;}
    if(contacts.needsPermission()&&!granted(Manifest.permission.READ_CONTACTS)){
      if(pending!=null||pendingTorchId!=null){finish(id,"busy","Finish the current permission request first.");return;}
      pendingId=id;pending=()->resolveTarget(id,recipient,target);
      activity.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},CONTACTS_REQUEST);return;
    }
    List<Recipient> matches=contacts.find(recipient);
    if(matches.isEmpty()){finish(id,"not_found","No contact with a phone number matches “"+recipient+"”. Give the full number with country code.");return;}
    if(matches.size()==1){target.use(matches.get(0).name,matches.get(0).name,matches.get(0).phone);return;}
    if(chooser!=null){finish(id,"busy","Choose the recipient from the current list first.");return;}
    String[] labels=new String[matches.size()];for(int i=0;i<labels.length;i++)labels[i]=matches.get(i).name+" · +"+matches.get(i).phone;
    chooserId=id;chooser=new AlertDialog.Builder(activity).setTitle("Which "+recipient+"?").setItems(labels,(d,index)->{chooser=null;target.use(matches.get(index).name,matches.get(index).name,matches.get(index).phone);})
      .setNegativeButton("Cancel",(d,w)->{chooser=null;finish(id,"cancelled","Cancelled. Nothing was sent.");})
      .setOnCancelListener(d->{chooser=null;finish(id,"cancelled","Cancelled. Nothing was sent.");}).show();
  }

  private void storePending(String id,String app,String label,String key,String digits,String text,String sim){
    pendingSend=json("app",app,"label",label,"key",key,"digits",digits==null?"":digits,"text",text,"sim",sim==null?"":sim,"at",System.currentTimeMillis());
    String preview=text.length()>120?text.substring(0,120)+"…":text;
    finish(id,"needs_confirmation","Ready to send to "+label+" on "+appLabel(app)+": “"+preview+"”. Say “send” to confirm, or “cancel”.",
      json("pending",json("app",app,"label",label,"text",text)));
  }

  private void confirmSend(String id){
    JSONObject pendingMessage=pendingSend;
    if(pendingMessage==null){finish(id,"nothing_pending","There is no prepared message. Tell me whom to message and what to say first.");return;}
    if(System.currentTimeMillis()-pendingMessage.optLong("at",0)>PENDING_TTL){
      pendingSend=null;finish(id,"expired","That prepared message expired. Tell me the message again.");return;
    }
    String app=pendingMessage.optString("app"),label=pendingMessage.optString("label");
    String text=pendingMessage.optString("text"),sim=pendingMessage.optString("sim");
    String digits=pendingMessage.optString("digits"),key=pendingMessage.optString("key");
    if(app.equals("sms")){pendingSend=null;sendSms(id,digits,label,text,sim);return;}
    if(app.equals("whatsapp")){
      String pkg=whatsapp();
      if(pkg==null){finish(id,"not_installed","WhatsApp is not installed on this phone.");return;}
      dispatchChat(id,app,pkg,label,"whatsapp://send?phone="+digits,text);return;
    }
    dispatchChat(id,app,chatPackage(app),label,chatDeepLink(app,key),text);
  }

  private String appLabel(String app){
    if(app.equals("sms"))return "SMS";
    if(app.equals("whatsapp"))return "WhatsApp";
    if(app.equals("telegram"))return "Telegram";
    if(app.equals("instagram"))return "Instagram";
    if(app.equals("messenger"))return "Messenger";
    return app;
  }

  private String chatPackage(String app){
    if(app.equals("telegram"))return launcher.installed("org.telegram.messenger")?"org.telegram.messenger":"org.telegram.plus";
    if(app.equals("instagram"))return "com.instagram.android";
    if(app.equals("messenger"))return "com.facebook.orca";
    return "";
  }

  private String chatDeepLink(String app,String handle){
    if(app.equals("telegram"))return "tg://resolve?domain="+Uri.encode(handle);
    if(app.equals("instagram"))return "https://ig.me/m/"+Uri.encode(handle);
    if(app.equals("messenger"))return "https://m.me/"+Uri.encode(handle);
    return null;
  }

  private void dispatchChat(String id,String app,String pkg,String label,String deepLink,String text){
    if(pkg==null||pkg.isEmpty()||!launcher.installed(pkg)){finish(id,"not_installed",appLabel(app)+" is not installed on this phone.");return;}
    MessageAutomationService service=MessageAutomationService.get();
    if(service==null){
      finish(id,"permission_required","To send directly in "+appLabel(app)+" I need the “Vishal Jarvis message sender” accessibility service. Enable it in Android Settings → Accessibility (Android may ask you to allow restricted settings first), then say “send” again.",json("needsAccessibility",true));
      return;
    }
    final String appName=appLabel(app);
    service.send(pkg,deepLink,label,text,(status,message)->{
      if("sent".equals(status)){
        pendingSend=null;
        finish(id,"sent","Sent to "+label+" on "+appName+".");
      }else if("sent_unverified".equals(status)){
        pendingSend=null;
        finish(id,"sent_unverified",message);
      }else if("cancelled".equals(status)||"timeout".equals(status)||"busy".equals(status)){
        finish(id,status,message);
      }else{
        finish(id,"failed",message);
      }
    });
  }

  private void sendSms(String id,String digits,String label,String text,String sim){
    if(!granted(Manifest.permission.SEND_SMS)){requestSmsPermission(id,()->sendSms(id,digits,label,text,sim));return;}
    try{
      SmsManager manager=SmsManager.getDefault();
      int subscription=subscriptionForSim(sim);
      if(simIndex(sim)>0 && subscription<0){
        finish(id,"unavailable","Cannot select that SIM for SMS without phone-state access. Nothing was sent.");return;
      }
      if(subscription>=0){
        SmsManager picked=activity.getSystemService(SmsManager.class);
        if(picked!=null&&Build.VERSION.SDK_INT>=31)manager=picked.createForSubscriptionId(subscription);
        else if(Build.VERSION.SDK_INT>=22)manager=SmsManager.getSmsManagerForSubscriptionId(subscription);
      }
      Intent receipt=new Intent(SMS_RECEIPT).setPackage(activity.getPackageName())
        .putExtra("token",smsToken).putExtra("id",id).putExtra("to",digits);
      PendingIntent sent=PendingIntent.getBroadcast(activity,Math.abs((id+"sms").hashCode()),receipt,
        PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
      ArrayList<String> parts=manager.divideMessage(text);
      if(parts.size()>1){
        ArrayList<PendingIntent> receipts=new ArrayList<>();
        for(int i=0;i<parts.size();i++)receipts.add(sent);
        manager.sendMultipartTextMessage("+"+digits,null,parts,receipts,null);
      }else{
        manager.sendTextMessage("+"+digits,null,text,sent,null);
      }
      String simNote=simIndex(sim)>0?" on SIM "+simIndex(sim):"";
      finish(id,"sms_queued","SMS to "+label+simNote+" handed to Android to send.");
    }catch(SecurityException e){
      finish(id,"permission_required","Allow the SMS permission for Vishal Jarvis so it can send messages.");
    }catch(RuntimeException e){
      finish(id,"failed","Android refused the SMS. Nothing was sent.");
    }
  }

  private int subscriptionForSim(String sim){
    int index=simIndex(sim);
    if(index<1||!granted(Manifest.permission.READ_PHONE_STATE))return -1;
    try{
      SubscriptionManager manager=(SubscriptionManager)activity.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
      if(manager==null)return -1;
      List<SubscriptionInfo> active=manager.getActiveSubscriptionInfoList();
      if(active==null||active.size()<index)return -1;
      return active.get(index-1).getSubscriptionId();
    }catch(RuntimeException ignored){return -1;}
  }

  private void cancelSend(String id){
    boolean had=pendingSend!=null;
    pendingSend=null;
    if(MessageAutomationService.get()!=null)MessageAutomationService.get().cancel("Sending cancelled.");
    finish(id,had?"cancelled":"nothing_pending",had?"Cancelled. Nothing was sent.":"There was nothing waiting to send.");
  }
  public void cancelPendingSend(){pendingSend=null;}

  // ---------------------------------------------------------------- other actions

  private void openUrl(String id,String url){
    Intent intent=web(url);boolean opened=launcher.open(intent);
    finish(id,opened?"opened":"unavailable",opened?"Opened the link in your browser.":"No browser could open that link.");
  }
  private void openCamera(String id){
    boolean opened=launcher.open(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
    finish(id,opened?"opened":"unavailable",opened?"Camera opened.":"This device has no camera app available.");
  }
  private void setFlashlight(String id,boolean enabled){
    if(!granted(Manifest.permission.CAMERA)){
      if(pending!=null||pendingTorchId!=null){finish(id,"busy","Finish the current permission request first.");return;}
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
    Intent intent;
    if(panel.equals("bluetooth"))intent=new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
    else if(panel.equals("sound"))intent=new Intent(Settings.ACTION_SOUND_SETTINGS);
    else if(panel.equals("display"))intent=new Intent(Settings.ACTION_DISPLAY_SETTINGS);
    else if(panel.equals("battery"))intent=new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS);
    else if(panel.equals("location"))intent=new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
    else if(panel.equals("apps"))intent=new Intent(Settings.ACTION_APPLICATION_SETTINGS);
    else if(panel.equals("notifications"))intent=new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,activity.getPackageName());
    else if(panel.equals("data"))intent=new Intent(Settings.ACTION_DATA_USAGE_SETTINGS);
    else if(panel.equals("airplane"))intent=new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS);
    else if(panel.equals("accessibility"))intent=new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    else if(panel.equals("nfc"))intent=new Intent(Settings.ACTION_NFC_SETTINGS);
    else if(panel.equals("wifi")){openWifiSettings(id);return;}
    else if(panel.equals("home"))intent=new Intent(Settings.ACTION_HOME_SETTINGS);
    else intent=new Intent(Settings.ACTION_SETTINGS);
    boolean opened=launcher.open(intent);
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
      int state=battery==null?0:battery.getIntExtra(BatteryManager.EXTRA_STATUS,BatteryManager.BATTERY_STATUS_UNKNOWN);
      int percent=level>=0&&scale>0?(int)Math.round(level*100.0/scale):-1;
      data.put("batteryPercent",percent);
      data.put("charging",state==BatteryManager.BATTERY_STATUS_CHARGING||state==BatteryManager.BATTERY_STATUS_FULL);
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
      data.put("sims",callAccounts().size());
      data.put("device",Build.MANUFACTURER+" "+Build.MODEL);
      data.put("android",Build.VERSION.RELEASE);
      data.put("sdk",Build.VERSION.SDK_INT);
      data.put("appVersion","1.3.0");
      StringBuilder summary=new StringBuilder();
      summary.append("Battery ").append(percent<0?"unknown":percent+"%").append(data.optBoolean("charging")?", charging":"").append(". ");
      summary.append("Network: ").append(network).append(". ");
      summary.append("Free storage: ").append(data.opt("freeStorageMb")).append(" MB. ");
      summary.append("Media volume: ").append(data.opt("mediaVolumePercent")).append("%. ");
      summary.append("SIMs: ").append(callAccounts().size()).append(". ");
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

  // ---------------------------------------------------------------- permissions / lifecycle

  public void permissionResult(){
    Runnable retry=pending;String id=pendingId;pending=null;pendingId=null;if(retry==null)return;
    if(granted(Manifest.permission.READ_CONTACTS))retry.run();
    else finish(id,"permission_required","Contacts access was denied. Allow Contacts permission in Android app settings, then try again.");
  }
  public void phonePermissionResult(){
    Runnable retry=pending;String id=pendingId;String permission=pendingPhonePermission;pending=null;pendingId=null;pendingPhonePermission=null;if(retry==null)return;
    if(permission!=null && granted(permission))retry.run();
    else finish(id,"permission_required","The requested Phone or SMS permission was not granted. Nothing was sent or called.");
  }
  public void torchPermissionResult(){
    String id=pendingTorchId;Boolean enabled=pendingTorchState;pendingTorchId=null;pendingTorchState=null;
    if(id==null||enabled==null)return;
    if(granted(Manifest.permission.CAMERA))applyFlashlight(id,enabled);
    else finish(id,"permission_required","Flashlight ব্যবহারের জন্য Camera permission অনুমতি দাও।");
  }
  public void cancel(String id){
    if(id==null||!id.matches("[A-Za-z0-9-]{1,80}"))return;
    if(completed.containsKey(id))return;
    if(id.equals(pendingId)){pending=null;pendingId=null;pendingPhonePermission=null;}
    if(id.equals(pendingTorchId)){pendingTorchId=null;pendingTorchState=null;}
    if(id.equals(chooserId)&&chooser!=null){chooser.dismiss();chooser=null;chooserId=null;}
    finish(id,"cancelled","Action cancelled. Nothing was sent.");
  }
  public void close(){
    pending=null;pendingId=null;pendingPhonePermission=null;pendingTorchId=null;pendingTorchState=null;
    if(chooser!=null){chooser.dismiss();chooser=null;}
    if(MessageAutomationService.get()!=null)MessageAutomationService.get().cancel("App closed. Nothing was sent.");
    if(smsReceiverRegistered){try{activity.unregisterReceiver(smsReceiver);}catch(RuntimeException ignored){}smsReceiverRegistered=false;}
  }
}
