#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path.cwd()

P = {
    "build": ROOT / "app/build.gradle.kts",
    "manifest": ROOT / "app/src/main/AndroidManifest.xml",
    "main_layout": ROOT / "app/src/main/res/layout/activity_main.xml",
    "settings_layout": ROOT / "app/src/main/res/layout/activity_settings.xml",
    "menu": ROOT / "app/src/main/res/menu/menu_main.xml",
    "colors": ROOT / "app/src/main/res/values/colors.xml",
    "themes": ROOT / "app/src/main/res/values/themes.xml",
    "strings_ar": ROOT / "app/src/main/res/values/strings.xml",
    "strings_en": ROOT / "app/src/main/res/values-en/strings.xml",
    "main_activity": ROOT / "app/src/main/java/com/althmany/groupmanager/ui/MainActivity.kt",
    "settings_activity": ROOT / "app/src/main/java/com/althmany/groupmanager/ui/SettingsActivity.kt",
    "validator": ROOT / "scripts/validate_source.py",
}

def die(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)

def read(path: Path) -> str:
    if not path.exists():
        die(f"missing file: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")

def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")

def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = read(path)
    if new in text:
        print(f"OK already: {label}")
        return
    count = text.count(old)
    if count != 1:
        die(f"{label}: expected one anchor, found {count} in {path.relative_to(ROOT)}")
    write(path, text.replace(old, new, 1))
    print(f"PATCHED: {label}")

def append_before_resources_close(path: Path, block: str, marker: str, label: str) -> None:
    text = read(path)
    if marker in text:
        print(f"OK already: {label}")
        return
    if "</resources>" not in text:
        die(f"{label}: </resources> missing")
    write(path, text.replace("</resources>", block.rstrip() + "\n</resources>", 1))
    print(f"PATCHED: {label}")

# 1) Version + user-visible rebrand. Keep applicationId/package unchanged for update compatibility.
replace_once(P["build"], "versionCode = 304", "versionCode = 310", "versionCode 310")
replace_once(P["build"], 'versionName = "3.0.4"', 'versionName = "3.1.0"', "versionName 3.1.0")

for path in (P["strings_ar"], P["strings_en"]):
    text = read(path)
    if "Al-othmany Sender" not in text:
        text = text.replace("AL-thmany", "Al-othmany Sender")
        text = text.replace("AL-THMANY", "AL-OTHMANY SENDER")
        write(path, text)
        print(f"PATCHED: rebrand {path.relative_to(ROOT)}")

ar = read(P["strings_ar"])
for old, new in {
    '<string name="unified_toolbar_subtitle">الإصدار %1$s • تشغيل ذكي متعدد واتساب • 1000 رابط في الجولة</string>':
        '<string name="unified_toolbar_subtitle">الإصدار %1$s • تشغيل ذكي متعدد واتساب • استئناف تلقائي وثبات أعلى</string>',
    '<string name="unified_links_subtitle">ألصق أو استورد حتى 1000000 رابط قروب أو مجتمع، وسيُحذف التكرار تلقائيًا، مع معالجة حتى 1000 في كل تشغيل صريح.</string>':
        '<string name="unified_links_subtitle">ألصق أو استورد حتى 1000000 رابط قروب أو مجتمع. تُحذف التكرارات تلقائيًا ويستمر التشغيل مع حفظ التقدم حتى نهاية الجلسة.</string>',
    '<string name="safety_note">في الوضع العادي يفتح التطبيق الرابط وتضغط أنت زر الانضمام. عند تفعيل إضافة Accessibility يدويًا، تضغط الإضافة فقط «عرض المجموعة» أو «الانضمام إلى المجموعة» أو «طلب الانضمام» داخل واتساب، وتستوعب القائمة حتى ١٠٠٠٠٠٠ رابط، مع معالجة حتى ١٠٠٠ رابطًا في كل تشغيل صريح ثم التوقف حتى يطلب المستخدم المتابعة.</string>':
        '<string name="safety_note">يفتح المحرك الروابط التي أضفتها صراحة، ويتعامل فقط مع إجراءات الدعوة المعروفة داخل واتساب. يحفظ موضع التقدم، ويتوقف عند مغادرة واتساب، ولا يحاول تجاوز قيود واتساب.</string>',
}.items():
    if old in ar:
        ar = ar.replace(old, new, 1)
write(P["strings_ar"], ar)

en = read(P["strings_en"])
for old, new in {
    '<string name="unified_toolbar_subtitle">v%1$s • Adaptive Multi-WhatsApp Runtime • 1000 links per run</string>':
        '<string name="unified_toolbar_subtitle">v%1$s • Adaptive Multi-WhatsApp Runtime • stable auto-resume</string>',
    '<string name="unified_links_subtitle">Paste or import up to 1,000,000 group or community invitations. Duplicates are removed automatically; up to 1000 are processed per explicit run.</string>':
        '<string name="unified_links_subtitle">Paste or import up to 1,000,000 group or community invitations. Duplicates are removed automatically and progress is preserved through the full session.</string>',
    '<string name="safety_note">In normal mode, the app opens the link and you press Join. When the optional Accessibility add-on is enabled manually, it clicks only “View group”, “Join group”, or “Request to join” inside WhatsApp, can queue up to 1,000,000 links and processes no more than 1000 links per explicit run before stopping until the user explicitly continues.</string>':
        '<string name="safety_note">The engine opens only invitations you explicitly supplied, acts only on known invitation controls, preserves progress, pauses when you leave WhatsApp, and never attempts to bypass WhatsApp restrictions.</string>',
}.items():
    if old in en:
        en = en.replace(old, new, 1)
write(P["strings_en"], en)

# New dashboard action label in both languages.
for path, label_text in (
    (P["strings_ar"], "إضافة روابط"),
    (P["strings_en"], "Add links"),
):
    text = read(path)
    if 'name="sender_add_links"' not in text:
        text = text.replace(
            "</resources>",
            f'    <string name="sender_add_links">{label_text}</string>\n</resources>',
            1
        )
        write(path, text)

# 2) Dark Sender dashboard palette + light settings panel.
append_before_resources_close(P["colors"], '''
    <!-- Al-othmany Sender 3.1 -->
    <color name="sender_bg_deep">#031519</color>
    <color name="sender_bg">#061F25</color>
    <color name="sender_header">#082B33</color>
    <color name="sender_card">#0B2A31</color>
    <color name="sender_card_alt">#0E323A</color>
    <color name="sender_border">#174650</color>
    <color name="sender_accent">#10B7C1</color>
    <color name="sender_accent_dark">#078995</color>
    <color name="sender_accent_soft">#163E46</color>
    <color name="sender_text_primary">#F3FAFB</color>
    <color name="sender_text_secondary">#AFC6CB</color>
    <color name="sender_text_tertiary">#819CA3</color>
    <color name="sender_progress_track">#16383F</color>
    <color name="sender_settings_surface">#F7FAFA</color>
''', 'name="sender_bg_deep"', "Sender palette")

append_before_resources_close(P["themes"], '''
    <style name="Theme.AlOthmanySender.Main" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="colorPrimary">@color/sender_accent</item>
        <item name="colorOnPrimary">@color/white</item>
        <item name="colorSecondary">@color/nebula_mint</item>
        <item name="colorSurface">@color/sender_bg</item>
        <item name="colorOnSurface">@color/sender_text_primary</item>
        <item name="colorSurfaceContainer">@color/sender_card</item>
        <item name="colorOutline">@color/sender_border</item>
        <item name="android:statusBarColor">@color/sender_bg_deep</item>
        <item name="android:navigationBarColor">@color/sender_bg_deep</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:windowLightNavigationBar">false</item>
    </style>

    <style name="Theme.AlOthmanySender.SettingsPanel" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="colorPrimary">@color/brand_primary</item>
        <item name="colorOnPrimary">@color/white</item>
        <item name="colorSurface">@color/sender_settings_surface</item>
        <item name="colorOnSurface">@color/text_primary</item>
        <item name="colorSurfaceContainer">@color/white</item>
        <item name="colorOutline">@color/card_border</item>
        <item name="android:windowIsFloating">true</item>
        <item name="android:windowCloseOnTouchOutside">true</item>
        <item name="android:backgroundDimEnabled">true</item>
        <item name="android:windowDimAmount">0.46</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:windowBackground">@drawable/bg_sender_settings_panel</item>
    </style>
''', 'name="Theme.AlOthmanySender.Main"', "Sender themes")

theme_text = read(P["themes"])
theme_text = theme_text.replace('<item name="android:textSize">27sp</item>', '<item name="android:textSize">22sp</item>', 1)
theme_text = theme_text.replace('<item name="android:letterSpacing">0.02</item>', '<item name="android:letterSpacing">0.01</item>', 1)
write(P["themes"], theme_text)

resources = {
"bg_sender_dashboard.xml": '''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient android:angle="270" android:startColor="@color/sender_bg_deep"
        android:centerColor="@color/sender_bg" android:endColor="#08282F" />
</shape>
''',
"bg_sender_header.xml": '''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient android:angle="0" android:startColor="#052229"
        android:centerColor="@color/sender_header" android:endColor="#073842" />
    <corners android:bottomLeftRadius="28dp" android:bottomRightRadius="28dp" />
</shape>
''',
"bg_sender_inline.xml": '''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/sender_accent_soft" />
    <stroke android:width="1dp" android:color="@color/sender_border" />
    <corners android:radius="14dp" />
</shape>
''',
"bg_sender_pill.xml": '''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#123B43" />
    <stroke android:width="1dp" android:color="#1D5963" />
    <corners android:radius="999dp" />
</shape>
''',
"bg_sender_settings_panel.xml": '''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/sender_settings_surface" />
    <corners android:topLeftRadius="28dp" android:bottomLeftRadius="28dp"
        android:topRightRadius="18dp" android:bottomRightRadius="18dp" />
</shape>
''',
"ic_settings_sender.xml": '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M19.43,12.98c0.04,-0.32 0.07,-0.65 0.07,-0.98s-0.02,-0.66 -0.07,-0.98l2.11,-1.65c0.19,-0.15 0.24,-0.42 0.12,-0.64l-2,-3.46c-0.12,-0.22 -0.37,-0.31 -0.6,-0.22l-2.49,1c-0.52,-0.4 -1.08,-0.73 -1.69,-0.98L14.5,2.42C14.47,2.18 14.25,2 14,2h-4c-0.25,0 -0.46,0.18 -0.5,0.42L9.12,5.07c-0.61,0.25 -1.18,0.59 -1.69,0.98l-2.49,-1c-0.23,-0.08 -0.48,0 -0.6,0.22l-2,3.46c-0.13,0.22 -0.07,0.49 0.12,0.64l2.11,1.65c-0.04,0.32 -0.08,0.66 -0.08,0.98s0.03,0.66 0.08,0.98l-2.11,1.65c-0.19,0.15 -0.24,0.42 -0.12,0.64l2,3.46c0.12,0.22 0.37,0.31 0.6,0.22l2.49,-1c0.52,0.4 1.08,0.73 1.69,0.98l0.38,2.65c0.04,0.24 0.25,0.42 0.5,0.42h4c0.25,0 0.46,-0.18 0.5,-0.42l0.38,-2.65c0.61,-0.25 1.18,-0.58 1.69,-0.98l2.49,1c0.23,0.08 0.48,0 0.6,-0.22l2,-3.46c0.12,-0.22 0.07,-0.49 -0.12,-0.64l-2.11,-1.65zM12,15.5A3.5,3.5 0,1 1,12 8a3.5,3.5 0,0 1,0 7.5z" />
</vector>
''',
"ic_menu_sender.xml": '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M4,6h16v2H4zM4,11h16v2H4zM4,16h16v2H4z" />
</vector>
''',
}
for name, content in resources.items():
    write(ROOT / "app/src/main/res/drawable" / name, content)
print("PATCHED: Sender drawables/icons")

# 3) Dashboard restyle while preserving all current IDs.
main_xml = read(P["main_layout"])
for old, new in [
    ("@drawable/bg_app_aurora", "@drawable/bg_sender_dashboard"),
    ("@drawable/bg_hero_aurora", "@drawable/bg_sender_header"),
    ("@color/surface_card", "@color/sender_card"),
    ("@color/aurora_border", "@color/sender_border"),
    ("@color/nebula_primary_dark", "@color/sender_text_primary"),
    ("@color/text_primary", "@color/sender_text_primary"),
    ("@color/text_secondary", "@color/sender_text_secondary"),
    ("@color/text_tertiary", "@color/sender_text_tertiary"),
    ("@color/swift_status_surface", "@color/sender_card_alt"),
    ("@color/swift_control_surface", "@color/sender_card_alt"),
    ("@color/swift_border", "@color/sender_border"),
    ("@color/swift_progress_track", "@color/sender_progress_track"),
    ("@color/progress_track", "@color/sender_progress_track"),
    ("@drawable/bg_inline_info", "@drawable/bg_sender_inline"),
    ("@drawable/bg_count_pill", "@drawable/bg_sender_pill"),
    ("@drawable/bg_schedule_panel", "@drawable/bg_sender_inline"),
    ('android:text="0 / 1000"', 'android:text="0 / 1000000"'),
    ('app:cardCornerRadius="18dp"', 'app:cardCornerRadius="22dp"'),
    ('app:cardElevation="3dp"', 'app:cardElevation="0dp"'),
    ('app:cardElevation="2dp"', 'app:cardElevation="0dp"'),
    ('app:cardElevation="1dp"', 'app:cardElevation="0dp"'),
    ('android:layout_height="82dp"', 'android:layout_height="92dp"'),
]:
    main_xml = main_xml.replace(old, new)

if 'app:navigationIcon="@drawable/ic_menu_sender"' not in main_xml:
    main_xml = main_xml.replace(
        'app:menu="@menu/menu_main"',
        'app:menu="@menu/menu_main"\n            app:navigationIcon="@drawable/ic_menu_sender"\n            app:navigationIconTint="@color/white"',
        1
    )

if 'android:id="@+id/addLinksButton"' not in main_xml:
    anchor = '''                        <com.google.android.material.textfield.TextInputLayout
                            android:id="@+id/linksInputLayout"'''
    insertion = '''                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/addLinksButton"
                            android:layout_width="match_parent"
                            android:layout_height="52dp"
                            android:layout_marginTop="10dp"
                            android:text="@string/sender_add_links"
                            android:textColor="@color/white"
                            android:textSize="14sp"
                            android:textStyle="bold"
                            app:backgroundTint="@color/sender_accent_dark"
                            app:cornerRadius="17dp" />

                        <com.google.android.material.textfield.TextInputLayout
                            android:id="@+id/linksInputLayout"'''
    if anchor not in main_xml:
        die("main layout add-links anchor missing")
    main_xml = main_xml.replace(anchor, insertion, 1)
    main_xml = main_xml.replace(
        'android:id="@+id/linksInputLayout"\n                            style=',
        'android:id="@+id/linksInputLayout"\n                            android:visibility="gone"\n                            style=',
        1
    )
write(P["main_layout"], main_xml)
print("PATCHED: Sender dashboard layout")

# 4) Visible settings gear.
menu = read(P["menu"])
if 'android:icon="@drawable/ic_settings_sender"' not in menu:
    old = '''    <item
        android:id="@+id/action_settings"
        android:title="@string/action_settings"
        app:showAsAction="never" />'''
    new = '''    <item
        android:id="@+id/action_settings"
        android:icon="@drawable/ic_settings_sender"
        android:title="@string/action_settings"
        app:showAsAction="always" />'''
    if old not in menu:
        die("settings menu item anchor missing")
    menu = menu.replace(old, new, 1)
    write(P["menu"], menu)

main_kt = read(P["main_activity"])
if 'binding.toolbar.setNavigationOnClickListener' not in main_kt:
    anchor = '''        binding.toolbar.subtitle = getString(R.string.unified_toolbar_subtitle, displayVersion)

        binding.toolbar.setOnMenuItemClickListener'''
    new = '''        binding.toolbar.subtitle = getString(R.string.unified_toolbar_subtitle, displayVersion)
        binding.toolbar.setNavigationOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        binding.toolbar.setOnMenuItemClickListener'''
    if anchor not in main_kt:
        die("toolbar listener anchor missing")
    main_kt = main_kt.replace(anchor, new, 1)

if 'addLinksButton.setOnClickListener' not in main_kt:
    anchor = '''        pasteButton.setOnClickListener { pasteFromClipboard() }'''
    new = '''        addLinksButton.setOnClickListener {
            linksInputLayout.visibility = View.VISIBLE
            linksEditText.requestFocus()
        }
        pasteButton.setOnClickListener { pasteFromClipboard() }'''
    if anchor not in main_kt:
        die("paste button anchor missing")
    main_kt = main_kt.replace(anchor, new, 1)

old_listener = '''        linksEditText.doAfterTextChanged {
            val text = it?.toString().orEmpty()
            scheduleLinkAnalysis(text)
            if (text.isBlank()) lastAutomaticInputHash = null
            scheduleSmartAutoStart()
        }'''
new_listener = '''        linksEditText.doAfterTextChanged {
            val text = it?.toString().orEmpty()
            if (text.isNotBlank() && linksInputLayout.visibility != View.VISIBLE) {
                linksInputLayout.visibility = View.VISIBLE
            }
            scheduleLinkAnalysis(text)
            if (text.isBlank()) lastAutomaticInputHash = null
            scheduleSmartAutoStart()
        }'''
if new_listener not in main_kt:
    if old_listener not in main_kt:
        die("links listener anchor missing")
    main_kt = main_kt.replace(old_listener, new_listener, 1)

old_anim = '''        if (showSession && sessionCard.visibility != View.VISIBLE) {
            sessionCard.alpha = 0f
            sessionCard.visibility = View.VISIBLE
            sessionCard.animate().alpha(1f).setDuration(220L).start()
        } else if (!showSession) {'''
new_anim = '''        if (showSession && sessionCard.visibility != View.VISIBLE) {
            sessionCard.animate().cancel()
            sessionCard.alpha = 1f
            sessionCard.visibility = View.VISIBLE
        } else if (!showSession) {'''
if new_anim not in main_kt:
    if old_anim not in main_kt:
        die("session animation anchor missing")
    main_kt = main_kt.replace(old_anim, new_anim, 1)
write(P["main_activity"], main_kt)
print("PATCHED: dashboard interaction simplification")

# 5) Right-side floating settings activity and main dark theme.
manifest = read(P["manifest"])
old = '''        <activity
            android:name=".ui.SettingsActivity"
            android:exported="false" />'''
new = '''        <activity
            android:name=".ui.SettingsActivity"
            android:exported="false"
            android:theme="@style/Theme.AlOthmanySender.SettingsPanel" />'''
if new not in manifest:
    if old not in manifest:
        die("SettingsActivity manifest anchor missing")
    manifest = manifest.replace(old, new, 1)

old = '''        <activity
            android:name=".ui.MainActivity"
            android:exported="true">'''
new = '''        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:theme="@style/Theme.AlOthmanySender.Main">'''
if new not in manifest:
    if old not in manifest:
        die("MainActivity manifest anchor missing")
    manifest = manifest.replace(old, new, 1)
write(P["manifest"], manifest)

settings_xml = read(P["settings_layout"])
settings_xml = settings_xml.replace('android:background="?attr/colorSurface"', 'android:background="@drawable/bg_sender_settings_panel"', 1)
settings_xml = settings_xml.replace('android:background="@color/brand_primary_dark"', 'android:background="@android:color/transparent"', 1)
settings_xml = settings_xml.replace('app:navigationIconTint="@color/white"', 'app:navigationIconTint="@color/brand_primary_dark"', 1)
settings_xml = settings_xml.replace('app:titleTextColor="@color/white"', 'app:titleTextColor="@color/text_primary"', 1)
settings_xml = settings_xml.replace('app:cardCornerRadius="20dp"', 'app:cardCornerRadius="16dp"')
write(P["settings_layout"], settings_xml)

settings_kt = read(P["settings_activity"])
imports_old = '''import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast'''
imports_new = '''import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast'''
if imports_new not in settings_kt:
    if imports_old not in settings_kt:
        die("SettingsActivity imports anchor missing")
    settings_kt = settings_kt.replace(imports_old, imports_new, 1)

window_old = '''        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }'''
window_new = '''        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.46f)
        window.setGravity(Gravity.RIGHT)
        binding.root.post {
            val metrics = resources.displayMetrics
            val phoneWidth = (metrics.widthPixels * 0.92f).toInt()
            val maxPanelWidth = (560f * metrics.density).toInt()
            window.setLayout(minOf(phoneWidth, maxPanelWidth), ViewGroup.LayoutParams.MATCH_PARENT)
        }

        binding.toolbar.setNavigationOnClickListener { finish() }'''
if window_new not in settings_kt:
    if window_old not in settings_kt:
        die("SettingsActivity window anchor missing")
    settings_kt = settings_kt.replace(window_old, window_new, 1)
write(P["settings_activity"], settings_kt)
print("PATCHED: floating settings panel")

# 6) Update stale validator invariants and version checks.
validator = read(P["validator"])
pairs = [
    ('"versionCode 304": "versionCode = 304" in build,',
     '"versionCode 310": "versionCode = 310" in build,'),
    ('"versionName 3.0.4": \'versionName = "3.0.4"\' in build,',
     '"versionName 3.1.0": \'versionName = "3.1.0"\' in build,'),
    ('"Shizuku 2.7.4 parity cadence preset": all(token in shizuku_fast_policy for token in ["CLICK_THROTTLE_MS = 60L", "GESTURE_DURATION_MS = 16L", "RESULT_ANALYSIS_FALLBACK_MS = 72L", "ACTION_RETRY_AFTER_MS = 95L", "POST_JOIN_MIN_EVIDENCE_MS = 30L", "NON_LOADING_WATCHDOG_MS = 1_000L", "UNKNOWN_TIMEOUT_MS = 2_000L", "LOADING_TIMEOUT_MS = 20_000L", "USER_INSTANT_ADVANCE_SETTLE_MS = 0L"]),',
     '"Shizuku 3.1 reliable cadence preset": all(token in shizuku_fast_policy for token in ["CLICK_THROTTLE_MS = 60L", "GESTURE_DURATION_MS = 72L", "RESULT_ANALYSIS_FALLBACK_MS = 72L", "ACTION_RETRY_AFTER_MS = 95L", "POST_JOIN_MIN_EVIDENCE_MS = 30L", "NON_LOADING_WATCHDOG_MS = 1_000L", "UNKNOWN_TIMEOUT_MS = 2_000L", "LOADING_TIMEOUT_MS = 20_000L", "USER_INSTANT_ADVANCE_SETTLE_MS = 0L"]),'),
    ('"stable foreground departure guard": "OUTSIDE_TARGET_CONFIRM_MS = 320L" in foreground_policy and "scheduleAutoPauseOutsideTarget" in service,',
     '"fast foreground departure guard": "OUTSIDE_TARGET_CONFIRM_MS = 140L" in foreground_policy and "RECENT_TARGET_GRACE_MS = 120L" in foreground_policy and "scheduleAutoPauseOutsideTarget" in service,'),
]
for old, new in pairs:
    if new in validator:
        continue
    if old not in validator:
        if "Shizuku 3.0.4 reliable parity cadence preset" in validator and "Shizuku 3.1 reliable cadence preset" in new:
            validator = validator.replace("Shizuku 3.0.4 reliable parity cadence preset", "Shizuku 3.1 reliable cadence preset", 1)
            continue
        if "stable foreground departure guard" in old and "OUTSIDE_TARGET_CONFIRM_MS = 140L" in validator:
            continue
        die(f"validator anchor missing: {old[:80]}")
    validator = validator.replace(old, new, 1)

if '"Al-othmany Sender stable package"' not in validator:
    anchor = '''    "AL-thmany application id": 'applicationId = "com.althmany.groupmanager"' in build,
'''
    new = anchor + '''    "Al-othmany Sender stable package": 'applicationId = "com.althmany.groupmanager"' in build and "Theme.AlOthmanySender.Main" in manifest,
'''
    if anchor not in validator:
        die("validator application-id anchor missing")
    validator = validator.replace(anchor, new, 1)
write(P["validator"], validator)

print()
print("AL-OTHMANY SENDER 3.1.0 REDESIGN + STABILITY PATCH APPLIED")
print("Run: python3 scripts/validate_source.py")
