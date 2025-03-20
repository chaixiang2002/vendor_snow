#!/bin/bash

# 定义源目录和目标目录
src_dir=$1
dst_dir=$2

# 遍历源目录下的所有文件
for file in "$src_dir"/*; do
    # 获取文件名
    filename=$(basename "$file")
    # 在目标目录下创建软链接
    ln -s "$file" "$dst_dir/$filename"


    echo $filename >> $dst_dir/.gitignore
done

echo '
a_patches
vendor/snow 
' >> $dst_dir/.gitignore

chmod +x $dst_dir/*.sh
