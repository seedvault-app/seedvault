#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2023 The Calyx Institute
# SPDX-License-Identifier: Apache-2.0
#

export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y git-core gnupg flex bison build-essential zip curl zlib1g-dev  \
    libc6-dev-i386 libncurses5 x11proto-core-dev libx11-dev lib32z1-dev libgl1-mesa-dev  \
    libxml2-utils xsltproc unzip fontconfig python3 npm pip e2fsprogs python3-protobuf \
    fonts-dejavu diffutils rsync ccache

npm install --global yarn

mkdir -p ~/bin
curl https://storage.googleapis.com/git-repo-downloads/repo > ~/bin/repo
chmod a+x ~/bin/repo
ln -s /usr/bin/python3 /usr/bin/python
export PATH=~/bin:$PATH

set -e

retry() {
  set +e
  local max_attempts=${ATTEMPTS-5}
  local timeout=${TIMEOUT-1}
  local attempt=0
  local exitCode=0

  while [[ $attempt < $max_attempts ]]
  do
    "$@"
    exitCode=$?

    if [[ $exitCode == 0 ]]
    then
      break
    fi

    echo "Failure! Retrying ($*) in $timeout.."
    sleep "${timeout}"
    attempt=$(( attempt + 1 ))
    timeout=$(( timeout * 2 ))
  done

  if [[ $exitCode != 0 ]]
  then
    echo "Failed too many times! ($*)"
  fi

  set -e

  return $exitCode
}

DEVICE=$1
RELEASE=$2
TARGET=$3
BRANCH=$4

git config --global user.email "seedvault@example.com"
git config --global user.name "Seedvault CI"

retry curl https://storage.googleapis.com/git-repo-downloads/repo > ~/bin/repo
chmod a+x ~/bin/repo

mkdir -p /aosp
cd /aosp
retry yes | repo init -u https://android.googlesource.com/platform/manifest -b "$BRANCH" --depth=1

mkdir -p .repo/local_manifests
cat << EOF > .repo/local_manifests/seedvault.xml
<?xml version="1.0" encoding="UTF-8"?>
<manifest>

  <remote name="seedvault"
          fetch="https://github.com/$CIRRUS_REPO_OWNER" />

  <project name="seedvault" path="external/seedvault"
          revision="$CIRRUS_CHANGE_IN_REPO" remote="$CIRRUS_REPO_NAME" />

</manifest>
EOF

retry repo sync -c -j8 --fail-fast --force-sync

# Cirrus CI seems to (possibly) reschedule tasks that aren't sending out logs for a while?
while true; do echo "Still building..."; sleep 30; done &

source build/envsetup.sh
lunch $DEVICE-$RELEASE-$TARGET
m -j1 nothing
m -j2 Seedvault

mv /aosp/out/target/product/generic_arm64/system/system_ext/priv-app/Seedvault/Seedvault.apk "$CIRRUS_WORKING_DIR"
