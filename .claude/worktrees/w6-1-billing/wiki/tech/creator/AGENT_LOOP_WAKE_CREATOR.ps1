# AGENT_LOOP_WAKE_CREATOR - 30min fallback heartbeat for Arjun orchestrator loop
# Sentinel: do not start a second instance if PID file is alive.
#
# FIX (2026-07-09, Swapnil): the previous version used Start-Job, whose child
# process is owned by the calling PowerShell/Cursor terminal session. When that
# session/terminal closed, the job died silently, the PID file went stale, and
# the loop was reported "not started" even though nothing ever alerted anyone.
# This version uses Start-Process with a hidden, fully detached child process
# so the heartbeat survives the parent shell closing.
#
# NOTE: Inside a live Cursor session, prefer the monitored background Shell
# loop (sentinel `AGENT_LOOP_WAKE_CREATOR {"prompt":...}` piped through
# notify_on_output) — that is what actually wakes the agent. This script is
# the durable OS-level fallback for when no Cursor session is attached, and a
# manual audit trail via the log file.

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$PidFile = Join-Path $ScriptDir "AGENT_LOOP_WAKE_CREATOR.pid"
$LogFile = Join-Path $ScriptDir "AGENT_LOOP_WAKE_CREATOR.log"
$IntervalMinutes = 30

function Test-LoopAlive {
    if (-not (Test-Path $PidFile)) { return $false }
    $stored = Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $stored) { return $false }
    return $null -ne (Get-Process -Id ([int]$stored) -ErrorAction SilentlyContinue)
}

if (Test-LoopAlive) {
    $existing = Get-Content $PidFile | Select-Object -First 1
    Write-Output "AGENT_LOOP_WAKE_CREATOR already running (PID $existing). Exiting."
    exit 0
}

$innerScript = @"
`$LogPath = '$LogFile'
`$Minutes = $IntervalMinutes
while (`$true) {
    Start-Sleep -Seconds (`$Minutes * 60)
    `$stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    Add-Content -Path `$LogPath -Value "`$stamp HEARTBEAT wake Arjun CREATOR loop tick"
}
"@

$encodedBytes = [System.Text.Encoding]::Unicode.GetBytes($innerScript)
$encodedCommand = [Convert]::ToBase64String($encodedBytes)

$proc = Start-Process -FilePath "powershell.exe" `
    -ArgumentList @("-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", $encodedCommand) `
    -WindowStyle Hidden -PassThru

Set-Content -Path $PidFile -Value $proc.Id

Start-Sleep -Milliseconds 500
if (Test-LoopAlive) {
    Write-Output "AGENT_LOOP_WAKE_CREATOR armed (PID $($proc.Id), detached, ${IntervalMinutes}min heartbeat)"
} else {
    Write-Output "AGENT_LOOP_WAKE_CREATOR failed to start detached process."
}
