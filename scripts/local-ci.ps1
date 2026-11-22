[CmdletBinding()]
param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$InitScript,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
if (-not $SdkRoot -or -not (Test-Path -LiteralPath (Join-Path $SdkRoot 'platforms/android-36'))) {
    throw 'Set ANDROID_HOME or pass -SdkRoot with Android SDK Platform 36 installed.'
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Set JAVA_HOME or pass -JavaHome with a JDK 17 installation.'
}

$oldSdk = $env:ANDROID_HOME
$oldJava = $env:JAVA_HOME
$env:ANDROID_HOME = $SdkRoot
$env:JAVA_HOME = $JavaHome
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $tasks = @('--console=plain', ':core:testDebugUnitTest', ':mobile:lintDebug', ':wear:lintDebug', ':mobile:assembleDebug', ':wear:assembleDebug')
    if ($InitScript) { $tasks = @('--init-script', $InitScript) + $tasks }
    if ($Offline) { $tasks = @('--offline') + $tasks }
    & .\gradlew.bat @tasks
    if ($LASTEXITCODE) { throw 'Android local CI failed' }
} finally {
    Pop-Location
    $env:ANDROID_HOME = $oldSdk
    $env:JAVA_HOME = $oldJava
}
