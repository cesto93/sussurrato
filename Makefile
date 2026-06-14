export ANDROID_HOME := $(HOME)/Android/Sdk
PACKAGE_NAME := com.pierfrancescocontino.sussurrato

.PHONY: build install run

build:
	./gradlew :app:assembleDebug

way-install: build
	waydroid app install app/build/outputs/apk/debug/app-debug.apk

install:
	adb install -r app/build/outputs/apk/debug/app-debug.apk

run: way-install
	waydroid app launch $(PACKAGE_NAME)

wayd-session:
	waydroid session start &

test:
	./gradlew test

debug:
	adb logcat -s "ModelDownload:*" "TranscriptionVM:*"
