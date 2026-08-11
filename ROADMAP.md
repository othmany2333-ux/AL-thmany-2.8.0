# AL-thmany Roadmap

## منجز في 1.2 — Smart Runtime Pro

- Runtime Decision Coordinator بأولوية موحدة للحالات.
- Confidence Engine وAction Scoring قابلان للتفسير.
- Screen Fingerprint + Watchdog + Circuit Breaker.
- Single-flight executor وIdempotency Guard ضد التكرار.
- Shadow Mode وReplay Engine وDiagnostic Journal.
- Live Runtime Health في لوحة التشغيل.
- SQLite schema v5 مع فهرسة مسار التشغيل وDashboard snapshot محدود.
- Event coalescing وتقليل Accessibility noise.
- Loading Guard وConflict Guard وone-link/one-launch.
- مطابقة عربية/إنجليزية موسعة لحالات الدعوة والنتائج والقيود.
- Micro-benchmark Pure Kotlin ضمن GitHub Actions.

## الخطوات التالية بعد اختبار APK الفعلي

- Instrumentation tests على أجهزة Android وإصدارات WhatsApp متعددة.
- إنشاء مكتبة Replay أكبر من snapshots دلالية مجهولة المحتوى للحالات الجديدة التي تظهر ميدانيًا.
- قياسات Memory/CPU باستخدام Android Profiler على تشغيل طويل، ثم ضبط الحدود بناءً على بيانات فعلية.
- تحسينات إضافية للوصول البصري والتباين ودعم أحجام الشاشات المختلفة.
- Migration tests لكل ترقية مستقبلية لقاعدة SQLite والإعدادات.

لن تتضمن الخطة ميزات لإخفاء الأتمتة أو تجاوز قيود WhatsApp أو الانضمام التلقائي الشامل إلى جميع مجموعات المجتمع الفرعية.
