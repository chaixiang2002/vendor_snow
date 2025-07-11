$(warning "执行 device-snow.mk!!!!!")

# 0001 adb自启动和adb跳过授权 start
PRODUCT_PROPERTY_OVERRIDES += \
	persist.snow.adb.auth_required=false \
	persist.snow.adb.start_on_boot=true
# 0001 adb自启动和adb跳过授权 end

# 0002 root权限 start
PRODUCT_PACKAGES += \
	xu_daemon \
	vu

PRODUCT_PROPERTY_OVERRIDES += \
	persist.sys.snow.root.global=1
# 0002 root权限 end