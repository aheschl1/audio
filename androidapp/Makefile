GRADLE := env -u ADB_SERVER_SOCKET -u ANDROID_ADB_SERVER_ADDRESS -u ANDROID_ADB_SERVER_PORT ./gradlew
APP_MODULE := app

.PHONY: help debug release clean install test

help:
	@echo "Targets:"
	@echo "  make debug    - Build debug APK"
	@echo "  make release  - Build release APK"
	@echo "  make install  - Install debug APK on connected device"
	@echo "  make test     - Run unit tests"
	@echo "  make clean    - Clean build outputs"

debug:
	$(GRADLE) :$(APP_MODULE):assembleDebug

release:
	$(GRADLE) :$(APP_MODULE):assembleRelease

install:
	adb install -r app/build/outputs/apk/debug/app-debug.apk 

test:
	$(GRADLE) :$(APP_MODULE):testDebugUnitTest

clean:
	$(GRADLE) clean

logs:
	adb logcat --pid $(adb shell pidof -s com.example.androidapp)