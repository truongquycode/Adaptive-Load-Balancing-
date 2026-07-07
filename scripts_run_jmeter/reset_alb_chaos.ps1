param(
    [Parameter(Mandatory = $true)]
    [string]$GatewayResetUrl,

    [Parameter(Mandatory = $true)]
    [string]$BackendBaseUrl,

    [Parameter(Mandatory = $true)]
    [string]$BackendPorts,

    [int]$TimeoutSec = 8,
    [int]$Retries = 1,
    [int]$RetryDelaySec = 2,

    [switch]$Strict
)

$ErrorActionPreference = 'Stop'
$failures = New-Object System.Collections.Generic.List[string]

function Invoke-PostWithRetry {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Uri,
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    for ($attempt = 1; $attempt -le ($Retries + 1); $attempt++) {
        try {
            Write-Host ("[INFO] Reset {0}: {1} (attempt {2})" -f $Name, $Uri, $attempt)
            $response = Invoke-WebRequest -Method Post -Uri $Uri -TimeoutSec $TimeoutSec -UseBasicParsing
            Write-Host ("[INFO] Reset {0} completed. HTTP {1}" -f $Name, [int]$response.StatusCode)
            return $true
        }
        catch {
            Write-Host ("[WARN] Reset {0} failed on attempt {1}: {2}" -f $Name, $attempt, $_.Exception.Message)
            if ($attempt -le $Retries) {
                Start-Sleep -Seconds $RetryDelaySec
            }
        }
    }

    $failures.Add($Name) | Out-Null
    return $false
}

$backendBase = $BackendBaseUrl.TrimEnd('/')
$ports = $BackendPorts -split '\s+' | Where-Object { $_ -and $_.Trim().Length -gt 0 }

# Best-effort reset before each missing run. The JMeter test plan also resets
# all backends and the gateway inside its Setup Thread Group, so a temporary
# reset timeout here should not stop the completion run.
[void](Invoke-PostWithRetry -Uri $GatewayResetUrl -Name 'gateway-alb')

foreach ($port in $ports) {
    $cleanPort = $port.Trim()
    $uri = ('{0}:{1}/api/chaos/reset' -f $backendBase, $cleanPort)
    [void](Invoke-PostWithRetry -Uri $uri -Name ("backend-{0}" -f $cleanPort))
}

if ($failures.Count -gt 0) {
    Write-Host ("[WARN] Best-effort reset finished with failed targets: {0}" -f ($failures -join ', '))
    if ($Strict) {
        exit 1
    }
    exit 0
}

Write-Host '[INFO] Best-effort reset completed successfully.'
exit 0
