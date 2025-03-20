# snow app---------------------------------------
PRODUCT_PACKAGES += \
    chanwei-check \
    Clash_Android \
    deviceinfo \
    dgtest \
    juejin \
    LocalSend \
    Palmderive \
    space_check3 \
    termux-snow \
    via 


# test sh
PRODUCT_COPY_FILES += vendor/snow/sh/hello.sh:system/bin/hello.sh


# su
PRODUCT_PACKAGES += lyt 
# Space9dService
# proc_watcher
PRODUCT_PACKAGES += libandroidpw 


# # opengapps
# GAPPS_VARIANT := pico
# $(call inherit-product, vendor/opengapps/build/opengapps-packages.mk)
# # 1. Adding extra packages  添加额外的包
# # GAPPS_PRODUCT_PACKAGES += Chrome
# # 2. Excluding packages  排除包
# # GAPPS_EXCLUDED_PACKAGES := Hangouts
# # 3. DEX pre-optimization  DEX 预优化
# # WITH_DEXPREOPT := true


# # add google maps
# PRODUCT_COPY_FILES += \
#         vendor/snow/google/com.google.android.maps.jar:system/framework/com.google.android.maps.jar \
#         vendor/snow/google/com.google.android.maps.xml:system/etc/permissions/com.google.android.maps.xml

# PRODUCT_PACKAGES += google google_build google-hiddenapi-package-whitelist


# add adbd contorl
PRODUCT_COPY_FILES += vendor/snow/etc/init.adbd.rc:system/etc/init/init.adbd.rc
PRODUCT_PROPERTY_OVERRIDES += \
        lyt.adbd.auth_required=false \
        lyt.adbd.auto_start=true
PRODUCT_HOST_PACKAGES += adbd


# 设置目录权限
LOCAL_POST_INSTALL_CMD := chmod -R 0755 $(TARGET_OUT)/bin/my_scripts


# logcat 落盘存储到/data/misc/logd
PRODUCT_PACKAGES += logcatd logpersist.start
PRODUCT_PROPERTY_OVERRIDES += logd.logpersistd=logcatd 

