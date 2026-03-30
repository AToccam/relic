$ErrorActionPreference = "Stop"

$noPause = $false
foreach ($a in $args) {
  if ($a -ieq "--no-pause" -or $a -ieq "-NoPause") {
    $noPause = $true
  }
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$gatewayDir = Join-Path $scriptDir "relic-gateway"
$coreDir = Join-Path $scriptDir "relic-core"
$faceDir = Join-Path $scriptDir "relic-face"

$missingServices = New-Object System.Collections.Generic.List[string]
$startedServices = New-Object System.Collections.Generic.List[string]
$startAllowed = $true

$pidOllama = $null
$pidOpenClaw = $null
$pidBackend = $null
$pidFrontend = $null

function Test-ListeningPort([int]$port) {
  $item = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -eq $port } |
    Select-Object -First 1
  return ($null -ne $item)
}

function Wait-PortUp([int]$port, [int]$seconds) {
  for ($i = 0; $i -lt $seconds; $i++) {
    if (Test-ListeningPort $port) { return $true }
    Start-Sleep -Seconds 1
  }
  return $false
}

function Add-Missing([string]$name) {
  $script:missingServices.Add($name)
  $script:startAllowed = $false
  Write-Host "- Install check: MISSING"
  Write-Host "- Startup has been disabled for this and remaining steps."
}

function Add-Started([string]$name) {
  $script:startedServices.Add($name)
}

function Start-ServiceWindow([string]$workDir, [string]$title, [string]$command) {
  $cmdLine = "title $title && $command"
  $p = Start-Process -FilePath "cmd.exe" -WorkingDirectory $workDir -ArgumentList @("/k", $cmdLine) -PassThru
  return $p.Id
}

function Stop-Started([string]$name, [Nullable[int]]$pid) {
  if ($null -eq $pid) {
    Write-Host "- $name was not started by this script."
    return
  }
  try {
    taskkill /PID $pid /T /F | Out-Null
    Write-Host "- $name stopped (PID $pid)."
  } catch {
    Write-Host "- $name (PID $pid) stop failed or process already exited."
  }
}

Write-Host "============================================================"
Write-Host "Relic quick start sequence:"
Write-Host "  1) Ollama  2) OpenClaw  3) relic-core  4) relic-face"
Write-Host ""
Write-Host "Note:"
Write-Host "  - Main window: orchestrator and summary."
Write-Host "  - Service windows: each service runs in its own terminal."
Write-Host "============================================================"
Write-Host ""

# 1) Ollama
Write-Host "[1/4] Ollama"
$hasOllama = $null -ne (Get-Command ollama -ErrorAction SilentlyContinue)
if (-not $hasOllama) {
  Add-Missing "Ollama"
} else {
  Write-Host "- Install check: OK"
  if (-not $startAllowed) {
    Write-Host "- Startup skipped because missing services already exist."
  } elseif (Test-ListeningPort 11434) {
    Write-Host "- Already running on port 11434."
  } else {
    $pidOllama = Start-ServiceWindow -workDir $scriptDir -title "Relic Ollama" -command "ollama serve"
    if (Wait-PortUp -port 11434 -seconds 15) {
      Add-Started "Ollama"
      Write-Host "- Started and listening on port 11434."
    } else {
      Write-Host "- Start triggered but port 11434 not ready in time."
    }
  }
}

# 2) OpenClaw
Write-Host "[2/4] OpenClaw"
$hasNpx = $null -ne (Get-Command npx -ErrorAction SilentlyContinue)
$hasOpenClaw = Test-Path (Join-Path $gatewayDir "node_modules\openclaw\package.json")
if (-not $hasOpenClaw -and $hasNpx) {
  try {
    Push-Location $gatewayDir
    & npx --no-install openclaw --version *> $null
    $hasOpenClaw = ($LASTEXITCODE -eq 0)
  } catch {
    $hasOpenClaw = $false
  } finally {
    Pop-Location
  }
}
if (-not $hasNpx -or -not $hasOpenClaw) {
  Add-Missing "OpenClaw"
} else {
  Write-Host "- Install check: OK"
  if (-not $startAllowed) {
    Write-Host "- Startup skipped because missing services already exist."
  } elseif (Test-ListeningPort 18789) {
    Write-Host "- Already running on port 18789."
  } else {
    $pidOpenClaw = Start-ServiceWindow -workDir $gatewayDir -title "Relic OpenClaw" -command "npx openclaw gateway --port 18789 --verbose"
    if (Wait-PortUp -port 18789 -seconds 20) {
      Add-Started "OpenClaw"
      Write-Host "- Started and listening on port 18789."
    } else {
      Write-Host "- Start triggered but port 18789 not ready in time."
    }
  }
}

# 3) relic-core
Write-Host "[3/4] relic-core"
$hasJava = $null -ne (Get-Command java -ErrorAction SilentlyContinue)
$hasMvn = $null -ne (Get-Command mvn -ErrorAction SilentlyContinue)
$hasPom = Test-Path (Join-Path $coreDir "pom.xml")
if (-not $hasJava -or -not $hasMvn -or -not $hasPom) {
  Add-Missing "relic-core"
} else {
  Write-Host "- Install check: OK"
  if (-not $startAllowed) {
    Write-Host "- Startup skipped because missing services already exist."
  } elseif (Test-ListeningPort 8082) {
    Write-Host "- Already running on port 8082."
  } else {
    $pidBackend = Start-ServiceWindow -workDir $coreDir -title "Relic Core" -command "mvn spring-boot:run"
    if (Wait-PortUp -port 8082 -seconds 90) {
      Add-Started "relic-core"
      Write-Host "- Started and listening on port 8082."
    } else {
      Write-Host "- Start triggered but port 8082 not ready in time."
    }
  }
}

# 4) relic-face
Write-Host "[4/4] relic-face"
$hasNode = $null -ne (Get-Command node -ErrorAction SilentlyContinue)
$hasNpm = $null -ne (Get-Command npm -ErrorAction SilentlyContinue)
$hasFacePkg = Test-Path (Join-Path $faceDir "package.json")
$hasFaceModules = Test-Path (Join-Path $faceDir "node_modules")
if (-not $hasNode -or -not $hasNpm -or -not $hasFacePkg -or -not $hasFaceModules) {
  Add-Missing "relic-face"
} else {
  Write-Host "- Install check: OK"
  if (-not $startAllowed) {
    Write-Host "- Startup skipped because missing services already exist."
  } elseif (Test-ListeningPort 5173) {
    Write-Host "- Already running on port 5173."
  } else {
    $pidFrontend = Start-ServiceWindow -workDir $faceDir -title "Relic Face" -command "npm run dev"
    if (Wait-PortUp -port 5173 -seconds 30) {
      Add-Started "relic-face"
      Write-Host "- Started and listening on port 5173."
    } else {
      Write-Host "- Start triggered but port 5173 not ready in time."
    }
  }
}

Write-Host ""
Write-Host "===================== Summary ====================="
if ($missingServices.Count -gt 0) {
  Write-Host ("Missing services: " + ($missingServices -join ", "))
  if ($startedServices.Count -gt 0) {
    Write-Host ("Started by this script: " + ($startedServices -join ", "))
    Write-Host "Missing services detected. Rolling back started services..."
    Stop-Started -name "relic-face" -pid $pidFrontend
    Stop-Started -name "relic-core" -pid $pidBackend
    Stop-Started -name "OpenClaw" -pid $pidOpenClaw
    Stop-Started -name "Ollama" -pid $pidOllama
  } else {
    Write-Host "No service was started, rollback skipped."
  }
  if (-not $noPause) {
    Write-Host ""
    Write-Host "Press any key to close this window..."
    $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
  }
  exit 1
}

Write-Host "Missing services: none"
if ($startedServices.Count -gt 0) {
  Write-Host ("Started by this script: " + ($startedServices -join ", "))
} else {
  Write-Host "No new service process started. All services may already be running."
}
Write-Host "Quick start completed."

if (-not $noPause) {
  Write-Host ""
  Write-Host "Press any key to close this window..."
  $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
}
exit 0
