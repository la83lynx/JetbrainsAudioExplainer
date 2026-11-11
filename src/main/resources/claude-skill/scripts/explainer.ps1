#!/usr/bin/env pwsh
# Helper for Claude to communicate with the Audio Explainer IDE plugin.
# Windows / cross-platform PowerShell port of explainer.sh (no bash / jq / curl dependency).
# Works in Windows PowerShell 5.1 and PowerShell 7+.
#
# Usage:
#   explainer.ps1 plan <json_file>      Send walkthrough plan from file
#   explainer.ps1 send <json_string>    Send raw JSON message
#   explainer.ps1 state                 Get current walkthrough state
#   explainer.ps1 stop                  Stop the walkthrough
#   explainer.ps1 save [name]           Save current walkthrough
#   explainer.ps1 load <name>           Load a saved walkthrough
#   explainer.ps1 list                  List saved walkthroughs

param(
    [Parameter(Position = 0)][string]$Command,
    [Parameter(Position = 1)][string]$Arg
)

$ErrorActionPreference = 'Stop'

$home_ = if ($env:HOME) { $env:HOME } else { $env:USERPROFILE }
$PortFile = Join-Path $home_ '.claude-explainer-port'
$TokenFile = Join-Path $home_ '.claude-explainer-token'

if (-not (Test-Path $PortFile)) {
    [Console]::Error.WriteLine('{"error": "Audio Explainer plugin not running (no port file)"}')
    exit 1
}
if (-not (Test-Path $TokenFile)) {
    [Console]::Error.WriteLine('{"error": "No auth token found"}')
    exit 1
}

$Port = (Get-Content -Raw $PortFile).Trim()
$Token = (Get-Content -Raw $TokenFile).Trim()
$Base = "http://127.0.0.1:$Port"
$Headers = @{ Authorization = "Bearer $Token" }

# Read a response as UTF-8 regardless of what the server declared. Without this the reply is decoded
# with the HTTP default (ISO-8859-1), which mangles any non-ASCII text the plugin sends back in
# `issues`.
function Read-Utf8Response($Response) {
    $stream = $Response.RawContentStream
    if ($stream) {
        $stream.Position = 0
        return [System.Text.Encoding]::UTF8.GetString($stream.ToArray())
    }
    return $Response.Content
}

# Invoke and return the raw response body, printing the error body on a non-2xx (mirrors `curl -s`,
# which never throws). Avoids -SkipHttpErrorCheck so this also runs on Windows PowerShell 5.1.
#
# $Body is BYTES, never a string. PowerShell encodes a string body with the HTTP default charset
# (ISO-8859-1) when the content type does not name one, so a UTF-8 plan round-trips through a
# single-byte code page on the way out: an em dash (E2 80 94) leaves as E2 3F 22, and that 0x22 is a
# double quote landing inside a JSON string, which breaks the document rather than just the word.
# Every character of a non-English walkthrough goes through this path.
function Invoke-Api([string]$Method, [string]$Path, $Body, [int]$TimeoutSec = 100) {
    try {
        $params = @{
            Method          = $Method
            Uri             = "$Base$Path"
            Headers         = $Headers
            UseBasicParsing = $true
            TimeoutSec      = $TimeoutSec
        }
        if ($null -ne $Body -and $Body.Length -gt 0) {
            # `[byte[]]$bytes` is load-bearing, and so is assigning inside the branches rather than
            # from an `if` expression: a script block that returns an array writes it to the output
            # stream one element at a time, so `$x = if (..) { $bytes }` collects it back as
            # [object[]]. Invoke-WebRequest then has no byte array to send and stringifies the
            # collection instead, putting "123 34 116 ..." on the wire. Declaring the type converts
            # it back whatever the pipeline did.
            [byte[]]$bytes = $null
            if ($Body -is [string]) { $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body) }
            else { $bytes = [byte[]]$Body }
            $params['ContentType'] = 'application/json; charset=utf-8'
            $params['Body'] = $bytes
        }
        return (Read-Utf8Response (Invoke-WebRequest @params))
    }
    catch {
        $resp = $_.Exception.Response
        if ($resp -and $resp.GetResponseStream) {
            try {
                $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
                return $reader.ReadToEnd()
            }
            catch { }
        }
        return "{`"error`": `"$($_.Exception.Message -replace '"', '\"')`"}"
    }
}

switch ($Command) {
    'plan' {
        if (-not $Arg) { [Console]::Error.WriteLine('Usage: explainer.ps1 plan <json_file>'); exit 1 }
        Invoke-Api 'Post' '/api/message' ([System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Arg).ProviderPath))
    }
    'send' {
        if (-not $Arg) { [Console]::Error.WriteLine("Usage: explainer.ps1 send '<json>'"); exit 1 }
        Invoke-Api 'Post' '/api/message' $Arg
    }
    'state' {
        Invoke-Api 'Get' '/api/state'
    }
    'stop' {
        Invoke-Api 'Post' '/api/message' '{"type": "stop"}'
    }
    'save' {
        $name = $Arg -replace '\\', '\\' -replace '"', '\"'
        $body = if ($Arg) { "{`"name`": `"$name`"}" } else { '{}' }
        Invoke-Api 'Post' '/api/save' $body
    }
    'load' {
        if (-not $Arg) { [Console]::Error.WriteLine('Usage: explainer.ps1 load <name>'); exit 1 }
        $name = $Arg -replace '\\', '\\' -replace '"', '\"'
        Invoke-Api 'Post' '/api/load' "{`"name`": `"$name`"}"
    }
    'list' {
        Invoke-Api 'Get' '/api/walkthroughs'
    }
    default {
        [Console]::Error.WriteLine('Usage: explainer.ps1 {plan|send|state|stop|save|load|list}')
        exit 1
    }
}
