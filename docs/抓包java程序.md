
当前项目使用的是OkHttp做的依赖，需要添加wistle的证书为信任的证书。

```
keytool -importcert -alias whistle \
  -file /你下载的/root.crt \
  -keystore "$JAVA_HOME/lib/security/cacerts" \
  -storepass changeit
```

例如实际的：

```
keytool -importcert -alias whistle \
  -file /Users/fengbincao/Downloads/rootCA.crt \
  -keystore "/Users/fengbincao/Documents/App/jdk/jdk17/Contents/Home/lib/security/cacerts" \
  -storepass changeit
```

启动参数environment variables 里写 WHISTLE_PROXY=127.0.0.1:8899 就走 Whistle，删掉这个变量就直连。

另外启动参数中也要设置一个 ZHIPU_API_KEY= 参数，自己设置好baseAPI的key信息。