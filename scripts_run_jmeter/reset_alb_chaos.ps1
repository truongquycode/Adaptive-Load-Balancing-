param(
    [Parameter(Mandatory = $true)]
    [string]$GatewayResetUrl,

    [Parameter(Mandatory = $true)]
    [string]$BackendBaseUrl,

    [Parameter(Mandatory = $true)]
    [string]$BackendPorts,

    [int]$TimeoutSec = 20,
    [int]$Retries = 2,
    [int]$RetryDelaySec = 2
)

$ErrorActionPreference = 'Stop'

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
            Invoke-RestMethod -Method Post -Uri $Uri -TimeoutSec $TimeoutSec | Out-Null
            Write-Host ("[INFO] Reset {0} completed." -f $Name)
            return
        }
        catch {
            Write-Host ("[WARN] Reset {0} failed on attempt {1}: {2}" -f $Name, $attempt, $_.Exception.Message)
            if ($attempt -gt $Retries) {
                throw
            }
            Start-Sleep -Seconds $RetryDelaySec
        }
    }
}

$backendBase = $BackendBaseUrl.TrimEnd('/')
$ports = $BackendPorts -split '\s+' | Where-Object { $_ -and $_.Trim().Length -gt 0 }

Invoke-PostWithRetry -Uri $GatewayResetUrl -Name 'gateway-alb'

foreach ($port in $ports) {
    $uri = ('{0}:{1}/api/chaos/reset' -f $backendBase, $port.Trim())
    Invoke-PostWithRetry -Uri $uri -Name ("backend-{0}" -f $port.Trim())
}

Write-Host '[INFO] Reset completed.'
