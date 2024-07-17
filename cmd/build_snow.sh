# 引入 functions.sh 中定义的函数
source /userdata/snow/snow-env/util.sh
check_args "$1" "$2" "$3"

msg=$1
num=$2
timee=$3
image_tag=rd"$timee"_$msg
image_name=rd_arm10:$image_tag
cur_path=/userdata/snow/$image_tag

run_cmd cd $cur_path

run_cmd mkdir system
run_cmd mkdir vendor

run_cmd sudo mount $cur_path/system.img system -o ro
run_cmd sudo mount $cur_path/vendor.img vendor -o ro

# echo sudo tar --xattrs -c vendor -C system --exclude="./vendor" . | docker import --platform=linux/arm64 -c 'ENTRYPOINT ["/init", "qemu=1", "androidboot.hardware=redroid"]' - $image_name
# sudo tar --xattrs -c vendor -C system --exclude="./vendor" . | docker import --platform=linux/arm64 -c 'ENTRYPOINT ["/init", "qemu=1", "androidboot.hardware=redroid"]' - $image_name
run_cmd sh -c "sudo tar --xattrs -c vendor -C system --exclude=\"./vendor\" . | docker import --platform=linux/arm64 -c 'ENTRYPOINT [\"/init\", \"qemu=1\", \"androidboot.hardware=redroid\"]' - $image_name"

run_cmd umount system
run_cmd umount vendor
run_cmd rm -rf $cur_path/system $cur_path/vendor


run_container $num $image_name
#  docker stop redroid10arm_$num
#  docker rm redroid10arm_$num

# run_cmd docker run -itd  --privileged --name redroid10arm_$num \
#     -v /userdata/snow/redroid_data/rd_$num/:/data \
#     -p 110"$num":5555 \
#     $image_name \
#     androidboot.redroid_width=1080 \
#     androidboot.redroid_height=1920 \
#     androidboot.redroid_dpi=480 \
#     androidboot.redroid_gpu_mode=guest \
