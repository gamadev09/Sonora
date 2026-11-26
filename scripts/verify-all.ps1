# Automated Sonora Build & Regression Verification Script
# Runs unit tests across shared, sharedUi, and app, plus builds Phone, Wear OS, and TV APKs.
$ErrorActionPreference = "Stop"

Write-Host "=== 1. Running unit test suites (shared, sharedUi, app) ==="
.\gradlew.bat :shared:jvmTest :sharedUi:jvmTest :app:testProdDebugUnitTest "-Pbitchord.bundleWear=false" --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "=== 2. Building Android Phone, Wear OS, and Android TV APKs ==="
.\gradlew.bat :app:assembleProdDebug :wearos:assembleProdDebug :tv:assembleProdDebug "-Pbitchord.bundleWear=false" --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "=== ALL SONORA BUILD & REGRESSION CHECKS PASSED ==="
