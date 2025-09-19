#!/usr/bin/env bash

files=$(adb shell ls /sdcard/Android/data/org.otago.hci.vrbasedtourism/files/LOG/\*.log)

for file in $files;
do
    adb pull $file ./logs/
    adb shell rm $file
done