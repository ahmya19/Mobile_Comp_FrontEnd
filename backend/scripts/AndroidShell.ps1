function ConvertTo-PinPointShellLiteral {
    param([AllowEmptyString()][string]$TaskValue)
    $taskQuote=[string][char]39
    $taskEscapedQuote=$taskQuote+[char]34+$taskQuote+[char]34+$taskQuote
    return $taskQuote+$TaskValue.Replace($taskQuote,$taskEscapedQuote)+$taskQuote
}
