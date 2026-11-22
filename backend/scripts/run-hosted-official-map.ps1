param(
    [Parameter(Mandatory)][ValidatePattern('^emulator-[0-9]+$')][string]$EmulatorSerial,
    [Parameter(Mandatory)][string]$CredentialFile,
    [Parameter(Mandatory)][string]$OracleFile,
    [Parameter(Mandatory)][string]$OutputFile,
    [switch]$Install
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'AndroidShell.ps1')
$taskRepo=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path.TrimEnd('\')
$taskPrivate=(Resolve-Path -LiteralPath $CredentialFile).Path
$taskOracle=(Resolve-Path -LiteralPath $OracleFile).Path
$taskOutput=[IO.Path]::GetFullPath($OutputFile)
foreach($taskPath in @($taskPrivate,$taskOracle,$taskOutput)) {
    if($taskPath.Equals($taskRepo,[StringComparison]::OrdinalIgnoreCase) -or
       $taskPath.StartsWith($taskRepo+'\',[StringComparison]::OrdinalIgnoreCase)) {
        throw 'Keep credentials, oracle and evidence outside the repository.'
    }
}
if(Test-Path -LiteralPath $taskOutput){throw 'Choose an unused evidence archive.'}
try {
    $taskAccounts=Get-Content -Raw -LiteralPath $taskPrivate | ConvertFrom-Json
    $taskExpected=Get-Content -Raw -LiteralPath $taskOracle | ConvertFrom-Json
} catch {throw 'Could not read the private accounts or oracle JSON.'}
foreach($taskField in @('email','password','uid')) {
    if([string]::IsNullOrWhiteSpace($taskAccounts.official.$taskField)){throw 'Missing official demo credential field.'}
}
if($taskExpected.schema -ne 1 -or $taskExpected.project_url -ne 'https://jaysxjjdvaitgrbqjlhe.supabase.co' -or
   $taskExpected.official_uid -ne $taskAccounts.official.uid){throw 'Independent oracle identity/project mismatch.'}
$taskSdk=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{Join-Path $env:LOCALAPPDATA 'Android\Sdk'}
$taskAdb=Join-Path $taskSdk 'platform-tools\adb.exe'
if($Install) {
    foreach($taskApk in @('app\build\outputs\apk\debug\app-debug.apk','app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')) {
        & $taskAdb -s $EmulatorSerial install -r (Join-Path $taskRepo $taskApk)
        if($LASTEXITCODE -ne 0){throw 'Emulator installation failed; no data was cleared.'}
    }
}
& $taskAdb -s $EmulatorSerial push $taskOracle '/data/local/tmp/pinpoint-official-map-oracle.json' | Out-Null
if($LASTEXITCODE -ne 0){throw 'Could not transfer private oracle to emulator.'}
# One command string passed through the portable quoting helper: Windows PowerShell 5.1 drops embedded double quotes
# when calling a native command directly, which previously skipped this step silently.
$taskPrepare=Invoke-PinPointProcess -FilePath $taskAdb -TimeoutMilliseconds 60000 -Arguments @('-s',$EmulatorSerial,'shell',
    "run-as com.example.pinpoint sh -c 'mkdir -p files; cat /data/local/tmp/pinpoint-official-map-oracle.json > files/official-map-oracle.json; rm -f files/official-map-result.json'")
if($taskPrepare.TimedOut -or $taskPrepare.ExitCode -ne 0 -or $taskPrepare.Output -match "can't create|No such file|Needs 1 argument"){throw 'Could not prepare reserved official-map control files.'}
$taskDeviceOracle=(& $taskAdb -s $EmulatorSerial shell 'run-as com.example.pinpoint cat files/official-map-oracle.json') -join "`n"
if(($taskDeviceOracle | ConvertFrom-Json | ConvertTo-Json -Compress -Depth 20) -ne ($taskExpected | ConvertTo-Json -Compress -Depth 20)){throw 'The oracle on the emulator does not match the supplied oracle.'}
$taskStale=(& $taskAdb -s $EmulatorSerial shell 'run-as com.example.pinpoint ls files/official-map-result.json 2>&1') -join ' '
if($taskStale -notmatch 'No such file'){throw 'A stale official-map result file remains on the emulator.'}
& $taskAdb -s $EmulatorSerial shell rm -f /data/local/tmp/pinpoint-official-map-oracle.json
$taskArguments=@('am','instrument','-w','-r','-e','class','com.example.pinpoint.HostedOfficialMapTest',
    '-e','allowOfficialMapChecks','true','-e','official_email',$taskAccounts.official.email,
    '-e','official_password',$taskAccounts.official.password,
    'com.example.pinpoint.test/androidx.test.runner.AndroidJUnitRunner')
$taskCommand=($taskArguments | ForEach-Object {ConvertTo-PinPointShellLiteral $_}) -join ' '
$taskResult=Invoke-PinPointProcess -FilePath $taskAdb -Arguments @('-s',$EmulatorSerial,'shell',$taskCommand) -TimeoutMilliseconds 480000
$taskLog=$taskResult.Output.Replace($taskAccounts.official.password,'[redacted password]').Replace($taskAccounts.official.email,'[demo email]')
$taskStream=[IO.File]::Open($taskOutput,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
try {$taskBytes=[Text.Encoding]::UTF8.GetBytes($taskLog);$taskStream.Write($taskBytes,0,$taskBytes.Length)} finally {$taskStream.Dispose()}
if($taskResult.TimedOut -or $taskResult.ExitCode -ne 0 -or $taskLog -notmatch 'OK \(1 test\)') {
    throw 'Official hosted test failed; inspect the private redacted archive.'
}
$taskCompletion=(& $taskAdb -s $EmulatorSerial shell 'run-as com.example.pinpoint cat files/official-map-result.json') -join "`n"
if($LASTEXITCODE -ne 0){throw 'Missing official-map completion evidence.'}
$taskState=$taskCompletion | ConvertFrom-Json
if($taskState.phase -ne 'passed' -or !$taskState.official_role -or !$taskState.session_cleanup_complete -or
   !$taskState.assigned_controls_visible -or !$taskState.other_barangay_controls_hidden){throw 'Official completion evidence is incomplete.'}
Write-Output 'Official hosted map checks passed; verify the independent hosted baseline separately.'
