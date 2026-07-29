#!/usr/bin/env bash

set -e
shopt -s extglob
. scripts/test_common.sh

emu_args_base="-no-window -no-audio -no-boot-anim -gpu software -read-only -no-snapshot -cores $core_count"
log_args="-show-kernel -logcat '' -logcat-output logcat.log"
emu_args=

atd_min_api=30
atd_max_api=36
huge_ram_min_api=26

case $(uname -m) in
  'arm64'|'aarch64')
    if [ -n "$FORCE_32_BIT" ]; then
      echo "! ARM32 is not supported"
      exit 1
    fi
    arch=arm64-v8a
    ;;
  *)
    if [ -n "$FORCE_32_BIT" ]; then
      arch=x86
    else
      arch=x86_64
    fi

    ;;
esac

cleanup() {
  rm -f magisk_*.img
  "$avd" delete avd -n test > /dev/null 2>&1
}

test_error() {
  trap - EXIT
  print_error "! An error occurred"
  pkill -INT -P $$
  wait
  cleanup
  exit 1
}

wait_for_boot() {
  set -e
  adb wait-for-device
  while true; do
    local result="$(adb exec-out getprop sys.boot_completed)"
    if [ $? -ne 0 ]; then
      exit 1
    elif [ "$result" = "1" ]; then
      break
    fi
    sleep 2
  done
}

dump_vars() {
  local val
  for name in $@ emu_args; do
    eval val=\$$name
    echo $name=\"$val\"\;
  done
}

resolve_vars() {
  set +x
  local arg_list="$1"
  local ver=$2
  local type=$3

  # Determine API level
  local api
  case $ver in
    +([0-9\.])) api=$ver ;;
    TiramisuPrivacySandbox) api=33 ;;
    UpsideDownCakePrivacySandbox) api=34 ;;
    VanillaIceCream) api=35 ;;
    Baklava) api=36 ;;
    CinnamonBun) api=37 ;;
    *CANARY) api=10000 ;;
    *)
      print_error "! Unknown system image version '$ver'"
      exit 1
      ;;
  esac

  # Determine default image type
  if [ -z $type ]; then
    if [ $(bc <<< "$api >= $atd_min_api && $api <= $atd_max_api") = 1 ]; then
      # Use the lightweight ATD images if possible
      type='aosp_atd'
    elif [ $(bc <<< "$api > $atd_max_api") = 1 ]; then
      # Preview/beta release, no AOSP version available
      type='google_apis'
    else
      type='default'
    fi
  fi

  # Old Linux kernels will not boot with memory larger than 3GB
  local memory
  if [ $(bc <<< "$api < $huge_ram_min_api") = 1 ]; then
    memory=3072
  else
    memory=8192
  fi

  emu_args="$emu_args_base -memory $memory"

  # System image variable and paths
  local avd_pkg="system-images;android-$ver;$type;$arch"
  local sys_img_dir="$ANDROID_HOME/system-images/android-$ver/$type/$arch"
  local ramdisk="$sys_img_dir/ramdisk.img"

  # Dump variables to output
  dump_vars $arg_list
}

dl_emu() {
  local avd_pkg=$1
  yes | "$sdk" --licenses > /dev/null 2>&1
  "$sdk" --channel=3 'cmdline-tools;latest' platform-tools emulator "$avd_pkg"
  # It's possible cmdline-tools updated
  if [ -e "${cli_tools}-2" ]; then
    rm -rf "$cli_tools"
    mv "${cli_tools}-2" "$cli_tools"
  fi
}

setup_emu() {
  local avd_pkg=$1
  local ver=$2
  dl_emu $avd_pkg
  echo no | "$avd" create avd -f -n test -k $avd_pkg
}

test_emu() {
  local variant=$1

  local magisk_args="-ramdisk magisk_${variant}.img -feature -SystemAsRoot"

  if [ -n "$AVD_TEST_LOG" ]; then
    rm -f logcat.log
    "$emu" @test $emu_args $log_args $magisk_args > kernel.log 2>&1 &
  else
    "$emu" @test $emu_args $magisk_args > /dev/null 2>&1 &
  fi

  timeout $boot_timeout bash -c wait_for_boot

  run_setup $variant

  adb reboot
  timeout $boot_timeout bash -c wait_for_boot

  run_tests

  adb emu kill
  wait
}

test_main() {
  local ver avd_pkg ramdisk
  eval $(resolve_vars "ver avd_pkg ramdisk" $1 $2)

  # Specify an explicit port so that tests can run with other emulators running at the same time
  local emu_port=5682
  emu_args="$emu_args -port $emu_port"
  export ANDROID_SERIAL="emulator-$emu_port"

  setup_emu "$avd_pkg" $ver

  # Restart ADB daemon just in case
  adb kill-server
  adb start-server

  # Launch stock emulator
  print_title "* Launching $avd_pkg"
  "$emu" @test $emu_args > /dev/null 2>&1 &
  timeout $boot_timeout bash -c wait_for_boot

  # Patch images
  if [ -z "$AVD_TEST_SKIP_DEBUG" ]; then
    ./build.py -v avd_patch "$ramdisk" magisk_debug.img
  fi
  if [ -z "$AVD_TEST_SKIP_RELEASE" ]; then
    ./build.py -vr avd_patch "$ramdisk" magisk_release.img
  fi

  adb emu kill
  wait

  if [ -z "$AVD_TEST_SKIP_DEBUG" ]; then
    print_title "* Testing $avd_pkg (debug)"
    test_emu debug
  fi

  if [ -z "$AVD_TEST_SKIP_RELEASE" ]; then
    print_title "* Testing $avd_pkg (release)"
    test_emu release
  fi

  cleanup
}

run_main() {
  local ver avd_pkg
  eval $(resolve_vars "ver avd_pkg" $1 $2)
  setup_emu "$avd_pkg" $ver
  print_title "* Launching $avd_pkg"
  "$emu" @test $emu_args > /dev/null 2>&1 &
  wait_for_boot
  cleanup
}

dl_main() {
  local avd_pkg
  eval $(resolve_vars "avd_pkg" $1 $2)
  print_title "* Downloading $avd_pkg"
  dl_emu "$avd_pkg"
}

live_test_main() {
  local variants=("$@")
  if [ ${#variants[@]} -eq 0 ]; then
    if [ -z "$AVD_TEST_SKIP_DEBUG" ]; then variants+=(debug); fi
    if [ -z "$AVD_TEST_SKIP_RELEASE" ]; then variants+=(release); fi
  fi

  for variant in "${variants[@]}"; do
    # Cleanup
    adb shell pm uninstall io.github.seyud.weave || true
    adb shell pm uninstall repackaged.io.github.seyud.weave.test || true
    adb shell /system/xbin/su 0 rm -rf /data/adb/modules

    # "Install" Magisk
    ./build.py -v emulator
    timeout $boot_timeout bash -c wait_for_boot

    run_setup $variant

    # Trigger Magisk soft reboot
    ./build.py -v emulator
    timeout $boot_timeout bash -c wait_for_boot

    run_tests
  done
}

case "$1" in
  test )
    shift
    trap test_error EXIT
    export -f wait_for_boot
    set -x
    test_main "$@"
    ;;
  live-test )
    shift
    export -f wait_for_boot
    set -x
    live_test_main "$@"
    ;;
  run )
    shift
    trap cleanup EXIT
    run_main "$@"
    ;;
  dl )
    shift
    dl_main "$@"
    ;;
  * )
    print_error "Unknown argument '$1'"
    exit 1
    ;;
esac

# Exit normally, don't run through cleanup again
trap - EXIT
