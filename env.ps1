# HyperDuo local toolchain environment
# Dot-source this at the start of any build/decompile shell:
#   . C:\code\HyperDuo\env.ps1

$env:HYPERDUO_ROOT = 'C:\code\HyperDuo'
# JDK 21, not 17: Miuix ships Java 21 (class file major 65) bytecode, and the Kotlin
# compiler refuses to inline a 21-targeted function into 17-targeted output. The
# nav entry points (rememberNavController, entry) are inline composables, so the
# build cannot go below 21 while miuix-nav is in the dependency list. javac has to
# be 21 as well: AGP rejects a Java/Kotlin target mismatch outright.
$env:JAVA_HOME    = 'C:\code\HyperDuo\.tools\jdk\jdk-21.0.12.1+1'
$env:ANDROID_HOME = 'C:\code\HyperDuo\.tools\sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = 'C:\code\HyperDuo\.tools\gradle-home'
$env:PATH = "$env:JAVA_HOME\bin;C:\code\HyperDuo\.tools\bin;C:\code\HyperDuo\.tools\bin\jadx\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

# Gradle (no system install; local copy under .tools)
$gradleHome = Get-ChildItem 'C:\code\HyperDuo\.tools\gradle' -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($gradleHome) {
    $env:GRADLE_HOME = $gradleHome.FullName
    $env:PATH = "$env:GRADLE_HOME\bin;$env:PATH"
}

$env:ADB = 'C:\Program Files\UotanToolbox\Bin\platform-tools\adb.exe'

function apktool { & "$env:JAVA_HOME\bin\java.exe" -jar 'C:\code\HyperDuo\.tools\bin\apktool.jar' @args }
function jadx    { & 'C:\code\HyperDuo\.tools\bin\jadx\bin\jadx.bat' @args }
function java21  { & "$env:JAVA_HOME\bin\java.exe" @args }
function gradle  { & "$env:GRADLE_HOME\bin\gradle.bat" @args }
function adb     { & $env:ADB @args }

Write-Host "[HyperDuo] JAVA_HOME=$env:JAVA_HOME" -ForegroundColor DarkCyan
Write-Host "[HyperDuo] ANDROID_HOME=$env:ANDROID_HOME" -ForegroundColor DarkCyan
if ($env:GRADLE_HOME) { Write-Host "[HyperDuo] GRADLE_HOME=$env:GRADLE_HOME" -ForegroundColor DarkCyan }
