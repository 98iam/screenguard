# Push ScreenGuard APKs to Google Drive Folder: ScreenGuard APKs
$folderId = "1AVaa2Ecy4gjYWd77TyL0YYVglMqkaXab"
$childApk = Join-Path $PSScriptRoot "ScreenGuard-Child.apk"
$parentApk = Join-Path $PSScriptRoot "ScreenGuard-Parent.apk"

Write-Host "Syncing ScreenGuard APKs to Google Drive..." -ForegroundColor Cyan

# Update Child APK
if (Test-Path $childApk) {
    Write-Host "Updating ScreenGuard-Child.apk on Google Drive..." -ForegroundColor Yellow
    gdrive mutate update 18Zb5ZlKczZiH84kVNSYbgvcBiGODDryT $childApk
}

# Update Parent APK
if (Test-Path $parentApk) {
    Write-Host "Updating ScreenGuard-Parent.apk on Google Drive..." -ForegroundColor Yellow
    gdrive mutate update 1tmgOh4EWj9-vaonWmHdBXwciGB6BDgWK $parentApk
}

Write-Host "Google Drive sync complete!" -ForegroundColor Green
Write-Host ("Folder link: https://drive.google.com/drive/folders/" + $folderId) -ForegroundColor Cyan
