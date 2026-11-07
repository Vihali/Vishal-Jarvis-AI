# Android আপডেট — কাজের বর্তমান অবস্থা

মূল সোর্সটি `Vishal-Jarvis-Android-App-Source.zip`-এ আছে; workflow সেই ZIP থেকে APK তৈরি করে।

- কল করার ও ডায়ালার খোলার অ্যাকশন সরানো হয়েছে; SIM/network country lookup সরানো হয়েছে। WhatsApp-এর জন্য Contacts ও phone-number formatting আগের মতো আছে; এটি কল বা SIM access নয়।
- বিদ্যমান অ্যাপ লঞ্চ, টর্চ, Gemini Live, WhatsApp draft এবং কথোপকথন হিস্ট্রি রাখা হয়েছে। Gemini Live tool-এ ব্যাটারি/চার্জ, স্টোরেজ ও validated internet status, media/system volume, Bluetooth settings ও alarm intent যুক্ত হয়েছে। Android-এর নিয়মে Wi-Fi/Bluetooth settings এবং alarm-এ ব্যবহারকারীর নিশ্চিতকরণ প্রয়োজন হতে পারে।
- CI-তে mobile command ও manifest checks এবং Android 35 emulator-এ APK install করে smoke, authentication/Live fixture ও Android action instrumentation tests চালানো হয়। Gemini Live fixture Google API কল করে না। বাস্তব ফোন বা আসল API key দিয়ে পরীক্ষা করা হয়নি।
- **এখনও বাস্তবায়িত নয়:** screen-off wake word, hands-free wake-up, boot থেকে microphone চালানো, lock-screen wake, নতুন persistent memory system, camera vision, লাইভ web/weather, এবং Gemini/Groq/NVIDIA fallback। Gemini Live-এর বিদ্যমান voice session wake-word listener নয়। লোকাল হিস্ট্রি সর্বশেষ ১০০ turn রাখে; এটি পূর্ণ দীর্ঘমেয়াদি মেমোরি নয়।
- Android-এর microphone foreground service, background activity launch, boot ও secure keyguard সংক্রান্ত বিধিনিষেধ আছে। battery optimization বাদ দিলেও নিরবচ্ছিন্ন execution বা নিরাপদ lock bypass নিশ্চিত হয় না। এ কারণে battery exemption বা অন্য intrusive permission ব্যবহার করে এই সীমা পাশ কাটানোর দাবি করা হয়নি।
- Build workflow প্রতিবার স্থানীয় debug signing key তৈরি করে। আগের APK ভিন্ন signing key-তে signed হলে সেটির ওপর সরাসরি update install হবে না; uninstall করলে পুরোনো local data মুছে যেতে পারে। নিজের release key ব্যবহার করে ধারাবাহিক signing করা উচিত।

## বিল্ড ও ইনস্টলেশন

- এখানে Gradle configuration বা wrapper নেই; মূল Windows PowerShell build-ই Vite, javac, D8, AAPT2, zipalign ও apksigner চালায়। `workflow_dispatch`-এ GitHub Actions থেকে বিল্ড ও emulator tests চালানো যায়।
- `artifacts/Vishal-Jarvis-debug.apk` হলো Android 35 emulator-এ পরীক্ষিত build। Package ID `com.myraa.mobile`, minimum Android 8.0 (API 26), OpenGL ES 3 ও আধুনিক Android System WebView প্রয়োজন।
- ডাউনলোড করে ফোনে APK খুলুন; Android চাইলে ওই উৎস থেকে app install করার অনুমতি দিন। আগের অ্যাপের signing key আলাদা হলে এটি update হিসেবে বসবে না; আগে uninstall করলে app-এর স্থানীয় ডেটা মুছে যেতে পারে।
- Gemini chat/Live-এর জন্য Settings-এ নিজস্ব Gemini API key ও সমর্থিত model access এবং internet দরকার। Live voice-এর জন্য microphone permission, notification control-এর জন্য Android 13+ notification permission, torch-এর জন্য camera permission, WhatsApp contact খোঁজার জন্য contacts permission এবং floating control-এর জন্য overlay permission প্রয়োজন হতে পারে।
- Debug key প্রতিটি CI build-এ নতুন করে তৈরি হয়; ধারাবাহিক upgrade বা release distribution-এর জন্য নিরাপদে সংরক্ষিত একই signing key দরকার। কোনো private key বা password রিপোজিটরিতে রাখা হয়নি।
