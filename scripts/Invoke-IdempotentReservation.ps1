function Invoke-IdempotentReservation {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$BaseUrl,

        [Parameter(Mandatory)]
        [string]$OrderId,

        [Parameter(Mandatory)]
        [string]$ProductId,

        [Parameter(Mandatory)]
        [ValidateRange(1, 2147483647)]
        [int]$Quantity,

        [Parameter(Mandatory)]
        [string]$IdempotencyKey,

        [ValidateRange(1, 10)]
        [int]$MaxAttempts = 5
    )

    $uri = "$($BaseUrl.TrimEnd('/'))/reservations"
    $body = @{
        orderId = $OrderId
        productId = $ProductId
        quantity = $Quantity
    } | ConvertTo-Json -Compress
    $headers = @{ 'Idempotency-Key' = $IdempotencyKey }

    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        try {
            Write-Verbose "Reservation attempt $attempt"
            $response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri $uri -Headers $headers `
                -ContentType 'application/json' -Body $body -TimeoutSec 15 -ErrorAction Stop
        } catch {
            $httpStatus = $null
            $retryAfter = $null
            $httpResponse = $_.Exception.Response
            if ($httpResponse) {
                $httpStatus = [int]$httpResponse.StatusCode
                if ($httpResponse -is [System.Net.HttpWebResponse]) {
                    $retryAfter = $httpResponse.Headers['Retry-After']
                } else {
                    $retryAfter = [string]$httpResponse.Headers.RetryAfter
                }
            }

            $errorCode = $null
            if ($httpStatus -eq 409 -and $_.ErrorDetails.Message) {
                try {
                    $errorCode = ($_.ErrorDetails.Message | ConvertFrom-Json -ErrorAction Stop).errorCode
                } catch {
                    # A 409 with no readable error code is a permanent conflict for this client.
                }
            }

            $networkFailure = $false
            if ($null -eq $httpStatus) {
                for ($cause = $_.Exception; $null -ne $cause; $cause = $cause.InnerException) {
                    if ($cause -is [System.TimeoutException] -or
                        $cause -is [System.Net.Sockets.SocketException] -or
                        ($cause -is [System.Net.WebException] -and $cause.Status.ToString() -in @(
                            'Timeout', 'ConnectFailure', 'ConnectionClosed', 'ReceiveFailure',
                            'SendFailure', 'KeepAliveFailure', 'NameResolutionFailure'
                        ))) {
                        $networkFailure = $true
                        break
                    }
                }
            }

            $retryable = $networkFailure -or $httpStatus -in @(429, 502, 503, 504) -or
                ($httpStatus -eq 409 -and $errorCode -eq 'CONCURRENT_MODIFICATION')
            if (-not $retryable -or $attempt -eq $MaxAttempts) {
                throw
            }

            $retryAfterSeconds = 0
            $seconds = 0L
            $date = [DateTimeOffset]::MinValue
            if ([long]::TryParse($retryAfter, [ref]$seconds)) {
                $retryAfterSeconds = [Math]::Max(0L, $seconds)
            } elseif ([DateTimeOffset]::TryParse($retryAfter, [ref]$date)) {
                $retryAfterSeconds = [Math]::Max(0, [Math]::Ceiling(($date - [DateTimeOffset]::UtcNow).TotalSeconds))
            }
            if ($retryAfterSeconds -gt 30) {
                Write-Verbose 'Retry-After exceeds the 30-second wait budget'
                throw
            }

            $backoff = [int][Math]::Min(4000, 250 * [Math]::Pow(2, $attempt - 1))
            $jitter = Get-Random -Minimum 0 -Maximum ($backoff + 1)
            $delay = [int][Math]::Max($jitter, $retryAfterSeconds * 1000)
            Write-Verbose "Retrying in $delay ms"
            Start-Sleep -Milliseconds $delay
            continue
        }

        return [PSCustomObject]@{
            Status = $response.StatusCode
            Replayed = [string]($response.Headers['Idempotency-Replayed'] | Select-Object -First 1)
            IdempotencyKey = $IdempotencyKey
            Reservation = $response.Content | ConvertFrom-Json
        }
    }
}
