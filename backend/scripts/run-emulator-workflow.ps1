param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._:-]+$')][Alias('DeviceSerial')][string]$EmulatorSerial,
    [Parameter(Mandatory)][string]$CredentialFile,
    [ValidateSet('full','citizen','official','review')][string]$Phase='full',
    [switch]$Install,
    [switch]$AllowPhysicalDevice
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'AndroidShell.ps1')
$taskSdk=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{Join-Path $env:LOCALAPPDATA 'Android\Sdk'}
$taskAdb=Join-Path $taskSdk 'platform-tools\adb.exe'
if(!(Test-Path -LiteralPath $taskAdb)){throw 'Set ANDROID_HOME to the Android SDK directory.'}
$taskRepo=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if($EmulatorSerial -notmatch '^emulator-[0-9]+$'){
    if(!$AllowPhysicalDevice){throw 'Physical devices need -AllowPhysicalDevice. The run signs the test accounts in/out and stubs the photo picker.'}
    $taskListed=(& $taskAdb devices) -join "`n"
    if($taskListed -notmatch "(?m)^$([regex]::Escape($EmulatorSerial))\s+device\s*$"){throw "Device $EmulatorSerial is not connected and authorized."}
}
try {$taskCredentials=Get-Content -Raw -LiteralPath $CredentialFile | ConvertFrom-Json}
catch {throw 'Could not read the private demo credential JSON. Check its path and syntax locally.'}
foreach($taskRole in @('citizen','official')) {
    if([string]::IsNullOrWhiteSpace($taskCredentials.$taskRole.email) -or [string]::IsNullOrWhiteSpace($taskCredentials.$taskRole.password)){throw "Missing $taskRole demo credentials"}
}
if($Install) {
    foreach($taskApk in @('app\build\outputs\apk\debug\app-debug.apk','app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')) {
        $taskApkPath=Join-Path $taskRepo $taskApk
        if(!(Test-Path -LiteralPath $taskApkPath)){throw 'Build the debug and Android test APKs first.'}
        & $taskAdb -s $EmulatorSerial install -r $taskApkPath
        if($LASTEXITCODE -ne 0){throw 'Install failed. No app was uninstalled or data erased.'}
    }
}
$taskPhases=if($Phase -eq 'full'){@('citizen','official','review')}else{@($Phase)}
foreach($taskPhase in $taskPhases) {
    Write-Output "CP-16 phase: $taskPhase"
    $taskArguments=@('am','instrument','-w','-r','-e','class','com.example.pinpoint.WorkflowRehearsalTest','-e','phase',$taskPhase)
    foreach($taskRole in @('citizen','official')) {
        $taskArguments+=@('-e',"${taskRole}_email",$taskCredentials.$taskRole.email,'-e',"${taskRole}_password",$taskCredentials.$taskRole.password)
    }
    $taskArguments+='com.example.pinpoint.test/androidx.test.runner.AndroidJUnitRunner'
    $taskShellCommand=($taskArguments | ForEach-Object {ConvertTo-PinPointShellLiteral $_}) -join ' '
    $taskResult=(& $taskAdb -s $EmulatorSerial shell $taskShellCommand) -join "`n"
    $taskExit=$LASTEXITCODE
    foreach($taskRole in @('citizen','official')) {
        $taskResult=$taskResult.Replace($taskCredentials.$taskRole.password,'[redacted password]')
        $taskResult=$taskResult.Replace($taskCredentials.$taskRole.email,'[demo email]')
    }
    $taskResult
    if($taskExit -ne 0 -or $taskResult -notmatch 'OK \(1 test\)'){throw "Emulator workflow phase '$taskPhase' failed. Use the exact redacted test error to diagnose it."}
}
