#!/bin/bash
#
# Script to deploy to a prebuilt repo.

REPO_URL="https://stevesoltys:$GITHUB_API_KEY@github.com/stevesoltys/backup-prebuilt"
TAG=$(git tag -l --points-at HEAD)

git config --global user.email "github@stevesoltys.com"
git config --global user.name "Steve Soltys"
git clone --quiet $REPO_URL

cd backup-prebuilt
git checkout $TRAVIS_BRANCH || git checkout -b $TRAVIS_BRANCH
rm -Rf ./*
cp $TRAVIS_BUILD_DIR/Android.mk .
cp $TRAVIS_BUILD_DIR/app/build/outputs/apk/release/app-release-unsigned.apk ./Backup.apk
cp $TRAVIS_BUILD_DIR/permissions_com.stevesoltys.backup.xml .
cp $TRAVIS_BUILD_DIR/whitelist_com.stevesoltys.backup.xml .

git add .
git commit -m :'Travis build $TRAVIS_BUILD_NUMBER
https://github.com/stevesoltys/backup/commit/$TRAVIS_COMMIT'
git push origin $TRAVIS_BRANCH

if [ ! -z ${TAG} ]; then
	git tag ${TAG}
	git push origin --tags
fi