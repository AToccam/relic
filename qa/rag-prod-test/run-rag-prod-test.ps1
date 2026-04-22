param(
    [Parameter(Mandatory = $true)]
    [string]$SiliconFlowApiKey,

    [string]$EmbeddingModel = "Qwen/Qwen3-Embedding-8B",
    [string]$EmbeddingUrl = "https://api.siliconflow.cn/v1/embeddings",
    [string]$BackendBaseUrl = "http://127.0.0.1:8082",
    [string]$ChromaBaseUrl = "http://127.0.0.1:8000",
    [string]$ChromaApiPrefix = "/api/v2",
    [string]$ChromaCollectionName = "relic_rag_prodtest",
    [string]$ChromaContainerName = "relic-rag-prodtest-chroma",
    [switch]$KeepProcesses
)

$ErrorActionPreference = "Stop"

$script:RootDir = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$script:OutputDir = Join-Path $PSScriptRoot "output"
$script:InputDir = Join-Path $PSScriptRoot "input"
$script:BackendDir = Join-Path $script:RootDir "relic-core"
$script:ConfigPath = Join-Path $PSScriptRoot "relic-core-rag-prodtest.yml"
$script:BackendLog = Join-Path $script:OutputDir "relic-core.log"
$script:BackendErrLog = Join-Path $script:OutputDir "relic-core.err.log"
$script:SummaryPath = Join-Path $script:OutputDir "report.md"
$script:ProbePath = Join-Path $script:OutputDir "embedding-probe.json"
$script:RunMetaPath = Join-Path $script:OutputDir "run-meta.json"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Ensure-Directory([string]$Path) {
    if (-not (Test-Path $Path)) {
        New-Item -ItemType Directory -Path $Path | Out-Null
    }
}

function Invoke-JsonRequest {
    param(
        [Parameter(Mandatory = $true)][ValidateSet("GET", "POST", "DELETE")] [string]$Method,
        [Parameter(Mandatory = $true)][string]$Uri,
        [object]$Body
    )

    if ($null -eq $Body) {
        return Invoke-RestMethod -Method $Method -Uri $Uri
    }

    return Invoke-RestMethod -Method $Method -Uri $Uri -ContentType "application/json; charset=utf-8" -Body ($Body | ConvertTo-Json -Depth 10)
}

function Get-TrimmedCommandOutput {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$ScriptBlock
    )

    $result = & $ScriptBlock
    if ($null -eq $result) {
        return ""
    }
    return ([string]$result).Trim()
}

function Wait-HttpReady {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 5 | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 2
        }
    } while ((Get-Date) -lt $deadline)

    throw "等待服务就绪超时: $Uri"
}

function Wait-ChromaReady {
    param(
        [int]$TimeoutSeconds = 120
    )

    $heartbeatCandidates = @(
        "$ChromaBaseUrl/api/v2/heartbeat",
        "$ChromaBaseUrl/api/v1/heartbeat"
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)

    do {
        foreach ($uri in $heartbeatCandidates) {
            try {
                Invoke-WebRequest -UseBasicParsing -Uri $uri -TimeoutSec 5 | Out-Null
                return $uri
            } catch {
            }
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    throw "等待 Chroma 就绪超时: $($heartbeatCandidates -join ', ')"
}

function Wait-IndexCompleted {
    param(
        [Parameter(Mandatory = $true)][string]$SourceId,
        [int]$TimeoutSeconds = 180
    )

    $statusUri = "$BackendBaseUrl/rag/index/status?sourceId=$([uri]::EscapeDataString($SourceId))"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)

    do {
        $status = Invoke-JsonRequest -Method GET -Uri $statusUri
        if ($status.status -eq "COMPLETED") {
            return $status
        }
        if ($status.status -eq "FAILED") {
            throw "索引失败: $SourceId => $($status.message)"
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    throw "索引超时: $SourceId"
}

function Invoke-SseRequest {
    param(
        [Parameter(Mandatory = $true)][string]$PayloadPath,
        [Parameter(Mandatory = $true)][string]$OutputPath
    )

    $result = & curl.exe -sS -N -H "Content-Type: application/json" --data "@$PayloadPath" "$BackendBaseUrl/v1/chat/completions"
    [System.IO.File]::WriteAllText($OutputPath, $result, [System.Text.UTF8Encoding]::new($false))
    return $result
}

function Parse-SseLog {
    param(
        [Parameter(Mandatory = $true)][string]$Path
    )

    $raw = Get-Content -Path $Path -Raw -Encoding UTF8
    $segments = @()
    if (-not [string]::IsNullOrWhiteSpace($raw)) {
        $segments = ($raw -split '(?=data:)') | ForEach-Object { $_.Trim() } | Where-Object { $_ }
    }

    $chunks = @()
    $doneSeen = $false
    foreach ($segment in $segments) {
        if (-not $segment.StartsWith("data:")) {
            continue
        }
        $payload = $segment.Substring(5).Trim()
        if ([string]::IsNullOrWhiteSpace($payload)) {
            continue
        }
        if ($payload -eq "[DONE]") {
            $doneSeen = $true
            continue
        }
        try {
            $chunks += ($payload | ConvertFrom-Json)
        } catch {
        }
    }

    $firstChunk = if ($chunks.Count -gt 0) { $chunks[0] } else { $null }
    $citations = @()
    if ($null -ne $firstChunk -and $null -ne $firstChunk.citations) {
        $citations = @($firstChunk.citations)
    }

    $contentParts = @()
    foreach ($chunk in $chunks) {
        if ($null -ne $chunk.choices -and $chunk.choices.Count -gt 0) {
            $delta = $chunk.choices[0].delta
            if ($null -ne $delta -and $null -ne $delta.content -and "$($delta.content)" -ne "") {
                $contentParts += "$($delta.content)"
            }
        }
    }

    return [pscustomobject]@{
        ChunkCount = $chunks.Count
        DoneSeen = $doneSeen
        CitationCount = $citations.Count
        Citations = $citations
        Content = ($contentParts -join "")
    }
}

function Invoke-ChatCase {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Prompt,
        [Parameter(Mandatory = $true)][hashtable]$RagConfig,
        [bool]$ToolsEnabled = $true
    )

    $payloadPath = Join-Path $script:OutputDir ($Name + ".request.json")
    $ssePath = Join-Path $script:OutputDir ($Name + ".sse.log")
    $body = @{
        conversationId = "rag-prodtest-$Name"
        toolsEnabled = $ToolsEnabled
        messages = @(
            @{
                role = "user"
                content = $Prompt
            }
        )
        ragConfig = $RagConfig
    } | ConvertTo-Json -Depth 8

    [System.IO.File]::WriteAllText($payloadPath, $body, [System.Text.UTF8Encoding]::new($false))
    Invoke-SseRequest -PayloadPath $payloadPath -OutputPath $ssePath | Out-Null
    $parsed = Parse-SseLog -Path $ssePath

    $pass = $false
    switch ($Name) {
        "rag-hit" {
            $pass = $parsed.DoneSeen -and $parsed.CitationCount -ge 1
            break
        }
        "rag-disabled-control" {
            $pass = $parsed.DoneSeen -and $parsed.CitationCount -eq 0
            break
        }
        "rag-unrelated-source" {
            $pass = $parsed.DoneSeen -and $parsed.CitationCount -eq 0
            break
        }
        default {
            $pass = $parsed.DoneSeen
        }
    }

    return [pscustomobject]@{
        name = $Name
        pass = $pass
        doneSeen = $parsed.DoneSeen
        citationCount = $parsed.CitationCount
        contentPreview = $parsed.Content.Trim()
    }
}

Ensure-Directory $script:OutputDir

$backendProcess = $null
$cleanupBackend = $false
$cleanupContainer = $false

try {
    Write-Step "探测 SiliconFlow Embedding API"
    $probePayload = @{
        model = $EmbeddingModel
        input = "RAG production probe text."
        encoding_format = "float"
    } | ConvertTo-Json -Depth 5

    $probeResponse = Invoke-WebRequest -UseBasicParsing `
        -Uri $EmbeddingUrl `
        -Method POST `
        -Headers @{ Authorization = "Bearer $SiliconFlowApiKey" } `
        -ContentType "application/json; charset=utf-8" `
        -Body $probePayload

    [System.IO.File]::WriteAllText($script:ProbePath, $probeResponse.Content, [System.Text.UTF8Encoding]::new($false))
    $probeJson = $probeResponse.Content | ConvertFrom-Json
    $embeddingDimension = @($probeJson.data[0].embedding).Count
    $probeTokens = [int]$probeJson.usage.prompt_tokens
    $traceId = $probeResponse.Headers["x-siliconcloud-trace-id"]

    Write-Step "启动或复用 Chroma"
    $existingContainer = Get-TrimmedCommandOutput { docker ps -a --filter "name=^/$ChromaContainerName$" --format "{{.Names}}" }
    if ([string]::IsNullOrWhiteSpace($existingContainer)) {
        & docker run -d --name $ChromaContainerName -p 8000:8000 chromadb/chroma:latest | Out-Null
        $cleanupContainer = $true
    } else {
        $runningContainer = Get-TrimmedCommandOutput { docker ps --filter "name=^/$ChromaContainerName$" --format "{{.Names}}" }
        if ([string]::IsNullOrWhiteSpace($runningContainer)) {
            & docker start $ChromaContainerName | Out-Null
        }
    }
    $activeChromaHeartbeat = Wait-ChromaReady -TimeoutSeconds 120

    Write-Step "启动 relic-core 生产测试实例"
    $env:SILICONFLOW_API_KEY = $SiliconFlowApiKey
    $env:RAG_EMBEDDING_MODEL = $EmbeddingModel
    $env:RAG_EMBEDDING_URL = $EmbeddingUrl
    $env:RAG_EMBEDDING_DIMENSIONS = "$embeddingDimension"
    $env:RAG_CHROMA_HOST = ([uri]$ChromaBaseUrl).Host
    $env:RAG_CHROMA_PORT = ([uri]$ChromaBaseUrl).Port
    $env:RAG_CHROMA_API_PREFIX = $ChromaApiPrefix
    $env:RAG_CHROMA_COLLECTION_NAME = $ChromaCollectionName

    $backendRunning = $false
    try {
        Invoke-WebRequest -UseBasicParsing -Uri "$BackendBaseUrl/mode" -TimeoutSec 3 | Out-Null
        $backendRunning = $true
    } catch {
        $backendRunning = $false
    }

    if (-not $backendRunning) {
        if (Test-Path $script:BackendLog) { Remove-Item -LiteralPath $script:BackendLog -Force }
        if (Test-Path $script:BackendErrLog) { Remove-Item -LiteralPath $script:BackendErrLog -Force }

        $configUri = "file:///" + ($script:ConfigPath -replace "\\", "/")
        $backendProcess = Start-Process `
            -FilePath "mvn.cmd" `
            -ArgumentList @("spring-boot:run", "-Dspring-boot.run.arguments=--spring.config.additional-location=$configUri") `
            -WorkingDirectory $script:BackendDir `
            -RedirectStandardOutput $script:BackendLog `
            -RedirectStandardError $script:BackendErrLog `
            -PassThru
        $cleanupBackend = $true
        Wait-HttpReady -Uri "$BackendBaseUrl/mode" -TimeoutSeconds 180
    }

    Invoke-JsonRequest -Method POST -Uri "$BackendBaseUrl/mode" -Body @{
        mode = "single"
        singleProvider = "deepseek"
    } | Out-Null

    Write-Step "上传测试文档"
    $uploadA = & curl.exe -sS -X POST "$BackendBaseUrl/files/upload" -F "file=@$((Join-Path $script:InputDir 'rag-prod-a.txt') -replace '\\','/')" | ConvertFrom-Json
    $uploadB = & curl.exe -sS -X POST "$BackendBaseUrl/files/upload" -F "file=@$((Join-Path $script:InputDir 'rag-prod-b.txt') -replace '\\','/')" | ConvertFrom-Json
    $uploadUnrelated = & curl.exe -sS -X POST "$BackendBaseUrl/files/upload" -F "file=@$((Join-Path $script:InputDir 'rag-prod-unrelated.txt') -replace '\\','/')" | ConvertFrom-Json

    Write-Step "触发索引并等待完成"
    foreach ($sourceId in @($uploadA.relativePath, $uploadB.relativePath, $uploadUnrelated.relativePath)) {
        Invoke-JsonRequest -Method POST -Uri "$BackendBaseUrl/rag/index/manual" -Body @{ sourceId = $sourceId } | Out-Null
    }

    $indexA = Wait-IndexCompleted -SourceId $uploadA.relativePath
    $indexB = Wait-IndexCompleted -SourceId $uploadB.relativePath
    $indexUnrelated = Wait-IndexCompleted -SourceId $uploadUnrelated.relativePath

    Write-Step "执行三组聊天验证"
    $testPrompt = "请根据资料说明 RAG 的触发条件和 citations 的返回方式。"
    $results = @()
    $results += Invoke-ChatCase -Name "rag-hit" -Prompt $testPrompt -RagConfig @{
        enabled = $true
        sourceIds = @($uploadA.relativePath, $uploadB.relativePath)
    } -ToolsEnabled $false
    $results += Invoke-ChatCase -Name "rag-disabled-control" -Prompt $testPrompt -RagConfig @{
        enabled = $false
        sourceIds = @($uploadA.relativePath, $uploadB.relativePath)
    } -ToolsEnabled $false
    $results += Invoke-ChatCase -Name "rag-unrelated-source" -Prompt $testPrompt -RagConfig @{
        enabled = $true
        sourceIds = @($uploadUnrelated.relativePath)
    } -ToolsEnabled $false

    $totalPass = ($results | Where-Object { $_.pass }).Count
    $overallPass = $totalPass -eq $results.Count
    $estimatedProbeCost = [math]::Round(($probeTokens * 0.28 / 1000000.0), 8)

    $meta = [ordered]@{
        executedAt = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss zzz")
        embeddingUrl = $EmbeddingUrl
        embeddingModel = $EmbeddingModel
        embeddingDimension = $embeddingDimension
        embeddingProbePromptTokens = $probeTokens
        embeddingProbeEstimatedCostRmb = $estimatedProbeCost
        siliconFlowTraceId = $traceId
        chromaHeartbeat = $activeChromaHeartbeat
        chromaApiPrefix = $ChromaApiPrefix
        chromaCollectionName = $ChromaCollectionName
        uploads = @($uploadA.relativePath, $uploadB.relativePath, $uploadUnrelated.relativePath)
        indexStatus = @(
            @{ sourceId = $indexA.sourceId; status = $indexA.status; chunkCount = $indexA.chunkCount },
            @{ sourceId = $indexB.sourceId; status = $indexB.status; chunkCount = $indexB.chunkCount },
            @{ sourceId = $indexUnrelated.sourceId; status = $indexUnrelated.status; chunkCount = $indexUnrelated.chunkCount }
        )
        results = $results
        overallPass = $overallPass
    }

    ($meta | ConvertTo-Json -Depth 10) | Set-Content -Path $script:RunMetaPath -Encoding UTF8

    $reportLines = @()
    $reportLines += "# RAG 生产验证报告"
    $reportLines += ""
    $reportLines += "- 执行时间: $($meta.executedAt)"
    $reportLines += "- Embedding 服务: $EmbeddingUrl"
    $reportLines += "- Embedding 模型: $EmbeddingModel"
    $reportLines += "- 实测返回维度: $embeddingDimension"
    $reportLines += "- Probe tokens: $probeTokens"
    $reportLines += "- 按 0.28 元 / 1M tokens 估算本次 probe 费用: ￥$estimatedProbeCost"
    $reportLines += "- Chroma heartbeat: $activeChromaHeartbeat"
    $reportLines += "- Chroma API prefix: $ChromaApiPrefix"
    $reportLines += "- Chroma collection: $ChromaCollectionName"
    if ($traceId) {
        $reportLines += "- SiliconFlow trace id: $traceId"
    }
    $reportLines += ""
    $reportLines += "## 索引结果"
    foreach ($item in $meta.indexStatus) {
        $reportLines += "- $($item.sourceId): $($item.status), chunks=$($item.chunkCount)"
    }
    $reportLines += ""
    $reportLines += "## 用例结果"
    foreach ($item in $results) {
        $status = "FAIL"
        if ($item.pass) {
            $status = "PASS"
        }
        $reportLines += "- $($item.name): $status, done=$($item.doneSeen), citations=$($item.citationCount)"
    }
    $reportLines += ""
    $reportLines += "## 产物"
    $reportLines += "- Probe 响应: $script:ProbePath"
    $reportLines += "- 运行元数据: $script:RunMetaPath"
    $reportLines += "- 后端日志: $script:BackendLog"
    $reportLines += "- SSE 原始输出目录: $script:OutputDir"

    [System.IO.File]::WriteAllLines($script:SummaryPath, $reportLines, [System.Text.UTF8Encoding]::new($false))

    Write-Step "测试完成"
    Write-Host "报告已生成: $script:SummaryPath" -ForegroundColor Green
    if (-not $overallPass) {
        throw ('存在失败用例，请检查 {0} 与 output 目录中的原始日志。' -f $script:SummaryPath)
    }
}
finally {
    if (-not $KeepProcesses) {
        if ($cleanupBackend -and $null -ne $backendProcess -and -not $backendProcess.HasExited) {
            Stop-Process -Id $backendProcess.Id -Force
        }
        if ($cleanupContainer) {
            try {
                & docker rm -f $ChromaContainerName | Out-Null
            } catch {
            }
        }
    }
}
