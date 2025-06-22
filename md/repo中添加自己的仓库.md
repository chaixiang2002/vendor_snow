
```
❯ ls .repo/local_manifests                        
snow.xml
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
  <!-- 
    请将下面的 'fetch' 属性值替换为您的 Git 服务器地址，
    例如：fetch="https://github.com/your-username"
    或者您本地的 git 服务器地址，例如：fetch="/path/to/your/git/server"
  -->
  <remote name="snow"
          fetch="https://github.com/chaixiang2002/" />

  <!--
    请将 'name' 替换为您在 Git 服务器上的项目名称。
    'path' 是您希望项目同步到本地的路径。
    'remote' 应该与上面定义的 remote 标签的 name 一致。
    'revision' 是您想要跟踪的分支或标签。
  -->
  <project path="vendor/snow"
           name="vendor_snow"
           remote="snow"
           revision="13-pixel4" />
</manifest> 
```

1. 创建.repo/local_manifests/snow.xml 
2. rm -rf vendor/snow
3. repo sync                       