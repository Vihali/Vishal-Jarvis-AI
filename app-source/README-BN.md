# Vishal Jarvis — Android App Source (v1.2.0)

এই ZIP-এ Android-এ চালানোর জন্য Vishal Jarvis-এর সম্পূর্ণ source code রয়েছে। v1.2.0-এ অ্যাপটি শুধু "chat + voice" থেকে একটি সত্যিকারের **Android AI agent**-এ উন্নীত করা হয়েছে: লেখা চ্যাটেও এখন ফোনের কাজ করতে পারে, উত্তর stream হয়, দীর্ঘমেয়াদি memory থাকে এবং অ্যাকশনের সংখ্যা অনেক বেড়েছে।

## v1.2.0-এ নতুন কী

### এজেন্ট এখন লেখা চ্যাটেও কাজ করে
- আগে শুধু Live voice-এ tool call ছিল; এখন **text chat-ও** Gemini function calling দিয়ে ফোনের কাজ করে (open app, search, message draft, alarm, timer, volume, ইত্যাদি)।
- উত্তর **stream** হয়ে আসে — প্রথম শব্দ থেকেই দেখা যায়, পুরো উত্তরের জন্য অপেক্ষা করতে হয় না।
- প্রতিটি ধাপ UI-তে দেখায়: "Opening WhatsApp", "Setting an alarm for 07:30" ইত্যাদি, সাথে সফল/ব্যর্থের সত্যি ফলাফল।
- একসাথে কয়েকটি কাজ চেইন করা যায় (সর্বোচ্চ ৪ ধাপ), এবং প্রতি ধাপে আসল ফলাফল না পেলে এজেন্ট সফলতার দাবি করে না।

### নতুন Android অ্যাকশন
| অ্যাকশন | কী করে | নিরাপত্তা |
| --- | --- | --- |
| `open_app` | ইনস্টল করা যেকোনো অ্যাপের নাম ধরে খোলে | শুধু লঞ্চার activity |
| `open_url` | https লিংক ব্রাউজারে খোলে | শুধু http(s), credential/localhost ব্লক |
| `search_youtube` / `search_web` | YouTube বা ব্রাউজার search | query encode করা |
| `whatsapp_message` / `send_sms` | message **draft** খোলে | ইউজার নিজে Send চাপে |
| `call_number` / `call_contact` | dialer খোলে (নাম local contacts থেকে) | কল নিজে করে না |
| `set_flashlight` | টর্চ on/off | Camera permission |
| `open_wifi_settings` / `open_settings_panel` | Wi-Fi, Bluetooth, sound, display, battery, location, apps, notifications, data, airplane, accessibility, NFC, home | Android-এর নিয়মে সুইচ ইউজার নিজে চাপে |
| `set_alarm` / `set_timer` | clock app-এর editor খোলে | ইউজার confirm করে |
| `create_event` | calendar editor খোলে | কিছুই নিজে সেভ করে না |
| `navigate_to` | Maps-এ গন্তব্য খোলে | navigation ইউজার শুরু করে |
| `share_text` / `copy_text` | share sheet বা clipboard | ইউজার অ্যাপ বেছে নেয় |
| `media_control` / `set_volume` | play/pause/next, media ভলিউম | শুধু media stream |
| `device_status` | ব্যাটারি, নেটওয়ার্ক, storage, ভলিউম, মডেল | শুধু পড়ে |
| `list_apps` | ইনস্টল করা অ্যাপের নাম | শুধু নিজের ডিভাইসে |
| `open_camera` | ক্যামেরা খোলে | ছবি নিজে তোলে না |

### দীর্ঘমেয়াদি memory
- "মনে রাখো আমি বাংলায় উত্তর চাই" বললে এজেন্ট `remember_fact` tool দিয়ে ছোট fact সেভ করে (সর্বোচ্চ ৩০টি, ২৪০ অক্ষর)।
- Settings → Memory-তে প্রতিটি fact দেখা, মুছে ফেলা বা হাতে যোগ করা যায়।
- পাসওয়ার্ড, OTP, API key, কার্ড নম্বর ইত্যাদি সেভ করতে নেটিভ স্তরেই refusal হয়।

### UX উন্নতি
- Quick-action chips: Torch, Wi-Fi, WhatsApp, Battery, Alarm, Camera, Search
- প্রতিটি উত্তরে **Copy / Read / Retry** বাটন; মেসেজে সময়
- কথোপকথনে **search**, সম্পূর্ণ history toggle, transcript **copy/share**
- **Permission manager**: কোন permission আছে, এক ট্যাপে চাওয়া
- **Theme** (Dark/Light/System) এবং **Reduce motion**
- Offline banner, haptic feedback, বড় composer, ছোট স্ক্রিনের জন্য আলাদা layout
- Key না থাকলে onboardিং কার্ড, সম্ভাব্য কাজের suggestion

### ভেতরের উন্নতি
- Native `MobileTools.java` — text chat ও Live voice একই tool schema ব্যবহার করে
- Streaming SSE parser, tool loop, cancellation, timeout এবং duplicate-action protection
- History ৪০ turn context-এ সীমিত, reply এসে history সেভ না হলে স্পষ্ট error
- `npm run typecheck` যোগ করা হয়েছে; Vite build আগের মতোই

## আগের ফিচার যা অপরিবর্তিত আছে
Gemini text chat, Gemini Live voice, Android TTS, encrypted API key (Android Keystore), conversation history, settings, floating voice controls, notification controls, background Live call, WhatsApp draft, contact lookup, dialer, torch, Wi-Fi panel।

## APK বানানো

দরকার: Node.js 22, JDK 21, Android SDK Platform 35, Build Tools 35.0.0, PowerShell 7 (Windows)।

```powershell
npm install
npm run check          # TypeScript typecheck + Vite asset build
pwsh -NoProfile -File scripts/build-android.ps1 -SdkRoot 'C:\path\to\android-sdk' -JavaRoot 'C:\path\to\jdk-21'
```

APK: `artifacts/android/Vishal-Jarvis-1.2.0-private.apk`

ইনস্টল:

```powershell
adb install -r artifacts/android/Vishal-Jarvis-1.2.0-private.apk
```

## ফোনে চালানোর শর্ত
- Android 8.0 / API 26 বা নতুন, Android System WebView 100+, OpenGL ES 3
- internet + নিজের Gemini API key (Settings-এ দিতে হবে; কোডে নেই)
- প্রয়োজন অনুযায়ী microphone, contacts, camera, notification এবং floating-control permission

## সীমাবদ্ধতা (সৎভাবে)
এই ZIP source package, APK নয়। এই আপডেটে TypeScript typecheck এবং Vite build সফলভাবে চালানো হয়েছে, কিন্তু Android SDK/ডিভাইস এই পরিবেশে নেই — তাই **APK build বা physical-device test এখানে করা হয়নি**। নিজের ফোনে build করে startup, 3D character, text chat + tool call, Live voice, প্রতিটি permission flow এবং background/battery আচরণ যাচাই করে নিন।

এজেন্ট এখনো এগুলো পারে না (ইচ্ছাকৃতভাবে): notification পড়া, screenshot/screen capture, অন্য অ্যাপের UI চালানো, ফাইল পড়া/লেখা, নীরবে message পাঠানো বা কল করা, Wi-Fi/Bluetooth নীরবে বন্ধ-চালু করা। Android package ID `com.myraa.mobile` আগের compatibility-র জন্য অপরিবর্তিত।
