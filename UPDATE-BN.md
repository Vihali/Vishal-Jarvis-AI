# Android আপডেট — কাজের বর্তমান অবস্থা

মূল সোর্সটি `Vishal-Jarvis-Android-App-Source.zip`-এ আছে; workflow সেই ZIP থেকে APK তৈরি করে।

- কল করার ও ডায়ালার খোলার অ্যাকশন সরানো হয়েছে; SIM/network country lookup সরানো হয়েছে। WhatsApp-এর জন্য Contacts ও phone-number formatting আগের মতো আছে; এটি কল বা SIM access নয়।
- বিদ্যমান অ্যাপ লঞ্চ, টর্চ, Gemini Live, WhatsApp draft এবং কথোপকথন হিস্ট্রি রাখা হয়েছে। Gemini Live tool-এ ব্যাটারি/চার্জ, স্টোরেজ ও validated internet status, media/system volume, Bluetooth settings ও alarm intent যুক্ত হয়েছে। Android-এর নিয়মে Wi-Fi/Bluetooth settings এবং alarm-এ ব্যবহারকারীর নিশ্চিতকরণ প্রয়োজন হতে পারে।
- নতুন ফিচারগুলি ফোনে instrumentation/device test করা হয়নি। ZIP-এ বিদ্যমান instrumentation test আপডেট করা হয়েছে, তবে CI বর্তমানে সেটি চালায় না।
- **এখনও বাস্তবায়িত নয়:** screen-off wake word, hands-free wake-up, boot থেকে microphone চালানো, lock-screen wake, নতুন persistent memory system, camera vision, লাইভ web/weather, এবং Gemini/Groq/NVIDIA fallback। Gemini Live-এর বিদ্যমান voice session wake-word listener নয়। লোকাল হিস্ট্রি সর্বশেষ ১০০ turn রাখে; এটি পূর্ণ দীর্ঘমেয়াদি মেমোরি নয়।
- Android-এর microphone foreground service, background activity launch, boot ও secure keyguard সংক্রান্ত বিধিনিষেধ আছে। battery optimization বাদ দিলেও নিরবচ্ছিন্ন execution বা নিরাপদ lock bypass নিশ্চিত হয় না। এ কারণে battery exemption বা অন্য intrusive permission ব্যবহার করে এই সীমা পাশ কাটানোর দাবি করা হয়নি।
- Build workflow প্রতিবার স্থানীয় debug signing key তৈরি করে। আগের APK ভিন্ন signing key-তে signed হলে সেটির ওপর সরাসরি update install হবে না; uninstall করলে পুরোনো local data মুছে যেতে পারে। নিজের release key ব্যবহার করে ধারাবাহিক signing করা উচিত।
