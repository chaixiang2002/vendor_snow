# functions.sh

run_cmd() {
    echo "Executing: $*"
    "$@"
    local status=$?
    if [ $status -ne 0 ]; then
        echo "Error: Command failed with status $status"
        exit $status
    fi
}

# 定义函数 check_args
check_args() {
    # 检查是否有任意一个参数为空
    if [ -z "$1" ] || [ -z "$2" ] || [ -z "$3" ]; then
        # 打印示例信息
        # echo "Usage: $0 <arg1> <arg2> <arg3>"
        # echo "Example: $0 value1 value2 value3"
        echo "Usage: $0 image_msg ip num"
        echo "Example: $0 oversea_gps 30.34 1"
        # 退出脚本
        exit 1
    fi
}
