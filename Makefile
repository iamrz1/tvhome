JAVA_HOME ?= /Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
DEVICE ?= 192.168.10.168:5555
ABI = $(shell adb -s $(DEVICE) shell getprop ro.product.cpu.abi | tr -d '\r')
APK = app/build/outputs/apk/release/app-$(ABI)-release.apk

export JAVA_HOME

.PHONY: build lint install run clean

build:
	./gradlew assembleRelease

lint:
	./gradlew lintRelease

install: build
	adb -s $(DEVICE) install -r $(APK)

run:
	adb -s $(DEVICE) shell am start -n com.iamrz1.tvhome/.MainActivity

clean:
	./gradlew clean
