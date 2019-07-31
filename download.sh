#!/bin/bash
# 
# Script to download apk from github releases

BASE_URL="https://github.com/stevesoltys/backup/releases"
APK="app-release-unsigned.apk"
VERSION="latest/download"
TAG=$(git tag -l --points-at HEAD)

if [ ! -z ${TAG} ]; then
	VERSION="download/${TAG}"
fi

curl -L ${BASE_URL}/${VERSION}/${APK} > Backup.apk