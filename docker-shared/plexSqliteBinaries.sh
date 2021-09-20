#!/bin/bash

D_OS_ARCH=$(dpkg --print-architecture)
echo "ARCH :: $D_OS_ARCH"

case $D_OS_ARCH in
  "amd64")
  dl_url="https://downloads.plex.tv/plex-media-server-new/1.24.3.5033-757abe6b4/debian/plexmediaserver_1.24.3.5033-757abe6b4_amd64.deb"
  ;;
  "i386")
  dl_url="https://downloads.plex.tv/plex-media-server-new/1.24.3.5033-757abe6b4/debian/plexmediaserver_1.24.3.5033-757abe6b4_i386.deb"
  ;;
  "armhf")
  dl_url="https://downloads.plex.tv/plex-media-server-new/1.24.3.5033-757abe6b4/debian/plexmediaserver_1.24.3.5033-757abe6b4_armhf.deb"
  ;;
  *)
  echo "INVALID_ARCHITECTURE_FOR_PLEX_SQLITE_DL: ${D_OS_ARCH}"
  exit -1
  ;;
esac

# Extract
wget -nv -O plex_tmp "$dl_url"
ar vx plex_tmp
tar -xf data.tar.xz
rm plex_tmp debian-binary _gpgplex control.tar.gz data.tar.xz
mv "usr/lib/plexmediaserver" "plexsqlitedriver"
rm -rf etc usr
cd plexsqlitedriver
rm  CrashUploader 'Plex Media Fingerprinter' 'Plex Relay' 'Plex Transcoder' 'Plex Commercial Skipper' 'Plex Media Scanner' 'Plex Tuner Service' 'Plex DLNA Server' 'Plex Script Host'
rm -rf Resources etc
rm -rf "lib/dri"

# Testing functionality
OUTPUT=$(echo "select sqlite_version();" | "./Plex SQLite")

if [[ "$OUTPUT" == "3"* ]]; then
    echo "PLEX_SQLITE_DEPLOYED @ ${PWD}"
else
    echo "PLEX_SQLITE_DEPLOYMENT_ERROR"
    exit -1
fi