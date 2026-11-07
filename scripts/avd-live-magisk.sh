#!/system/bin/sh
# Live-start Magisk on an emulator (no ramdisk patch), adapted from Magisk's scripts/avd_magisk.sh.
set -x
W=/data/local/tmp/avdm
cd $W || exit 1
if [ -z "$FIRST_STAGE" ]; then export FIRST_STAGE=1 ASH_STANDALONE=1; exec ./busybox sh $0; fi
APK=$(pm path com.topjohnwu.magisk | head -1 | cut -d: -f2)
cp -f "$APK" magisk.apk
./busybox unzip -oj magisk.apk 'assets/util_functions.sh' 'assets/stub.apk'
. ./util_functions.sh
api_level_arch_detect
./busybox unzip -oj magisk.apk "lib/$ABI/*" -x "lib/$ABI/libbusybox.so"
for file in lib*.so; do chmod 755 $file; mv "$file" "${file:3:${#file}-6}"; done
stop
[ -d /debug_ramdisk ] && umount -l /debug_ramdisk 2>/dev/null
setprop sys.boot_completed 0
MAGISKTMP=/debug_ramdisk
rm -f magisk.tmp
mount -t tmpfs -o 'mode=0755' magisk /debug_ramdisk
MAGISKBIN=/data/adb/magisk
mkdir -p $MAGISKBIN 2>/dev/null
./busybox unzip -oj magisk.apk 'assets/*.sh' -d $MAGISKBIN
mkdir -p /data/adb/modules /data/adb/post-fs-data.d /data/adb/service.d 2>/dev/null
for file in magisk magiskpolicy magiskboot busybox; do chmod 755 ./$file; cp -af ./$file $MAGISKTMP/$file; cp -af ./$file $MAGISKBIN/$file; done
cp -af ./magiskinit $MAGISKBIN/magiskinit
cp -af ./init-ld $MAGISKBIN/init-ld 2>/dev/null
cp -af ./stub.apk $MAGISKTMP/stub.apk
ln -s ./magisk $MAGISKTMP/su
ln -s ./magisk $MAGISKTMP/resetprop
ln -s ./magiskpolicy $MAGISKTMP/supolicy
mkdir -p $MAGISKTMP/.magisk/device $MAGISKTMP/.magisk/worker
mount -t tmpfs -o 'mode=0755' magisk $MAGISKTMP/.magisk/worker
mount --make-private $MAGISKTMP/.magisk/worker
touch $MAGISKTMP/.magisk/config
export MAGISKTMP
MAKEDEV=1 $MAGISKTMP/magisk --preinit-device 2>&1
RULESCMD=""
for r in $MAGISKTMP/.magisk/preinit/*/sepolicy.rule; do [ -f "$r" ] || continue; RULESCMD="$RULESCMD --apply $r"; done
./magiskpolicy --live --magisk $RULESCMD
$MAGISKTMP/magisk --post-fs-data
start
$MAGISKTMP/magisk --service
sleep 20
$MAGISKTMP/magisk --boot-complete
echo DONE
