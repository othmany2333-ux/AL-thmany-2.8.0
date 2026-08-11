$ErrorActionPreference = "Stop"

if (-not (Get-Command gradle -ErrorAction SilentlyContinue)) {
    Write-Error "Gradle غير مثبت. ثبّت Gradle 8.13 أو افتح المشروع في Android Studio أولًا."
}

gradle wrapper --gradle-version 8.13 --distribution-type bin
Write-Host "تم إنشاء Gradle Wrapper. ارفع gradlew وgradlew.bat ومجلد gradle/wrapper إلى GitHub."
