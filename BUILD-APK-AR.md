# بناء APK بدون Android Studio

1. أنشئ مستودع GitHub جديدًا.
2. ارفع **محتويات هذا الملف المضغوط** إلى جذر المستودع، بحيث يظهر `build.gradle.kts` و`app/` و`.github/` مباشرة في الصفحة الرئيسية للمستودع.
3. افتح تبويب **Actions**.
4. اختر **Build VidGrab APK** ثم **Run workflow**. ويمكن أيضًا تشغيله تلقائيًا عند Push إلى `main` أو `master`.
5. انتظر انتهاء المهمة بنجاح.
6. افتح تشغيل المهمة الناجح، وانزل إلى **Artifacts**، ثم حمّل `VidGrab-debug-apk`.
7. فك ضغط ملف Artifact وستجد `app-debug.apk`.

هذا APK تجريبي Debug للتثبيت اليدوي والاختبار، وليس إصدار Release موقّعًا للنشر على Google Play.
