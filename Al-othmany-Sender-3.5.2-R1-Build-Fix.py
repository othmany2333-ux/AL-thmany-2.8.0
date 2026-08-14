#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Al-othmany Sender 3.5.2 R1 - build/validator compatibility hotfix.

Apply from repository root after the 3.5.2 merged update.
Fixes:
- nullable MainUiState.snapshot access introduced by the exact dashboard sync method
- validate_source ViewBinding false positive for Kotlin ::binding.isInitialized
- stale 3.3/3.4.1 validator tokens after the 3.5.1 Shizuku continuity hotfix
"""
from pathlib import Path

ROOT = Path.cwd()
if not (ROOT / "app").is_dir():
    raise SystemExit("ERROR: run from repository root")

changed = []

def read(rel: str) -> str:
    p = ROOT / rel
    if not p.exists():
        raise SystemExit(f"ERROR: missing {rel}")
    return p.read_text(encoding="utf-8")

def write(rel: str, text: str) -> None:
    p = ROOT / rel
    old = p.read_text(encoding="utf-8")
    if old != text:
        p.write_text(text, encoding="utf-8")
        changed.append(rel)

# 1) Kotlin compile fix: MainUiState.snapshot is nullable.
main_rel = "app/src/main/java/com/althmany/groupmanager/ui/MainActivity.kt"
s = read(main_rel)
old = "        val total = viewModel.state.value.snapshot.stats.total.coerceAtLeast(1)"
new = "        val total = (viewModel.state.value.snapshot?.stats?.total ?: 0).coerceAtLeast(1)"
if old in s:
    s = s.replace(old, new, 1)
elif new not in s:
    raise SystemExit("ERROR: exact dashboard total/snapshot anchor not found")
write(main_rel, s)

# 2) Validator: ::binding.isInitialized is Kotlin lateinit metadata, not a ViewBinding field.
validator_rel = "scripts/validate_source.py"
v = read(validator_rel)
old = '    for value in sorted(binding_refs - layout_ids - {"root"}):'
new = '    for value in sorted(binding_refs - layout_ids - {"root", "isInitialized"}):'
if old in v:
    v = v.replace(old, new, 1)
elif new not in v:
    raise SystemExit("ERROR: ViewBinding validator anchor not found")

# 3) Validator token refresh after 3.5.1 continuity semantics.
old = '''    "3.3 evidence counter repair": "visualExpectedAction" in shizuku_service and
        "not counted as a false request" in shizuku_service and
        "Fast watchdog recovered a verified exact-user WhatsApp conversation after Join" in shizuku_service,'''
new = '''    "3.3 evidence counter repair": "visualExpectedAction" in shizuku_service and
        "SHIZUKU_VISUAL_AMBIGUOUS_NO_BACK" in shizuku_service and
        "Fast watchdog recovered a verified exact-user WhatsApp conversation after Join" in shizuku_service,'''
if old in v:
    v = v.replace(old, new, 1)
elif new not in v:
    raise SystemExit("ERROR: 3.3 evidence validator anchor not found")

old = '''    "3.4.1 cooldown is non-recursive": "realCommandKill: Boolean = false" in shizuku_service and
        "mode=COMMAND_COOLDOWN; command dump suppressed" in shizuku_service,'''
new = '''    "3.4.1 cooldown is non-recursive": "realCommandKill: Boolean = false" in shizuku_service and
        "No UI dump ran during cooldown" in shizuku_service,'''
if old in v:
    v = v.replace(old, new, 1)
elif new not in v:
    raise SystemExit("ERROR: 3.4.1 cooldown validator anchor not found")

write(validator_rel, v)

# Sanity checks.
main = read(main_rel)
validator = read(validator_rel)
required = [
    ("nullable snapshot fixed", "snapshot?.stats?.total ?: 0" in main),
    ("isInitialized validator exception", '{"root", "isInitialized"}' in validator),
    ("new ambiguous-no-back validator token", '"SHIZUKU_VISUAL_AMBIGUOUS_NO_BACK" in shizuku_service' in validator),
    ("new cooldown validator token", '"No UI dump ran during cooldown" in shizuku_service' in validator),
]
failed = [name for name, ok in required if not ok]
if failed:
    raise SystemExit("ERROR: R1 sanity failed: " + ", ".join(failed))

print("=" * 68)
print("✅ AL-OTHMANY SENDER 3.5.2 R1 BUILD FIX APPLIED")
print("=" * 68)
for rel in changed:
    print(" -", rel)
print()
print("Next:")
print("  python3 scripts/validate_source.py")
print("  git diff --check")
print("  gradle --no-daemon --no-configuration-cache :app:assembleDebug --console=plain")
