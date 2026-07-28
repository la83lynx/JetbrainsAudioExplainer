#!/usr/bin/env pwsh
# Helper for Claude to communicate with the Audio Explainer IDE plugin.
# Windows / cross-platform PowerShell port of explainer.sh (no bash / jq / curl dependency).
# Works in Windows PowerShell 5.1 and PowerShell 7+.
#
# Usage:
#   explainer.ps1 plan <json_file>      Send walkthrough plan from file
#   explainer.ps1 send <json_string>    Send raw JSON message
#   explainer.ps1 state                 Get current walkthrough state
#   explainer.ps1 wait-action [timeout] Wait for user action (default 30s)
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

# Invoke and return the raw response body, printing the error body on a non-2xx (mirrors `curl -s`,
# which never throws). Avoids -SkipHttpErrorCheck so this also runs on Windows PowerShell 5.1.
function Invoke-Api([string]$Method, [string]$Path, [string]$Body, [int]$TimeoutSec = 100) {
    try {
        $params = @{
            Method          = $Method
            Uri             = "$Base$Path"
            Headers         = $Headers
            UseBasicParsing = $true
            TimeoutSec      = $TimeoutSec
        }
        if ($PSBoundParameters.ContainsKey('Body') -and $Body) {
            $params['ContentType'] = 'application/json'
            $params['Body'] = $Body
        }
        return (Invoke-WebRequest @params).Content
    }
    catch {
        $resp = $_.Exception.Response
        if ($resp -and $resp.GetResponseStream) {
            try {
                $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
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
        Invoke-Api 'Post' '/api/message' (Get-Content -Raw $Arg)
    }
    'send' {
        if (-not $Arg) { [Console]::Error.WriteLine("Usage: explainer.ps1 send '<json>'"); exit 1 }
        Invoke-Api 'Post' '/api/message' $Arg
    }
    'state' {
        Invoke-Api 'Get' '/api/state'
    }
    'wait-action' {
        $timeout = if ($Arg) { [int]$Arg } else { 30 }
        Invoke-Api 'Get' "/api/actions?timeout=$timeout" $null ($timeout + 5)
    }
    'stop' {
        Invoke-Api 'Post' '/api/message' '{"type": "stop"}'
    }
    'save' {
        $body = if ($Arg) { "{`"name`": `"$Arg`"}" } else { '{}' }
        Invoke-Api 'Post' '/api/save' $body
    }
    'load' {
        if (-not $Arg) { [Console]::Error.WriteLine('Usage: explainer.ps1 load <name>'); exit 1 }
        Invoke-Api 'Post' '/api/load' "{`"name`": `"$Arg`"}"
    }
    'list' {
        Invoke-Api 'Get' '/api/walkthroughs'
    }
    default {
        [Console]::Error.WriteLine('Usage: explainer.ps1 {plan|send|state|wait-action|stop|save|load|list}')
        exit 1
    }
}
