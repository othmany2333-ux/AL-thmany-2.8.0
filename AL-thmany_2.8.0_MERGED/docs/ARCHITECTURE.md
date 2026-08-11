# AL-thmany 2.1 — Profile & Community Core Architecture

## 1. Input & normalization
`WhatsAppLinkParser` يطبع روابط `chat.whatsapp.com`, يصلح المحارف غير المرئية/full-width، يزيل التكرارات ويحترم حد الجلسة.

## 2. Persistent queue
`GroupLinkDatabase` هو المصدر الموثوق لحالة الجلسة. schema v5 يضيف فهرسًا لمسار التشغيل الساخن `(session_id, status, position_index)`. `GroupLinkRepository` يوفر استعلامات للرابط الحالي/التالي وDashboard محدود بدل Materializing قائمة 5000 رابط في كل حدث.

## 3. Profile-aware launch layer
`ProfileEnvironment` يصف ملف Android الذي تعمل داخله العملية الحالية (Owner / Work-managed / Samsung isolated / Secondary). `WhatsAppLauncher` يكتشف فقط تطبيقات WhatsApp المرئية داخل نفس الملف، ويتحقق من قدرتها على معالجة رابط الدعوة، ثم يستخدم explicit package Intent. داخل ملف معزول لا يبدأ Auto غير المحدد، ويُثبت `packageName + profileKey` طوال التشغيل. التطبيق لا يحتاج صلاحية INTERNET ولا يتحدث مع خوادم WhatsApp.

## 4. Accessibility perception
`QuickJoinAccessibilityService` يجمع العقد المرئية فقط بحد bounded ويستخرج text/contentDescription/hint/resource ID/class/bounds. أحداث Accessibility تندمج في scan loop متسلسل واحد لتجنب التوازي والتراكم.

## 5. Semantic matcher
`AccessibilityJoinMatcher` يميز Preview / Join / Request / Confirm، loading، terminal states، restriction، conversation، وWhatsApp home. إجراءات مثل Cancel Request / Leave / Report / Delete محجوبة صراحةً.

## 6. Evidence & action scoring
`ScreenEvidencePolicy` يحدد التعارضات، بينما `AccessibilityActionScoringPolicy` يرتب مرشحي الأزرار حسب السياق والدلالة وقابلية النقر ونوع العقدة وهامش الأفضلية.

## 7. Screen fingerprint
`RuntimeScreenFingerprint` يبني بصمة مستقرة من الأدلة الدلالية المرئية، مستقلة عن ترتيب traversal. تستخدم لقياس عدد المسحات المتطابقة، ودعم Confidence Engine وWatchdog.

## 8. Confidence engine
`RuntimeIntelligencePolicy` يعطي درجة 0–100 مع سبب قابل للتفسير. أي زر أو نتيجة نهائية لا تتجاوز العتبة تبقى في WAIT بدل تنفيذ قرار ضعيف.

## 9. Runtime coordinator
`RuntimeDecisionCoordinator` يفرض ترتيب القرار: `STOP_RESTRICTED → WAIT_LOADING → WAIT_CONFLICT → HANDLE_TERMINAL → HANDLE_ACTION → HANDLE_UNKNOWN`. Android traversal/clicking يبقى خارج هذه الطبقة لتسهيل الاختبار.

## 10. Execution guards
`AtomicBoolean` داخل الخدمة يطبق single-flight executor. `RuntimeIdempotencyGuard` يمنع إعادة نفس الإجراء الناجح بسبب أحداث Accessibility المتكررة مع إبقاء retry المحدود ممكنًا بعد نافذة suppression.

## 11. Stability & adaptive timing
`InvitationStabilityPolicy` و`AdaptiveInteractionPolicy` يحددان ثبات الأزرار/النتائج، post-loading settle، home detection، conversation confirmation، ومهلة التحميل. السرعة تعتمد على حالة الشاشة بدل delay واحد أعمى.

## 12. Watchdog & circuit breaker
`RuntimeWatchdogPolicy` يميز HEALTHY / LOADING / TRANSITION_NOISE / STALLED. `RuntimeCircuitBreaker` يوقف التشغيل بعد سلسلة أخطاء بنيوية (`UNKNOWN_SCREEN`, `ACTION_TIMEOUT`, `OPEN_FAILED`, `BROWSER_FALLBACK`) حتى لا يستمر المحرك على واجهة WhatsApp غير متوافقة.

## 13. Diagnostics, shadow & replay
`RuntimeDiagnosticStore` يحتفظ بسجل محلي دوّار صغير للقرار والثقة والمرحلة، ولا يسجل محتوى الرسائل. Shadow Mode يحلل ويسجل بدون نقر. `RuntimeReplayEngine` يعيد تشغيل تسلسل حالات Pure Kotlin لاختبارات regression.

## 14. UI & large queue performance
`MainViewModel` يستخدم Dashboard snapshot محدودًا أثناء التشغيل، بينما الإحصاءات تأتي من SQL aggregates. RecyclerView يعرض نافذة صغيرة فقط؛ التصدير/المشاركة فقط يحمّلان الجلسة كاملة عند طلب المستخدم.

## 15. Recovery & persistence
SharedPreferences تحفظ stage/pending action/stop reason والتوقيت، وSQLite يحفظ نتائج الروابط. إعادة إنشاء Activity لا تعيد الجلسة من الصفر؛ الخدمة تتحقق من session ID/current link قبل كل قرار. في Community Traversal يتم حفظ parent link والمرحلة والمجموعة الحالية والمجموعات المعالجة ومحاولات التمرير، ثم يعاد تسليح مهلة المرحلة عند إعادة إنشاء خدمة Accessibility.

## 16. Community traversal
`CommunityTraversalMatcher` يعمل فقط بعد إثبات أن الشاشة Community home، ويستبعد Announcements والإضافة/الإنشاء/الإدارة/الإعدادات/المغادرة/الإبلاغ/الحذف/الحظر. `CommunityTraversalPolicy` يحد العملية إلى 256 صف قروب و40 scroll و3 Back لكل مسار تعافٍ. صف القروب يُعالج مرة واحدة بمفتاح دلالي ثابت، ثم تعاد نفس قواعد Join/Request/terminal الحالية بدل إنشاء محرك نقر منفصل أقل أمانًا.

## 17. Safety boundaries
التشغيل user-visible ومحدود بعدد روابط خارجية لكل تشغيل صريح. لا توجد إعادة فتح تلقائية لنفس الرابط، ولا stealth/anti-detection، ولا تجاوز لشاشات تقييد WhatsApp. معالجة قروبات المجتمع تعمل فقط داخل مجتمع فتحه رابط المستخدم، وفقط على صفوف قروبات تم التعرف عليها دلاليًا ضمن الحدود الثابتة. عبور Secure Folder/Work Profile من ملف آخر غير مدعوم؛ يجب تثبيت AL-thmany وWhatsApp وAccessibility داخل نفس الملف.
