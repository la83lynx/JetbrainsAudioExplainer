# Enumerate installed Windows SAPI5 voices for the plugin (TtsService.probeSapiVoices).
# Emits one compact JSON array on stdout: [{name, culture, english, native, gender}, ...].
# `english`/`native` are the LANGUAGE names (neutral culture, so "Tamil" not "Tamil (India)") for menu
# labels + badges. System.Speech is .NET Framework, so this is run via Windows PowerShell 5.1.
$ErrorActionPreference = 'Stop'
# Emit UTF-8 so native language/voice names (தமிழ், 中文, …) survive — the plugin decodes stdout as UTF-8.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Speech
$out = foreach ($iv in (New-Object System.Speech.Synthesis.SpeechSynthesizer).GetInstalledVoices()) {
    if (-not $iv.Enabled) { continue }
    $v = $iv.VoiceInfo
    $c = $v.Culture
    $lang = if ($c.IsNeutralCulture -or [string]::IsNullOrEmpty($c.Parent.Name)) { $c } else { $c.Parent }
    [PSCustomObject]@{
        name    = $v.Name
        culture = $c.Name
        english = $lang.EnglishName
        native  = $lang.NativeName
        gender  = "$($v.Gender)"
    }
}
$out | ConvertTo-Json -Compress -Depth 3
