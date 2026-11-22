function ConvertTo-PinPointShellLiteral {
    param([AllowEmptyString()][string]$TaskValue)
    $taskQuote=[string][char]39
    $taskEscapedQuote=$taskQuote+[char]34+$taskQuote+[char]34+$taskQuote
    return $taskQuote+$TaskValue.Replace($taskQuote,$taskEscapedQuote)+$taskQuote
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
    foreach($taskArgument in $Arguments){$taskStart.ArgumentList.Add($taskArgument)}
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
