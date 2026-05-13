
$MavenVersion = "3.9.6"
$MavenUrl = "https://archive.apache.org/dist/maven/maven-3/$MavenVersion/binaries/apache-maven-$MavenVersion-bin.zip"
$MavenZip = "apache-maven-$MavenVersion-bin.zip"
$MavenDir = "apache-maven-$MavenVersion"

if (-not (Test-Path $MavenDir)) {
    Write-Host "Downloading Maven $MavenVersion..."
    Invoke-WebRequest -Uri $MavenUrl -OutFile $MavenZip
    Write-Host "Extracting Maven..."
    Expand-Archive -Path $MavenZip -DestinationPath . -Force
    Remove-Item $MavenZip
}

$env:Path += ";$PWD\$MavenDir\bin"
Write-Host "Maven set up successfully."
.\mvn.cmd -version

# Run Maven goals
# application-local.yml の値 (機密 DB 接続情報など) を読み込ませるためにローカルプロファイルを有効化。
# application-local.yml が存在しない場合は application.yml + 環境変数だけで動作する想定。
.\mvn.cmd clean compile
.\mvn.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
