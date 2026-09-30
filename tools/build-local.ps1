# Folia APK 本机构建脚本
# 用法：powershell -ExecutionPolicy Bypass -File tools\build-local.ps1 [gradle任务...]（默认 assembleRelease）
# 说明：本机网络对 gradle.org CDN 存在 Java SSL 校验问题，故用本地 Gradle 发行版而非 gradlew wrapper；
#       依赖已全部缓存于 ~/.gradle/caches，可加 --offline 离线构建（首次除外）。
$ErrorActionPreference = 'Stop'

$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jdk-17.0.2"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

$gradle = "$env:USERPROFILE\.tools\gradle-7.5.1\bin\gradle.bat"
if (-not (Test-Path $gradle)) { throw "未找到本地 Gradle：$gradle" }
if (-not (Test-Path $env:JAVA_HOME)) { throw "未找到 JDK：$env:JAVA_HOME" }

$tasks = if ($args.Count -gt 0) { $args } else { @('assembleRelease') }
& $gradle @tasks --no-daemon
exit $LASTEXITCODE
