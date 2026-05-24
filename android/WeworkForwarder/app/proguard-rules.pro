# 保留 Gson 序列化的数据类
-keepclassmembers class com.wework.forwarder.** {
    <fields>;
}

# 保留统一授权中心 JSON 字段名
-keepclassmembers class com.wework.forwarder.LicenseManager$VerifyRequest { *; }
-keepclassmembers class com.wework.forwarder.LicenseManager$VerifyResponse { *; }
-keepclassmembers class com.wework.forwarder.Storage$LicenseCache { *; }

# 保留 Android 组件入口
-keep class com.wework.forwarder.MainActivity { *; }
-keep class com.wework.forwarder.CollectorService { *; }
-keep class com.wework.forwarder.WeWorkAccessibilityService { *; }
