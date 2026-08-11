# تشغيل AL-thmany 2.3 داخل المجلد الآمن وWork Profile

## القاعدة الأساسية
Android وSamsung Knox يعزلان التطبيقات حسب ملف المستخدم. AL-thmany لا يحاول عبور حدود الملفات أو تجاوز Knox/DPC. يوجد محركان فقط:

- **Accessibility**: يستخدم عندما يسمح Android بتفعيل الخدمة داخل نفس البيئة.
- **Shizuku + UIAutomator**: يستخدم فقط إذا أثبت الفحص أن shell يستطيع رؤية واجهة WhatsApp الهدف داخل تلك البيئة؛ النجاح غير مضمون داخل Secure Folder/Work لأن السياسة تختلف من جهاز لآخر.

## Samsung Secure Folder
1. ثبّت AL-thmany وWhatsApp المطلوب داخل المجلد الآمن نفسه.
2. افتح نسخة AL-thmany الموجودة داخل المجلد الآمن.
3. اختر WhatsApp أو WhatsApp Business الظاهر داخل نفس البيئة.
4. إذا Accessibility متاحة، يمكن اختيار محرك Accessibility.
5. إذا Accessibility غير متاحة، شغّل Shizuku على الجهاز، امنح AL-thmany الإذن، ثم نفّذ **اختبار Shizuku** بينما WhatsApp الهدف مفتوح.
6. لا تبدأ دفعة كبيرة قبل اختبار رابط واحد. إذا كان UIAutomator لا يرى حزمة WhatsApp داخل Secure Folder يتوقف المحرك ويحفظ الرابط الحالي، ولا ينفذ نقرات عمياء.

## Work Profile
نفس القاعدة: AL-thmany وWhatsApp يجب أن يكونا في نفس Android user/profile الذي سيتم تشغيله. قد تسمح سياسة المسؤول بـAccessibility أو تمنعها. Shizuku لا يلغي سياسة DPC؛ يستخدم فقط إذا أثبت الفحص أن واجهة الهدف مرئية وأن input مسموح فعليًا.

## Target Lock
قبل بدء الجلسة يتم قفل `packageName + profileKey`. وفي Shizuku 2.3 يوجد فحص إضافي قبل استهلاك أي رابط للتأكد من وجود AL-thmany وWhatsApp في Android user نفسه، ثم فحص foreground package قبل كل Tap/Swipe/Back.

## إذا فتح واتساب لكن لم ينفذ الانضمام
- في Accessibility: افحص أن الخدمة مفعلة وتستقبل شجرة الواجهة داخل نفس الملف.
- في Shizuku: نفّذ Test Shizuku. إذا ظهر `TARGET_UI_NOT_VISIBLE` أو UI dump فارغ، فهذه البيئة لا تكشف واجهة WhatsApp للمحرك. لا يحاول AL-thmany تجاوز ذلك.

## المجتمعات في 2.3
Shizuku Community Traversal يعمل فقط عندما تكشف UIAutomator صفحة المجتمع وصفوف القروبات بشكل دلالي. كل صف يحتاج إجماع لقطتين، ويُستبعد الإعلان والإضافة/الإدارة/المغادرة/الحذف/الإبلاغ، مع حدود ثابتة للتمرير والرجوع ومنع التكرار.

## حدود النظام
إذا منع Knox أو DPC الوصول إلى UI hierarchy أو input داخل البيئة، لا توجد محاولة تجاوز في AL-thmany. يجب استخدام محرك مسموح في تلك البيئة أو تغيير السياسة من جهة الإدارة عندما تكون أنت المسؤول عنها.
