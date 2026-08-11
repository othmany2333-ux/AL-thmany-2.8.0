# AL-thmany 2.8.0 — Accessibility Visual Join & Fast Existing-Chat Handoff

## ما الجديد في 2.8.0

- أضيف fallback بصري لمسار Accessibility نفسه، وليس Shizuku فقط. إذا فتح واتساب الدعوة لكن لم يعرّف زر Join/Request داخل شجرة Accessibility، يلتقط المحرك Screenshot محليًا ويبحث فقط عن زر واتساب أخضر عريض في الجزء السفلي ثم يضغط مركزه بإيماءة حقيقية.
- لا توجد إحداثية ثابتة ولا ضغط أعمى: المسار لا يعمل على Home/Conversation/Restriction/Loading/Terminal ولا إذا ظهر زر دلالي معروف، ويشترط تشغيل جلسة صريحة داخل حزمة واتساب المحددة.
- أضيف `android:canTakeScreenshot="true"` إلى تعريف خدمة Accessibility حتى يعمل المسار البصري رسميًا على Android 11 وما بعده.
- بعد الضغط البصري يتحقق من اختفاء الزر، يعيد اللمسة مرة واحدة عند بقائه، ويتعامل مع فشل Screenshot كفشل تحقق بدل اعتباره نجاحًا وهميًا.
- إذا فتح رابط لمجموعة منضم لها مسبقًا وظهرت المحادثة مباشرة بدون Join/Request، يثبت المحادثة عبر قراءات متتالية قصيرة، ينفذ Back فورًا، يسجل Already Member، ثم يفتح الرابط التالي.
- المسار الدلالي الأصلي يبقى الأسرع؛ Screenshot fallback لا يبدأ إلا عندما لا يجد المحرك زرًا دلاليًا صالحًا.

## ما الجديد في 2.7.4

- الشخصي: ينفذ Join/Request بلمسة `dispatchGesture` حقيقية داخل حدود الزر الدلالي أولًا، ثم يستخدم `ACTION_CLICK` كمحاولة بديلة. كل محاولة لها مفتاح مستقل، لذلك لا يمنع حارس التكرار المحاولة الثانية إذا أعاد Android نجاحًا وهميًا.
- أي تشغيل صريح يطفئ Shadow تلقائيًا داخل MainActivity وخدمتي Accessibility/Shizuku؛ لا يستطيع إعداد قديم مستعاد من Samsung Backup تحويل التشغيل إلى مراقبة بدون ضغط.
- العمل/Secure Folder: إذا بقيت شجرة UiAutomation على Launcher رغم إثبات أن واتساب الدقيق في Android user المحدد هو foreground، يفحص المحرك Screenshot محليًا ويقبل فقط زرًا أخضر عريضًا في النصف السفلي. لا يستخدم موضعًا ثابتًا ولا يقبل زر واتساب العائم الصغير.
- بعد الضغط البصري يتحقق من اختفاء الزر، يعيد المحاولة مرة واحدة عند بقائه، يثبت نشاط Conversation إن كان Join، ثم ينفذ Back المكافئ لإغلاق X ويفتح الرابط التالي مباشرة.
- نتائج Request المؤكدة تغلق سطح الطلب قبل الانتقال. محادثة Join المؤكدة تعود خطوة واحدة ثم تنفذ handoff للرابط التالي.
- خُفض حد عدم الاستجابة غير المحمّلة في الوضع السريع إلى ثانية واحدة، مع بقاء Loading الحقيقي محميًا حتى 20 ثانية وRestriction/Retry Later توقفًا إلزاميًا.

> المسار البصري لا يحاول تجاوز Knox ولا يضغط عشوائيًا: exact user/package foreground شرط إلزامي، والهدف يجب أن يطابق لون وحجم زر واتساب الإيجابي العريض.

## ما الجديد في 2.7.3

- إصلاح حالة Samsung الظاهرة في الصور: إذا كان النظام يعرض خدمة AL-thmany «قيد التشغيل» فلا يعود زر البدء عالقًا بانتظار callback داخلي؛ يبدأ الرابط، وأول حدث واتساب يعيد تثبيت اتصال الخدمة والـheartbeat داخل الملف الحالي.
- توحيد إثبات الاتصال المحلي من callback الحي أو heartbeat حديث لنفس حزمة التطبيق ونفس Android profile، مع قراءة component المفعّل من `AccessibilityManager` و`Settings.Secure` بشكل متوافق مع نسخة debug.
- إصلاح Work/Shizuku للحالة المسجلة `nodes=0; pkg=launcher`: بعد 180ms من استمرار target-hidden/NO_ROOT يجرب المحرك `uiautomator dump` القياسي بحد أقصى محاولتين. إذا أثبتت الشجرة عقدًا من حزمة واتساب الدقيقة يتحول التشغيل إلى المسار المتوافق ويضغط الزر الدلالي.
- دعم زر Join الظاهر كنص `TextView` غير قابل للضغط داخل حاوية قابلة للضغط؛ يظل الهدف بحاجة إلى قراءتين متطابقتين ثم يستخدم Tap داخل حدود النص نفسه.
- زر Work Controller يطبق allowlist عندما يكون AL-thmany فعلًا Profile/Device Owner، وإلا يستطيع اختيار Shizuku الجاهز بدل بقاء الزر معطلًا. لا يحاول الاستيلاء على ملف عمل تديره CloudDPC/Knox.
- ما زالت الحماية تمنع الضغط الأعمى: لا ينفذ Shizuku Tap إلا بعد ظهور عقدة Join/Request/Preview/Confirm من حزمة واتساب المحددة وبحدود شاشة آمنة، وتبقى Restriction/Retry Later توقفًا إلزاميًا.

> لا يستطيع تطبيق عادي تحويل نفسه إلى Profile Owner داخل ملف عمل موجود. الإصلاح يستخدم Work Controller الحقيقي عندما تسمح الملكية، أو Shizuku المصرح به والمسار الدلالي المتوافق عندما يكون مدير الملف خارجيًا.

## ما الجديد في 2.7.2

- أضاف الإصدار إشارة اتصال `AccessibilityService` الحية، وأطفأ `Shadow Mode` القديم، وأصلح تحقق نتيجة `am start` وإعادة حل نشاط واتساب الدقيق.
- حافظ على إيقاع 14/36/95ms، click throttle 68ms، وhandoff مباشر عند فاصل 0ms.

## ما الجديد في 2.7.0

- رفع حد الجولة الصريحة الواحدة من 150 إلى **1000 رابط متسلسل** مع بقاء القائمة المحلية حتى مليون رابط فريد.
- توحيد التحكم بسرعة الانتقال في الشخصي وملف العمل والمجلد الآمن من **0 إلى 10 ثوانٍ بدقة 100ms**.
- تسريع Accessibility إلى Event-first: مسح 14ms بعد الحدث، 36ms عند الثبات، Poll احتياطي 95ms، وأقل فاصل ضغط 68ms.
- إعادة تفعيل Persistent Shizuku بصورة تكيفية: يبدأ بمحاولة المسار الدائم السريع، ثم يسقط تلقائيًا إلى UIAutomator المتوافق إذا رفضه النظام أو Knox.
- إضافة إثبات سريع عالي الثقة بعد Join عبر exact Android user + WhatsApp Conversation activity؛ عند ثبوته يُفتح الرابط التالي بلا Back وبلا UI dump إضافي.
- الحفاظ على التحقق الدلالي للشاشات الملتبسة وطلبات موافقة المشرف، والتوقف الإلزامي عند Restriction/Retry Later.
- توسيع مطابقة نصوص وأرقام موارد واتساب الحديثة للمحادثة، طلب الانضمام، الرابط المنتهي، وحد الانضمام.
- واجهة عربية جديدة مستوحاة من التصميم المرجعي: رأس teal متدرج، بطاقات بيضاء، تحكم سرعة أدق، وحالة تشغيل ونتائج أوضح.

> ملاحظة: Shizuku لا يتجاوز عزل Samsung Knox. إذا لم يكشف النظام شجرة واتساب أو نشاط المحادثة إلى shell، يستخدم التطبيق المسار المتوافق ويحفظ الرابط الحالي عند فقدان الدليل بدل تنفيذ ضغط غير موثوق.

## ما الجديد في 2.6.2

يبني 2.6.1 فوق مسار 2.6.0 السريع ويضيف Continuity State Machine تمنع توقف التشغيل على المحادثة أو الشاشة المجهولة أو فقدان foreground المؤقت. ما زال 2.6.0 يركز على إزالة الحمل الذي كان يجعل Shizuku أبطأ من Accessibility حتى عندما تكون المؤقتات صغيرة. عند `SHIZUKU_FAST_UI_ACTIVE` لم تعد الجلسة الدائمة تحول شجرة UiAutomation إلى XML ثم تعيد تحليلها؛ تستخدم Compact Node Frame يحافظ على نفس الأدلة الدلالية المطلوبة للتصنيف والضغط.

كذلك يحتفظ Shizuku UserService بنسخ مؤقتة من العقد القابلة للضغط من نفس Scan. عندما يقرر المحرك أن زر Join/Request موثوق، يحاول `AccessibilityNodeInfo.ACTION_CLICK` على العقدة التي جاءت من نفس القراءة مباشرة بدل إعادة traversal كاملة للشجرة. إذا أصبحت العقدة قديمة أو لم تقبل الضغط، يبقى coordinate injection ثم shell input كـfallback فقط.

الإيقاع المستهدف أثناء هذا المسار هو: Event 14ms، Stable 36ms، Poll 95ms، click throttle 68ms، gesture 18ms، result analysis 88ms، retry 120ms، post-join evidence 35ms، والفاصل بين الروابط 0ms. Loading الحقيقي يبقى محميًا حتى 20s وRestriction/Retry Later يبقى Hard Stop.

> ملاحظة: الهدف هو مساواة السلوك والإحساس قدر الإمكان مع Accessibility، لكن لا يمكن ضمان زمن متطابق حرفيًا على كل Firmware لأن Android يمنح AccessibilityService وUiAutomation/Shizuku مسارين مختلفين للنظام.

## ما الجديد في 2.5.1

عندما يثبت المحرك حالة `SHIZUKU_FAST_UI_ACTIVE` يستخدم 2.5.1 preset سريع Event-first مخصص لـShizuku: يستيقظ على Accessibility events القادمة من UiAutomation بدل الاعتماد على Timer فقط، مع Scan بعد الحدث 14ms، Scan ثابت 36ms، Poll احتياطي 95ms، Watchdog بعد الضغط كل 40ms وحد ثابت non-loading قدره 1.2s. الفاصل بين الروابط يصبح 0ms داخل هذا المسار، وعند إثبات دخول محادثة القروب يفتح الرابط التالي مباشرة بدون انتظار Back. Loading الحقيقي يحتفظ بحماية 20s، وRestriction/Retry Later يبقى توقفًا إلزاميًا.

## ما الجديد في 2.5.0

الإصدار 2.5.0 يضيف **Persistent UiAutomation Turbo Bridge** داخل Shizuku UserService. الهدف هو تقليل الفرق العملي بين Shizuku وAccessibility: عند سماح Android بإنشاء جلسة UiAutomation دائمة، تتم قراءة شجرة `AccessibilityNodeInfo` والضغط وBack مباشرة من الجلسة نفسها، من دون تشغيل `uiautomator dump` و`input` كعمليات جديدة في كل دورة.

المسار آمن ومتدرج: إذا حجب النظام/Knox الجلسة الدائمة، يعود التطبيق تلقائيًا إلى مسار UIAutomator السابق ويحفظ الرابط الحالي. الأزرار عالية الثقة فقط تستخدم المسار أحادي القراءة، بينما الشاشات الملتبسة والمجتمعات تبقى تحت تحقق متعدد القراءات. كما تم تقليل استعلامات قاعدة البيانات أثناء الانتقال للرابط التالي، وإضافة Burst سريع أثناء التحولات مع مسح أهدأ عندما تستقر الشاشة.

> ملاحظة: Shizuku وAccessibility آليتان مختلفتان في Android، لذلك لا يمكن ضمان زمن مطابق حرفيًا على كل جهاز. 2.5.0 يستخدم أسرع مسار Native متاح ثم يسقط تلقائيًا للمسار المتوافق إذا منعه النظام.


## ما الجديد في 2.4.3
- Shizuku ينفذ Back سريعًا بعد التحقق من دخول محادثة القروب، ثم يسجل النتيجة ويفتح الرابط التالي مباشرة مثل مسار Accessibility.
- إزالة انتظار `am start -W` من كل رابط مع الحفاظ على exact-user/package lock.
- أول UI dump بعد فتح الرابط يبدأ من launch lease قصير بدل فحص `dumpsys` ثقيل.
- مزامنة ملفات النتائج الكاملة أصبحت كل 10 نتائج أو عند نهاية التشغيل بدل كل رابط.
- الفاصل 0 يعني handoff مباشر؛ التأخيرات الداخلية خُفضت إلى الحد الأدنى الآمن.
- شاشات Restriction/Retry Later ما زالت توقف التشغيل فورًا ولا يوجد تجاوز Knox/DPC.

> ملاحظة: التسلسل والـhandoff أصبحا مطابقين لسلوك Accessibility قدر الإمكان، لكن UIAutomator يعتمد snapshots وليس Accessibility events، لذلك لا يمكن ضمان نفس زمن الاستجابة حرفيًا على كل جهاز.

## ما الجديد في 2.4.0
- Profile-aware native router: Accessibility المحلية أولًا ثم Shizuku المحمي حسب قدرة نفس الملف.
- Work Controller اختياري: إذا كان AL-thmany هو Profile Owner يستطيع إضافة حزمته إلى allowlist الخاصة بخدمات Accessibility؛ لا يفعّل الخدمة بدل المستخدم ولا يستولي على Work Profile يملكه مدير آخر.
- Secure Folder يبقى ضمن عزل Knox: لا يوجد تجاوز cross-profile، وShizuku يخضع لفحص user/package/UI قبل أي ضغط.
- تشخيص موحد يوضح المحرك المقترح والإجراء المطلوب وملكية Work Profile.

# AL-thmany 2.4.0 — Work Profile Settings & Policy Probe

AL-thmany is an Android invitation-queue runner with two explicit execution backends: **Accessibility** where Android exposes it, and an opt-in **Shizuku + UIAutomator** backend where the device actually exposes the target WhatsApp UI to shell. The Shizuku backend is fail-closed: it does not claim to bypass Samsung Knox, Work Profile policy, or WhatsApp restrictions.


## 2.3.3 Work Profile Settings & Policy Probe

This release adds three safe settings routes and a read-only profile policy diagnostic so managed-profile failures are classified instead of guessed. It does not enable Accessibility silently and does not bypass Knox or device policy.


## 2.3.2 Profile Accessibility Fix

هذا الإصدار يعالج الحالة التي تفتح فيها نسخة AL-thmany داخل Work Profile / Samsung Secure Folder رابط واتساب الأول بنجاح، لكن لا تضغط زر الانضمام لأن خدمة Accessibility المفعلة فعليًا ليست متصلة داخل نفس Android profile.

- **Profile-local service heartbeat:** لا يكفي أن يقول Android إن الخدمة مفعلة؛ يجب وصول heartbeat حديث من خدمة AL-thmany داخل نفس الملف الحالي.
- **منع false-ready عبر الملفات:** AUTO/Accessibility لن يبدأ الجولة إذا كانت الخدمة المفعلة تخص ملفًا آخر أو لم تتصل محليًا.
- **فتح صفحة الخدمة المحددة:** زر Accessibility يحاول فتح صفحة `QuickJoinAccessibilityService` مباشرة عبر component name، ثم يعود للإعدادات العامة فقط إذا لم تدعم الشركة المصنّعة صفحة التفاصيل.
- **Live Profile Control Diagnostic:** يعرض `profileKey`، حالة النظام، الاتصال المحلي، آخر package event، حالة UI tree، ونتيجة القدرة.
- **Preserve current link:** في Work/Samsung isolated profiles، إذا كانت خدمة Accessibility المحلية متصلة لكن `rootInActiveWindow` بقي غير متاح، تتوقف الجولة مع حفظ الرابط الحالي بدل اعتباره فشلًا أو الانتقال بدون ضغط.
- **No policy bypass:** الإصدار لا يتجاوز Knox/Work Profile policy ولا يفعّل Accessibility بنفسه؛ إذا كانت السياسة تمنع الخدمة فسيظهر السبب بدل العمل بصمت.
- **WhatsApp restrictions:** شاشات التقييد/Retry later تبقى hard stop كما كانت.

## 2.3.1 Continuity Recovery highlights

- Accessibility is now the compatibility-safe primary path when it is enabled; a dead/stale Shizuku runtime can no longer silence joining.
- If a Shizuku run loses binder/shell/UI visibility and Accessibility is actually enabled, the current link is preserved and the run falls back to Accessibility.
- Joined conversations use one opportunistic fast Back pulse, then the next profile-locked invitation opens immediately; continuity never depends on Back succeeding.
- Request-submitted surfaces exit immediately before the next link.
- Preflight before queue mutation: Shizuku Binder/permission/UserService, same Android user, shell capability, and display bounds.
- Package-aware semantic action scoring; nodes from another package are rejected.
- Two consecutive matching UI dumps are required before Join/Request/Preview taps.
- Safe coordinate bounds + foreground target re-check immediately before every injected input.
- Strong post-Join verification; an unknown screen alone is never recorded as success.
- Current link is preserved on unreadable/ambiguous UI or Shizuku disconnect.
- Bounded Community traversal is available to Shizuku only when the UI dump exposes semantically safe subgroup rows.
- WhatsApp restriction / Retry later remains a hard stop.
- Queue capacity stays at 1,000,000 unique links; each explicit run in 2.3.1 stayed capped at 150 links.

# AL-thmany 2.2.0 — Shizuku Hybrid Engine

الإصدار 2.2.0 يضيف محرك **Shizuku** اختياريًا بجانب Accessibility. وضع Auto يفضّل Accessibility عندما تكون متاحة، وإلا يستخدم Shizuku بعد أن يكون Binder شغالًا وإذن التطبيق ممنوحًا. محرك Shizuku يفتح الدعوة في WhatsApp المقفل للجلسة، يتحقق أن واتساب هو النافذة الأمامية، يقرأ `uiautomator dump`، ثم ينفذ Tap فقط على bounds لعقدة دلالية آمنة تم تصنيفها Join/Request/Preview/Confirmation.

- **Probe-first**: اختبار Binder + Permission + shell + UI dump قبل الاعتماد على Shizuku.
- **Fail closed**: إذا Knox/Work Profile يمنع UI hierarchy أو input، يتوقف المحرك بدل الضغط على إحداثيات مجهولة.
- **No double engine**: خدمة Accessibility لا تنفذ أتمتة أثناء جلسة Shizuku.
- **No restriction bypass**: Restriction/Retry later يبقى Hard Stop.
- **150 رابطًا لكل تشغيل صريح في 2.2.0** مع بقاء القائمة حتى 1,000,000 رابط فريد.
- في 2.2.0 يظل Community subgroup traversal الكامل في Accessibility backend؛ Shizuku يعالج رابط المجتمع الخارجي فقط حتى يثبت اختبار الجهاز أن صفوف المجتمع مرئية عبر UIAutomator.

راجع: `docs/SHIZUKU_HYBRID_AR.md` لإعداد Shizuku والاختبار على Secure Folder/Work Profile.

# AL-thmany 2.1.0 — Profile & Community Core

## ما الجديد في 2.1.0

الإصدار 2.1.0 يضيف طبقة تشغيل واعية بملف Android الحالي، مع تثبيت هدف واتساب المحدد طوال التشغيل، ودعم المجلد الآمن/Work Profile بالطريقة المسموحة من Android: **AL-thmany وWhatsApp وAccessibility يجب أن تكون داخل نفس الملف**. كما يضيف Community Traversal bounded لمعالجة صفوف القروبات التابعة للمجتمع بعد الانضمام أو عند كون الحساب عضوًا مسبقًا، مع منع الإعلانات وأزرار الإضافة/الإدارة/المغادرة من أن تُعامل كقروبات.

- **Profile-Aware Target Resolver**: اكتشاف واتساب من `PackageManager` داخل ملف Android الحالي فقط، مع cache مفصول بمفتاح الملف.
- **Secure Folder / Work Profile Guard**: وضع Auto غير الصريح لا يبدأ داخل ملف ثانوي؛ يجب اختيار تطبيق واتساب واضح من نفس البيئة.
- **Runtime Target Lock**: تثبيت `packageName + profileKey` طوال التشغيل حتى لا تنتقل أحداث Accessibility إلى نسخة واتساب أخرى.
- **Target Test**: زر لاختبار تشغيل تطبيق واتساب المحدد من نفس الملف قبل بدء الجلسة.
- **Strict Explicit Intents**: فتح الدعوات بــpackage محدد؛ لا يوجد fallback صامت إلى متصفح أو واتساب شخصي عند تفعيل الحماية الافتراضية.
- **Community Traversal Engine**: بعد دخول المجتمع يبحث عن صفوف القروبات الدلالية، يفتح كل قروب مرة واحدة، يطبق Join/Request/Already-member/terminal logic الحالي، ثم يعود إلى قائمة المجتمع.
- **Community Checkpoint**: حفظ parent-link والمرحلة والمجموعة الحالية والمجموعات المعالجة ومحاولات التمرير، ليستطيع المحرك الاستمرار بعد أحداث Android المتقطعة.
- **Bounded Recovery**: 256 صف قروب كحد أمان لكل مجتمع، 40 محاولة تمرير، و3 خطوات Back كحد أقصى؛ لا توجد حلقات تمرير أو رجوع غير منتهية.
- **Restriction hard stop**: أي Restriction/Retry later حقيقي من واتساب يبقى سبب توقف إلزامي ولا يتم تجاوزه.
- حد التشغيل الصريح الخارجي في 2.1.0 كان **150 رابطًا**، والقائمة المحلية حتى **1,000,000 رابط فريد**.

## ما الجديد في 2.0.0

الإصدار 2.0.0 يركز على الاستمرارية والدقة بدل المسح المفرط: تشغيل Accessibility أصبح Event-first مع cadence تكيفي، وتعافٍ ذاتي من فقدان الجذر والشاشات الخاملة، وحفظ التشغيل عند انقطاع خدمة Accessibility المؤقت، وتثبيت أقوى لنتيجة Join/Request قبل التسليم للرابط التالي. كان حد التشغيل الصريح في ذلك الإصدار **150 رابطًا**، وتبقى شاشة تقييد واتساب سبب توقف إلزامي لا يتم تجاوزه.

- **Adaptive Event Cadence**: خفض busy-loop من مسح 4ms/poll 30ms إلى مسح أحداث سريع 18ms مع poll احتياطي 120ms، ثم تهدئة تلقائية عندما تثبت الشاشة.
- **Root Self-Recovery**: فقدان `rootInActiveWindow` لا يعلق الجلسة للأبد؛ بعد مهلة آمنة يُسجل الرابط وينتقل التالي دون نقر مجهول.
- **Screen/Lock Guard**: عند انطفاء الشاشة أو غياب شجرة Accessibility بسبب قفل الجهاز، يبقى الرابط الحالي محفوظًا ولا يتم استهلاك القائمة كأخطاء متتالية.
- **Recoverable Service Interrupt**: إذا كان Auto Resume مفعلاً، `onInterrupt()` يحفظ التشغيل ولا يحوله إلى توقف نهائي.
- **Stable Handoff**: لا يتم اعتماد اختفاء زر Join وحده؛ watchdog بعد الإجراء يتطلب عمر دليل واستقرار شاشة قبل النجاح أو force-advance.
- **Stalled Screen Recovery**: شاشة ثابتة غير معروفة بدون Loading/Restriction/Action آمن تُسجل وتُسلّم للرابط التالي بدل الوقوف.
- **Continuous by default**: الإيقاف التلقائي عند مغادرة واتساب أصبح غير مفعّل افتراضيًا للتثبيت الجديد؛ يبقى خيارًا يدويًا للمستخدم.

## ما الجديد في 1.9.1

الإصدار 1.9.1 يحافظ على Instant Terminal Router وContinuous Handoff ويرفع حد التشغيل الصريح من 80 إلى **150 رابطًا**، مع بقاء سعة القائمة الكبيرة والاستئناف وإزالة التكرار والتوقف عند قيود واتساب كما هي.

- تمييز دلالي بين **مجموعة** و**مجتمع** مع بقاء Join/Request كإجراءات آمنة وواضحة.
- دعم الصيغ الظاهرة في الصور الحالية للرابط المعاد تعيينه، الإزالة من المجموعة، طلب الانضمام المرسل، وإشعار موافقة المشرف.
- **إلغاء الطلب** لا يُضغط أبدًا؛ إذا ظهر وحده بدون زر Request جديد فيُفهم أن الطلب موجود بالفعل ويتم تسجيل النتيجة ثم المتابعة.
- **موافق / OK** في شاشة خطأ نهائية لم يعد يُحسب كزر إيجابي ينافس Join/Request؛ يستخدم فقط بعد حفظ النتيجة لإغلاق النافذة عند الحاجة.
- مسار الإغلاق بعد النتيجة: X الدلالي → موافق/OK الآمن للحالات النهائية → Back → fallback محمي داخل سياق الدعوة فقط.
- الاحتفاظ بـLoading Guard وContext Guard وConfidence Engine وSingle-flight وIdempotency وAuto Resume والتحكم من الإشعار.
- عند السرعة 0 لا توجد مهلة صناعية بين الروابط بعد النتيجة المؤكدة، بينما تظل شاشة **جار التحميل** محمية بمهلة مستقلة.
- قائمة SQLite حتى **1,000,000 رابط فريد**، وحد التشغيل الصريح في 1.9.1 كان **150 رابطًا**، مع حفظ البقية للاستئناف.

# AL-thmany 1.4 — Adaptive Multi-WhatsApp Runtime

**AL-thmany** هو تطبيق Android مساعد للتعامل مع روابط دعوات مجموعات ومجتمعات WhatsApp التي يزوّدها المستخدم صراحةً. يعتمد على Android Accessibility لتحليل واجهة WhatsApp المرئية واتخاذ قرار سياقي، ولا يستخدم API غير رسمي ولا يتصل بخوادم WhatsApp مباشرةً.

## ما الجديد في 1.4

الإصدار 1.4 يبني على Smart Runtime Pro ويضيف اختيار واتساب ديناميكيًا، تحكمًا بالملف الشخصي، وتوقفًا/استئنافًا أكثر ذكاءً:

- **اختيار واتساب ديناميكي**: يعرض التطبيقات المثبتة التي تبدو كواتساب داخل ملف Android الحالي، بما في ذلك النسخ المستنسخة التي تعلن نفسها كتطبيقات واتساب/معالجات لروابط الدعوة.
- **Work / Secure Folder بصورة Profile-local**: يعمل داخل ملف العمل/الملف المعزول عند تثبيت AL-thmany وتفعيل Accessibility داخل نفس الملف؛ Android لا يسمح لتطبيق في الملف الشخصي بالتحكم مباشرةً بتطبيق داخل ملف معزول آخر.
- **Pause on Exit + Auto Resume**: يتوقف مؤقتًا عند مغادرة واتساب، ويستأنف تلقائيًا عند العودة فقط إذا كان Auto Resume مفعّلًا وكان التوقف تلقائيًا لا يدويًا.
- **Notification Controls**: إيقاف مؤقت / استئناف / إيقاف من الإشعار أثناء التشغيل.
- **Return to Dashboard**: عند انتهاء نافذة التشغيل أو اكتمال الطابور يحاول إعادة فتح واجهة AL-thmany.
- **Turbo Runtime 1.4**: تقليل Poll/Scan/Click/Result/Exit/Handoff مع بقاء Loading Guard منفصلًا.

- **Turbo Handoff**: تقليل التأخيرات الداخلية بعد النتيجة المؤكدة مع إبقاء `جار التحميل` والتقييد خارج مسار التسريع.
- **Automatic Resume**: مفتاح اختياري يحافظ على التشغيل الصريح الحالي إذا أعاد Android إنشاء خدمة Accessibility، ويستأنف من نفس الرابط المحفوظ.
- **Fast Exit**: في الوضع السريع تستخدم نافذة الدعوة محاولتي إغلاق/رجوع فقط بدل أربع محاولات.
- **Large Queue**: حتى 1,000,000 رابط فريد مخزن في الجلسة، مع بقاء 150 رابطًا كحد لكل تشغيل صريح في ذلك الإصدار.


- **Runtime Decision Coordinator**: أولوية موحدة للحالات: تقييد → تحميل → تعارض → نتيجة نهائية → إجراء → شاشة غير معروفة.
- **Confidence Engine**: تقييم قابل للتفسير لقوة دليل زر Join / Request / Preview / Confirm والنتائج النهائية قبل التنفيذ.
- **Action Scoring**: ترتيب مرشحي الأزرار حسب السياق، قابلية النقر، نوع العقدة، الـresource ID، حدود العنصر، وإشعار موافقة المشرف.
- **Screen Fingerprint**: بصمة مستقرة للشاشة تقلل تأثير أحداث Accessibility المكررة وتساعد على اكتشاف الشاشة العالقة.
- **Single-flight Executor + Idempotency Guard**: عملية نقر واحدة فقط في اللحظة نفسها، مع منع إعادة نفس الإجراء بسبب الأحداث المكررة.
- **Watchdog + Circuit Breaker**: يكتشف الشاشة الثابتة مدة غير طبيعية، ويوقف التشغيل بعد سلسلة أخطاء بنيوية متتالية بدل الاستمرار في قرارات خاطئة.
- **Shadow Mode**: وضع تشخيص يقرأ الشاشة ويسجل القرار المتوقع بدون تنفيذ أي ضغط.
- **Replay Engine**: اختبارات Pure Kotlin تعيد تشغيل تسلسلات حالات اصطناعية لفحص القرارات بدون Android Accessibility.
- **Diagnostic Journal**: سجل محلي دوّار للقرارات والثقة والتعليق، بدون تخزين محتوى المحادثات أو نصوص الرسائل، ويمكن مشاركته من الإعدادات للتشخيص.
- **Live Runtime Health**: سطر حي في لوحة التشغيل يعرض directive والإجراء والثقة وثبات الشاشة وحالة Watchdog بدون تسجيل نصوص واتساب.
- **Indexed Queue + Dashboard Window**: فهرس SQLite لمسار التشغيل، واستعلام Dashboard يعرض نافذة صغيرة من الروابط بدل تحميل آلاف الصفوف إلى الواجهة.
- **Event Coalescing**: دمج أحداث تغيير محتوى واتساب في مسار فحص متسلسل واحد مع `notificationTimeout=60ms` لتقليل الحمل.
- **Runtime Micro-benchmark**: سكربت Pure Kotlin لاختبار تجهيز آلاف الروابط وبصمات الشاشة وIdempotency ledger قبل الدمج.

## سلوك واتساب المدعوم

- التفريق بين **عرض المجموعة/المجتمع** و**الانضمام إلى المجموعة** و**طلب الانضمام** و**الانضمام للمجتمع**.
- حظر **إلغاء الطلب** والمغادرة والإبلاغ والحذف والإجراءات الهدامة.
- فهم **جار التحميل / Loading** وProgressBar وعدم الإغلاق أثناء التحميل.
- تجاوز الحالات النهائية المعروفة مثل: عضو مسبقًا، طلب مرسل، مجموعة ممتلئة، رابط تمت إعادة تعيينه/انتهى، إزالة المستخدم، والفشل المعروف.
- بعد نجاح Join وفتح محادثة فعلية بدليل قوي، يرجع مرة واحدة ثم يفتح الرابط التالي داخل WhatsApp دون العودة إلى واجهة AL-thmany.
- نفس رابط الدعوة لا يُعاد فتحه تلقائيًا، والروابط المكررة تزال عند تجهيز القائمة.
- عند ظهور تقييد من WhatsApp يتوقف التشغيل ولا يحاول تجاوزه.

## الأداء والاستقرار

- حتى **1,000,000 رابط فريد** محفوظ في الجلسة.
- حتى **1000 رابطًا لكل تشغيل صريح**؛ الباقي يبقى محفوظًا للاستكمال اللاحق.
- قراءة الرابط الحالي/التالي فقط أثناء التشغيل بدل تحميل الجلسة كاملة.
- SQL aggregates لإحصاءات الجلسة، وفهرس `session + status + position` للمسار الساخن.
- نافذة Dashboard حتى 120 رابطًا حول نقطة العمل، بينما التصدير الكامل يتم فقط عند طلب المستخدم.
- مزامنة مرايا النتائج على دفعات بدل إعادة كتابة ملفات كبيرة بعد كل رابط.
- تحليل الإدخال الكبير في الخلفية مع Debounce لتجنب تجميد واجهة Android.

## البناء والتحقق

المتطلبات: JDK 17 وAndroid SDK 36 وGradle 8.13. GitHub Actions مهيأ للتحقق من المصدر، Pure Kotlin regressions، Unit Tests، Android Lint، ثم Debug APK.

للفحص السريع داخل Codespaces:

```bash
python3 scripts/validate_source.py
bash scripts/compile_pure_kotlin.sh
bash scripts/run_pure_kotlin_regressions.sh
```

Artifact الناتج من GitHub Actions:

```text
al-thmany-debug-apk
```

## حدود التصميم

AL-thmany يعمل فقط على الروابط التي أدخلها المستخدم وبإجراء تشغيل ظاهر ومحدود. لا يحتوي على سلوك لإخفاء الأتمتة أو تجاوز قيود WhatsApp. في 2.1 يمكنه، بعد فتح رابط مجتمع أدخله المستخدم، معالجة **صفوف القروبات التي تكشفها واجهة Accessibility دلاليًا داخل ذلك المجتمع** ضمن حدود Recovery ثابتة؛ لا يخمّن عناصر غير معروفة ولا يتجاوز صلاحيات أو قيود واتساب. المجلد الآمن/Work Profile يتطلبان تثبيت AL-thmany وWhatsApp وتفعيل Accessibility داخل نفس ملف Android. أي تغيير كبير في واجهة WhatsApp قد يحتاج تحديث المطابقات واختبارات Replay/Regression جديدة.


## 1.8.1 Smart Terminal Escape
- Screen-wide semantic aggregation catches terminal WhatsApp errors even when Accessibility splits the sentence across several nodes.
- Reset/expired, removed/banned, full/limit, missing group/community and generic join-denial dialogs advance automatically after the result is recorded.
- Pending-request text can also be recognized across split nodes; cancel-request remains blocked.

## Instant Terminal Router (1.9)

Specific final invitation states are routed on the first reliable Accessibility frame: reset/expired/revoked invite, full target, removed/banned account, submitted request, and already-member. These outcomes skip redundant stability/confidence and inter-link delay, then use the bounded verified acknowledgement/X/Back escape chain before the next link. Loading remains recoverable, and WhatsApp restriction screens still stop the run.
