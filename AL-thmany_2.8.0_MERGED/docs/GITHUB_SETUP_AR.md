# إعداد AL-thmany 2.1.0 على GitHub

هذه الحزمة جاهزة لتوضع في مستودع GitHub ثم تُبنى عبر GitHub Actions.

## مستودع جديد

1. أنشئ مستودع GitHub جديدًا، ويفضل Private.
2. افتح Codespaces للمستودع.
3. ارفع `AL-thmany_2.1.0_GitHub_Ready_Source.zip` إلى جذر Codespace، ثم نفّذ:

```bash
unzip -o AL-thmany_2.1.0_GitHub_Ready_Source.zip
cp -a AL-thmany_2.1.0_Profile_Community_Core/. .
rm -rf AL-thmany_2.1.0_Profile_Community_Core
rm -f AL-thmany_2.1.0_GitHub_Ready_Source.zip

python3 scripts/validate_source.py
bash scripts/compile_pure_kotlin.sh
bash scripts/run_pure_kotlin_regressions.sh
bash scripts/run_runtime_benchmarks.sh

git add -A
git commit -m "AL-thmany 2.1.0 Profile Community Core"
git push origin main
```

بعد `git push` يبدأ Workflow باسم **AL-thmany Android CI** تلقائيًا. ويمكن تشغيله يدويًا من **Actions → AL-thmany Android CI → Run workflow**.

عند النجاح، افتح تشغيل الـWorkflow ثم قسم **Artifacts** وحمّل `al-thmany-debug-apk`. بداخله `app-debug.apk`.

## التحقق من النسخة

```bash
grep -E "versionCode|versionName" app/build.gradle.kts
```

يجب أن تكون النسخة 2.1.0.

## ملاحظات Secure Folder / Work Profile

للتشغيل داخل Samsung Secure Folder أو Work Profile يجب أن تكون نسخة AL-thmany وWhatsApp وخدمة Accessibility داخل نفس Android Profile. لا تعتمد على نسخة AL-thmany خارج المجلد الآمن للتحكم بواتساب داخله.
