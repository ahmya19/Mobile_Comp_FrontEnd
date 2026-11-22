function ConvertTo-PinPointShellLiteral {
    param([AllowEmptyString()][string]$TaskValue)
    $taskQuote=[string][char]39
    $taskEscapedQuote=$taskQuote+[char]34+$taskQuote+[char]34+$taskQuote
    return $taskQuote+$TaskValue.Replace($taskQuote,$taskEscapedQuote)+$taskQuote
}

# Windows PowerShell 5.1 (.NET Framework) has no ProcessStartInfo.ArgumentList; quote one argument by the
# CommandLineToArgvW rules so the same runners work there and in PowerShell 7.
function ConvertTo-PinPointWindowsArgument {
    param([AllowEmptyString()][string]$TaskValue)
    if($TaskValue.Length -gt 0 -and $TaskValue -notmatch '[\s"]'){return $TaskValue}
    $taskBuilder=[System.Text.StringBuilder]::new()
    [void]$taskBuilder.Append('"')
    $taskSlashes=0
    foreach($taskChar in $TaskValue.ToCharArray()) {
        if($taskChar -eq [char]92){$taskSlashes++;continue}
        if($taskChar -eq [char]34){[void]$taskBuilder.Append([string]::new([char]92,$taskSlashes*2+1)).Append('"')}
        else{[void]$taskBuilder.Append([string]::new([char]92,$taskSlashes)).Append($taskChar)}
        $taskSlashes=0
    }
    [void]$taskBuilder.Append([string]::new([char]92,$taskSlashes*2)).Append('"')
    return $taskBuilder.ToString()
}

function Invoke-PinPointProcess {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][ValidateRange(1,2147483647)][int]$TimeoutMilliseconds
    )
    $taskStart=[System.Diagnostics.ProcessStartInfo]::new()
    $taskStart.FileName=$FilePath
    $taskStart.UseShellExecute=$false
    $taskStart.CreateNoWindow=$true
    $taskStart.RedirectStandardOutput=$true
    $taskStart.RedirectStandardError=$true
    if($null -ne $taskStart.ArgumentList){foreach($taskArgument in $Arguments){$taskStart.ArgumentList.Add($taskArgument)}}
    else{$taskStart.Arguments=($Arguments | ForEach-Object {ConvertTo-PinPointWindowsArgument $_}) -join ' '}
    $taskProcess=[System.Diagnostics.Process]::new()
    $taskProcess.StartInfo=$taskStart
    try {
        $null=$taskProcess.Start()
        $taskOutput=$taskProcess.StandardOutput.ReadToEndAsync()
        $taskError=$taskProcess.StandardError.ReadToEndAsync()
        $taskTimedOut=!$taskProcess.WaitForExit($TimeoutMilliseconds)
        if($taskTimedOut){$taskProcess.Kill();$taskProcess.WaitForExit()}
        [pscustomobject]@{TimedOut=$taskTimedOut;ExitCode=$taskProcess.ExitCode;
            Output=$taskOutput.GetAwaiter().GetResult()+$taskError.GetAwaiter().GetResult()}
    } finally {$taskProcess.Dispose()}
}
