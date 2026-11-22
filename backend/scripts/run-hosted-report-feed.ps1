param(
    [Parameter(Mandatory)][ValidatePattern('^emulator-[0-9]+$')][string]$EmulatorSerial,
    [Parameter(Mandatory)][string]$CredentialFile,
    [Parameter(Mandatory)][ValidatePattern('^CP21-[0-9]{8}-[A-Z][0-9]{2}$')][string]$FixturePrefix,
    [Parameter(Mandatory)][guid]$FixtureGroupId,
    [Parameter(Mandatory)][string]$OutputFile,
    [switch]$Install
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'AndroidShell.ps1')
$taskRepo=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path.TrimEnd('\')
$taskPrivate=(Resolve-Path -LiteralPath $CredentialFile).Path
$taskOutput=[System.IO.Path]::GetFullPath($OutputFile)
foreach($taskPath in @($taskPrivate,$taskOutput)) {
    if($taskPath.StartsWith($taskRepo+'\',[StringComparison]::OrdinalIgnoreCase)) {
        throw 'Keep credentials and raw test output outside the repository.'
    }
}
if(Test-Path -LiteralPath $taskOutput){throw 'Choose an unused output archive; existing evidence is preserved.'}
try {$taskAccounts=Get-Content -Raw -LiteralPath $taskPrivate | ConvertFrom-Json}
catch {throw 'Could not read private demo credentials.'}
foreach($taskName in @('email','password','uid')) {
    if([string]::IsNullOrWhiteSpace($taskAccounts.citizen.$taskName)){throw 'Missing citizen demo credential field.'}
}
$null=[guid]::Parse($taskAccounts.citizen.uid)
$taskSdk=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{Join-Path $env:LOCALAPPDATA 'Android\Sdk'}
$taskAdb=Join-Path $taskSdk 'platform-tools\adb.exe'
if($Install) {
    foreach($taskApk in @('app\build\outputs\apk\debug\app-debug.apk','app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')) {
        & $taskAdb -s $EmulatorSerial install -r (Join-Path $taskRepo $taskApk)
        if($LASTEXITCODE -ne 0){throw 'Test APK installation failed; no app data was erased.'}
    }
}
# Reserved control files in the isolated test app; no business data or shared phone files.
& $taskAdb -s $EmulatorSerial shell 'run-as com.example.pinpoint rm -f files/cp21-report-stage.json files/cp21-report-oracle.json'
if($LASTEXITCODE -ne 0){throw 'Could not prepare emulator test controls.'}
$taskArguments=@('am','instrument','-w','-r','-e','class','com.example.pinpoint.HostedReportFeedTest',
    '-e','allowHostedReportChecks','true','-e','fixture_prefix',$FixturePrefix,
    '-e','fixture_group',$FixtureGroupId.ToString(),'-e','citizen_uid',$taskAccounts.citizen.uid,
    '-e','citizen_email',$taskAccounts.citizen.email,'-e','citizen_password',$taskAccounts.citizen.password,
    'com.example.pinpoint.test/androidx.test.runner.AndroidJUnitRunner')
$taskCommand=($taskArguments | ForEach-Object {ConvertTo-PinPointShellLiteral $_}) -join ' '
Write-Output 'Hosted report feed test started; host must supply the burst/order oracle when the private stage reaches ready-for-burst.'
$taskResult=Invoke-PinPointProcess -FilePath $taskAdb -Arguments @('-s',$EmulatorSerial,'shell',$taskCommand) -TimeoutMilliseconds 480000
$taskLog=$taskResult.Output.Replace($taskAccounts.citizen.password,'[redacted password]').Replace($taskAccounts.citizen.email,'[demo email]')
$taskStream=[IO.File]::Open($taskOutput,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
try {
    $taskBytes=[Text.Encoding]::UTF8.GetBytes($taskLog)
    $taskStream.Write($taskBytes,0,$taskBytes.Length)
} finally {$taskStream.Dispose()}
$taskLog
if($taskResult.TimedOut -or $taskResult.ExitCode -ne 0 -or $taskLog -notmatch 'OK \(1 test\)') {
    throw 'Hosted report feed test failed; retain the exact redacted archive and private phase evidence.'
}
$taskStageRaw=(& $taskAdb -s $EmulatorSerial shell 'run-as com.example.pinpoint cat files/cp21-report-stage.json') -join "`n"
if($LASTEXITCODE -ne 0){throw 'Missing hosted test completion evidence.'}
$taskStage=$taskStageRaw | ConvertFrom-Json
if($taskStage.phase -ne 'completed' -or $taskStage.prefix -ne $FixturePrefix -or !$taskStage.no_gaps_or_duplicates -or !$taskStage.filters_match) {
    throw 'Hosted completion evidence does not match this fixture batch.'
}
Write-Output 'Hosted report filter and pagination checks passed; ordinary phone navigation remains a separate check.'
