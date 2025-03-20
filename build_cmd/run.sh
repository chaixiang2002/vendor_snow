# rm -rf IMAGE
source build/envsetup.sh

lunch aosp_x86_64-eng

m $1

# 我 把 setType=0，logd all 打印了 
