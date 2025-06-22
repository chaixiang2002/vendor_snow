Tips：
    1. 查看我的提交                 repo forall -p -c 'git log --grep="SNOW::"'
    2. 查看但前改动的文件            repo forall -c 'git diff --name-only | sed "s|^|$PWD/|"'
    

TODO：
    0001 adb自启动和adb跳过授权【-】

Rule：
    1. 所有更新 都要在修改范围添加      SNOW 任务号-任务简述 start/end
    2. 所有提交 遵循格式              SNOW::任务号::子模块名 feat/fix(模块):简述,动词开头











    type (类型): 必需项。用于说明 commit 的类别，只允许使用下面几种标识。
    feat: 新功能 (feature)。
    fix: 修复 bug。
    docs: 文档 (documentation) 的变动。
    style: 格式（不影响代码运行的变动，例如空格、格式化、缺少分号等）。
    refactor: 重构（即不是新增功能，也不是修改 bug 的代码变动）。
    perf: 性能优化 (performance improvement)。
    test: 增加测试或修改现有测试。
    chore: 构建过程或辅助工具的变动（例如 build, ci 配置等）。
    revert: 回滚到之前的某个提交。
scope (范围): 可选项。
    用于说明 commit 影响的范围，比如数据层、控制层、视图层等等，视项目不同而不同。例如    feat(login): ... 表示登录模块的新功能。
subject (主题): 必需项。
    是 commit 目的的简短描述，不超过50个字符。
    以动词开头，使用第一人称现在时，比如 add，而不是 added 或 adds。
    第一个字母小写。
    结尾不加句号 (.)。



『0001』 预装app、adb无需授权、google可用
『0002』 adb自启动
0003 集成openGapp


1. opengapps[未完成]
引入就崩溃，进不了桌面

2. su[未完成]
权限不被允许，怀疑是selinux的问题。（lyt: setgid failed: Operation not permitted）

3. Space服务[未完成]
暂时未把aidl，framework的服务完全拿过来

4. 预装app[OK]

5. adbd自启和跳过验证[已完成]


6. logcat日志开启存储到本地[未完成]

7. 常用的包curl busybox openssl的集成和加入路径[待办]

8. openssh服务的集成[待办]

-------------------------------------------------
source build/envsetup.sh 
lunch aosp_blueline-user

make update-api
-------------------------------------------------
1. 查看改动文件
repo forall -c 'git diff --name-only | sed "s|^|$PWD/|"'

2. 提交改动
repo forall -c 'git add .'
repo forall -c 'git commit -m "0001 add(adb) :add adb start"'

3. 找到之前的提交
repo forall -p -c 'git log --grep="0001 add"'

4. 清除改动和本地修改
repo forall -c 'git clean -fd'              清理所有仓库中未跟踪的文件和目录。
repo forall -c 'git reset --hard'           丢弃本地修改
-------------------------------------------------

- 向find找到所有rc文件，然后对这些rc文件grep关键字
find / -type f -name "*.rc" -exec grep -Hn "adbd" {} +     


repo forall -p -c 'git log --author="\(chaixiang <973731820@qq.com>\|vm <you@vm.com>\)"'
























---
repo status                                 查看所有仓库的当前分支状态，包括未提交的更改。
repo start <branch_name> --all              在所有仓库中创建并切换到一个新分支。
repo abandon <branch_name>                  删除所有仓库中的指定分支。
repo branches                               查看所有仓库的分支
repo forall -c <command>                    批量执行 Git 命令
repo forall -c 'git clean -fd'              清理所有仓库中未跟踪的文件和目录。
repo forall -c 'git reset --hard'           丢弃本地修改
repo list                                   列出所有仓库

repo diff                                   查看所有仓库中未提交的更改。
---------------------------------------------------------------------
3. 批量提交（可选）
如果你希望一次性提交所有仓库的更改，可以使用 repo forall 命令：

bash
复制
repo forall -c 'git add .'
repo forall -c 'git commit -m "Description of changes"'
注意：这种方式会将所有仓库的改动提交为同一个提交信息，适用于改动较小且相关的情况。

如果你想一次性在所有仓库中查找，可以使用 repo forall：
---------------------------------------------------------------------

bash
复制
repo forall -c 'git log --grep="Description of changes"'
2. 通过提交时间查找
如果你记得大致的提交时间，可以通过 git log 按时间范围查找。

例如，查找某个时间段内的提交：
方法 1：在 repo forall 中打印仓库路径
在运行 repo forall 时，可以通过 -p 参数打印每个仓库的路径，这样就能知道每个提交属于哪个仓库。运行以下命令：

bash
复制
repo forall -p -c 'git log --grep="0001 add"'
---------------------------------------------------------------------

bash
复制
git log --since="2023-10-01" --until="2023-10-31"
----------------
commit eb465166befffe083280d803cbdee0bd5d0feccb
Author: vm <you@vm.com>
Date:   Mon Mar 17 06:38:05 2025 +0000

    0001 add(adb/su/apps) :init pixel3 successful
    
    Change-Id: Ie73225b608d9f558450a05954b06073cf52e85df
root@vm:/snow/android-12.0.0_r34# 
----------------