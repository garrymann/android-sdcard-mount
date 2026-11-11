[CmdletBinding()]
param(
    [string]$Repository = 'garrymann/android-sdcard-mount'
)

# Applies the repository's GitHub settings: security features, Actions policy, merge settings and
# the rulesets in .github/rulesets. Safe to re-run; each setting is applied independently and
# reported as OK, FAILED or SKIPPED.
#
# Rulesets, secret scanning, private vulnerability reporting and fork-PR approval are unavailable to
# private repositories on the Free plan. Run this again after making the repository public.
#
# Needs an authenticated `gh`. If `gh auth login` was not run, the GitHub credential stored in Git
# Credential Manager is used for this process only.

# Continue, not Stop: Windows PowerShell 5.1 turns any stderr output of a native command into a
# terminating error under Stop. Exit codes are checked explicitly instead.
$ErrorActionPreference = 'Continue'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw 'GitHub CLI (gh) was not found on PATH.'
}
& gh auth status *> $null
if ($LASTEXITCODE -ne 0 -and -not $env:GH_TOKEN) {
    $credential = "protocol=https`nhost=github.com`n`n" | git credential fill 2>$null
    $token = ($credential | Where-Object { $_ -like 'password=*' }) -replace '^password=', ''
    if (-not $token) {
        throw 'gh is not authenticated and no GitHub credential is stored. Run: gh auth login'
    }
    $env:GH_TOKEN = $token
}

$isPrivate = (& gh api "repos/$Repository" --jq '.private')
if ($LASTEXITCODE -ne 0) {
    throw "Cannot read $Repository; check that gh is authenticated with access to it."
}
$isPrivate = $isPrivate -eq 'true'
Write-Host "Configuring $Repository ($(if ($isPrivate) { 'private' } else { 'public' }))" -ForegroundColor Cyan

function Invoke-GhApi {
    param([string]$Method, [string]$Path, [string]$Body)
    $arguments = @('api', '-X', $Method, $Path)
    if ($Body) {
        $arguments += @('--input', '-')
        $output = $Body | & gh @arguments 2>&1
    } else {
        $output = & gh @arguments 2>&1
    }
    if ($LASTEXITCODE -ne 0) {
        throw (($output | Out-String).Trim())
    }
    return $output
}

function Set-Setting {
    param([string]$Name, [scriptblock]$Action, [switch]$PublicOnly)
    if ($PublicOnly -and $isPrivate) {
        Write-Host "  SKIPPED  $Name (needs a public repository)" -ForegroundColor DarkYellow
        return
    }
    try {
        & $Action | Out-Null
        Write-Host "  OK       $Name" -ForegroundColor Green
    } catch {
        Write-Host "  FAILED   $Name -- $($_.Exception.Message)" -ForegroundColor Red
    }
}

Set-Setting 'Merge settings: squash/rebase only, auto-delete branches, web sign-off' {
    Invoke-GhApi PATCH "repos/$Repository" (@{
        allow_merge_commit         = $false
        allow_squash_merge         = $true
        allow_rebase_merge         = $true
        delete_branch_on_merge     = $true
        web_commit_signoff_required = $true
    } | ConvertTo-Json)
}

Set-Setting 'Actions: read-only GITHUB_TOKEN by default' {
    Invoke-GhApi PUT "repos/$Repository/actions/permissions/workflow" (@{
        default_workflow_permissions     = 'read'
        can_approve_pull_request_reviews = $false
    } | ConvertTo-Json)
}

Set-Setting 'Dependabot alerts' { Invoke-GhApi PUT "repos/$Repository/vulnerability-alerts" }
Set-Setting 'Dependabot security updates' { Invoke-GhApi PUT "repos/$Repository/automated-security-fixes" }

Set-Setting 'Secret scanning with push protection' -PublicOnly {
    Invoke-GhApi PATCH "repos/$Repository" (@{
        security_and_analysis = @{
            secret_scanning                 = @{ status = 'enabled' }
            secret_scanning_push_protection = @{ status = 'enabled' }
        }
    } | ConvertTo-Json -Depth 4)
}

Set-Setting 'Private vulnerability reporting' -PublicOnly {
    Invoke-GhApi PUT "repos/$Repository/private-vulnerability-reporting"
}

Set-Setting 'Actions: approval required for all outside contributors' -PublicOnly {
    Invoke-GhApi PUT "repos/$Repository/actions/permissions/fork-pr-contributor-approval" (@{
        approval_policy = 'all_external_contributors'
    } | ConvertTo-Json)
}

$existing = @{}
if (-not $isPrivate) {
    $rulesets = & gh api "repos/$Repository/rulesets"
    if ($LASTEXITCODE -ne 0) {
        # Without the list every ruleset would be POSTed again and fail as a duplicate, or worse,
        # silently create one alongside a renamed original.
        throw "Cannot list rulesets for $Repository; nothing was changed. Re-run in a minute."
    }
    ($rulesets | ConvertFrom-Json) | ForEach-Object { $existing[$_.name] = $_.id }
}
Get-ChildItem (Join-Path $repoRoot '.github\rulesets') -Filter *.json | Sort-Object Name | ForEach-Object {
    $rulesetBody = Get-Content $_.FullName -Raw
    $rulesetName = ($rulesetBody | ConvertFrom-Json).name
    $rulesetId = $existing[$rulesetName]
    # The block runs inside Set-Setting, so it must not use a name Set-Setting also defines: a plain
    # $name would resolve to its -Name parameter (variable names are case-insensitive).
    Set-Setting "Ruleset '$rulesetName'" -PublicOnly {
        if ($rulesetId) {
            Invoke-GhApi PUT "repos/$Repository/rulesets/$rulesetId" $rulesetBody
        } else {
            Invoke-GhApi POST "repos/$Repository/rulesets" $rulesetBody
        }
    }
}
