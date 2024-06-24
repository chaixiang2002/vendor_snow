# 引入 functions.sh 中定义的函数
source ./util.sh

check_args "$1" "$2" "$3"

msg=$1
ip=192.168.$2
num=$3
timee=$(date  +%Y%m%d.%H%M)

filename=redroid_arm64_"$timee"_"$msg".tar
filepath=out/target/product/redroid_arm64/$filename
image_tag=rd"$timee"_$msg
image_name=rd_arm10:$image_tag


# rm -rf IMAGE------------------
source build/envsetup.sh
lunch redroid_arm64-userdebug
m -j 80

mkdir -p                                            IMAGES/$image_tag
run_cmd cp out/target/product/redroid_arm64/system.img      IMAGES/$image_tag/
run_cmd cp out/target/product/redroid_arm64/vendor.img      IMAGES/$image_tag/
run_cmd cp vendor_snow/cmd/build_snow.sh                    IMAGES/$image_tag/
run_cmd cp vendor_snow/cmd/util.sh                          IMAGES/$image_tag/
run_cmd scp -r IMAGES/$image_tag root@$ip:/userdata/snow/
run_cmd scp -r vendor_snow/cmd/util.sh root@$ip:/userdata/snow/cmd/
run_cmd ssh root@$ip /userdata/snow/$image_tag/build_snow.sh           $msg $num $timee

# # 打包
# cd out/target/product/redroid_arm64/ 
# mount system.img system -o ro                                                                       #挂载system 

# mount vendor.img vendor -o ro                                                                       #挂载vendor 

# # tar --xattrs -c vendor -C system --exclude="./vendor" .
# tar --xattrs -c -f $filename vendor -C system --exclude="./vendor" .

# umount system  vendor 

# scp $filepath root@$ip:/userdata/snow/

# # 导入归档文件到Docker--------------------------
# echo ssh root@$ip docker import -c 'ENTRYPOINT ["/init", "androidboot.hardware=redroid"]' /userdata/snow/$filename $image_name

# echo ssh root@$ip docker run -itd  --privileged --name redroid10arm_$num \
#     -v /userdata/snow/redroid_data/rd_$num/:/data \
#     -p 110"$num":5555 \
#     $image_name \
#     androidboot.redroid_width=1080 \
#     androidboot.redroid_height=1920 \
#     androidboot.redroid_dpi=480 \
#     androidboot.redroid_gpu_mode=guest \

# # 导入归档文件到Docker--------------------------
# # ssh root@$ip docker import -c 'ENTRYPOINT ["/init", "androidboot.hardware=redroid"]' /userdata/snow/$filename $image_name

# # ssh root@$ip docker run -itd  --privileged --name redroid10arm_$num \
# #     -v /userdata/snow/redroid_data/rd_$num/:/data \
# #     -p 110"$num":5555 \
# #     $image_name \
# #     androidboot.redroid_width=1080 \
# #     androidboot.redroid_height=1920 \
# #     androidboot.redroid_dpi=480 \
# #     androidboot.redroid_gpu_mode=guest \

# #备份------------------------------------------
# croot

# mkdir IMAGES/image_tag
# mv $filepath IMAGES/image_tag/



