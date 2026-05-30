export ANDROID_HOME := $(HOME)/Android/Sdk
PACKAGE_NAME := com.pierfrancescocontino.sussurrato

.PHONY: build install run

build:
	./gradlew :app:assembleDebug

install: build
	waydroid app install app/build/outputs/apk/debug/app-debug.apk

run: install
	waydroid app launch $(PACKAGE_NAME)

waydroid-session:
	waydroid session start &