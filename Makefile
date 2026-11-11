JAVA_HOME ?= /Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
DEVICE ?= 192.168.10.168:5555
APK := app/build/outputs/apk/release/app-release.apk

export JAVA_HOME

.PHONY: build install run clean

build:
	./gradlew assembleRelease

install: build
	adb -s $(DEVICE) install -r $(APK)

run:
	adb -s $(DEVICE) shell am start -n com.iamrz1.tvhome/.MainActivity

clean:
	./gradlew clean
