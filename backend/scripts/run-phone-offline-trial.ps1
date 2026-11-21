#Requires -Version 7.0
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$DeviceSerial,
    [Parameter(Mandatory)][ValidateSet('setup','enqueue','restart','observe','verify-deviation','verify-recovery')][string]$Phase,
    [string]$CredentialFile,
    [ValidateRange(4,10)][int]$Run=4,
    [ValidateSet('WiFi','Cellular')][string]$Transport='WiFi',
    [switch]$AllowPhysicalDevice,
    [switch]$KeepAppVisible,
    [switch]$InstallTestApk
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'AndroidShell.ps1')
$taskSdk=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{Join-Path $env:LOCALAPPDATA 'Android/Sdk'}
$taskAdb=Join-Path $taskSdk 'platform-tools/adb.exe'
$taskRepo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if($DeviceSerial -notmatch '^emulator-[0-9]+$' -and !$AllowPhysicalDevice){throw 'Phone trials require explicit -AllowPhysicalDevice and the owner approval.'}
$taskListed=(& $taskAdb devices) -join "`n"
if($taskListed -notmatch "(?m)^$([regex]::Escape($DeviceSerial))\s+device\s*$"){throw 'The selected device is not connected and authorized.'}
try {$taskCredentials=Get-Content -Raw -LiteralPath $CredentialFile | ConvertFrom-Json}
catch {throw 'Provide the private demo credential file outside Git. Its contents are not echoed.'}
if(!$taskCredentials.citizen.uid -or !$taskCredentials.citizen.email -or !$taskCredentials.citizen.password){throw 'Citizen demo credentials are incomplete.'}
if($InstallTestApk){
    & $taskAdb -s $DeviceSerial install -r (Join-Path $taskRepo 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    if($LASTEXITCODE -ne 0){throw 'Test APK installation failed; no uninstall or data clear was attempted.'}
}
if($Phase -eq 'restart'){
    & $taskAdb -s $DeviceSerial shell am force-stop com.example.pinpoint
    if($LASTEXITCODE -ne 0){throw 'PinPoint force-stop failed.'}
}
$taskTestPhase=if($Phase -eq 'restart'){'inspect'}else{$Phase}
$taskArguments=@('am','instrument','-w','-r','-e','class','com.example.pinpoint.PhoneOfflineTrialTest',
    '-e','cp25_phase',$taskTestPhase,'-e','owner_id',$taskCredentials.citizen.uid,'-e','run',"$Run",'-e','transport',$Transport)
if($KeepAppVisible){$taskArguments+=@('-e','keep_visible','true')}
if($Phase -eq 'setup'){
    $taskArguments+=@('-e','email',$taskCredentials.citizen.email,'-e','password',$taskCredentials.citizen.password)
}
$taskArguments+='com.example.pinpoint.test/androidx.test.runner.AndroidJUnitRunner'
$taskCommand=($taskArguments | ForEach-Object {ConvertTo-PinPointShellLiteral $_}) -join ' '
$taskLimit=if($Phase -eq 'observe'){330000}else{90000}
$taskExecution=Invoke-PinPointProcess -FilePath $taskAdb -Arguments @('-s',$DeviceSerial,'shell',$taskCommand) -TimeoutMilliseconds $taskLimit
$taskResult=$taskExecution.Output
$taskExit=$taskExecution.ExitCode
foreach($taskRole in @('citizen','official')){
    if($taskCredentials.$taskRole.password){$taskResult=$taskResult.Replace($taskCredentials.$taskRole.password,'[redacted password]')}
    if($taskCredentials.$taskRole.email){$taskResult=$taskResult.Replace($taskCredentials.$taskRole.email,'[demo email]')}
}
$taskResult
if($taskExecution.TimedOut){
    # A device-side timer cannot enforce a deadline if its process is suspended.
    & $taskAdb -s $DeviceSerial shell am force-stop com.example.pinpoint
    throw 'Host deadline exceeded. Only PinPoint was stopped; its saved queue/evidence remain. Reopen the app and verify the same report before another trial. Do not record a timing pass.'
}
if($taskExit -ne 0 -or $taskResult -notmatch 'OK \(1 test\)'){throw "CP-25 phase '$Phase' failed. Keep its exact redacted output; do not count this trial as a pass."}
